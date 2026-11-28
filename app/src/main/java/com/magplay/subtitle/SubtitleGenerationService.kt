package com.magplay.subtitle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.magplay.MainActivity
import com.magplay.R
import com.magplay.data.VideoItem
import com.magplay.data.VideoScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 后台批量生成离线字幕：遍历当前无 .srt 的视频，逐个解码→识别→写盘。
 * 前台服务 + 通知显示进度，支持取消（通过 stop action）。
 *
 * 内存策略（修复批量 OOM）：
 * - 大模型（228MB）整批只加载一次，所有视频复用，批次结束统一释放。
 * - 音频解码为分块 16k PCM，逐块识别，块用完即弃。
 * - 每个视频处理完主动提示 GC。
 */
class SubtitleGenerationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val cancelled = AtomicBoolean(false)

    /** 批次期间持有的唤醒锁：没有它，锁屏后 CPU 进入休眠，解码/识别线程会整体停住。 */
    private var wakeLock: PowerManager.WakeLock? = null

    /** 心跳日志节流：每 60s 记一次，用来事后判断"锁屏期间是否还在跑"。 */
    private var lastBeatAt = 0L
    private var lastBeatText = ""

    /** 跨进程状态快照节流：写入 status.json 供日志页「后台自检」读取。 */
    private var lastStatusAt = 0L
    private var statusText = ""

    /** 本批次启动时刻（elapsedRealtime）。系统 mediaProcessing FGS 24h 内只给 6h。 */
    private var batchStartedAt = 0L

    /**
     * 暂停/超时后不再让 finally 把状态覆盖成「批次结束」，
     * 否则后台自检卡片看不到真正的中断原因。
     */
    private var statusFinalized = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled.set(true)
                job?.cancel()
                updateNotification("正在取消…", 0, false)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // 防重复点击只看本进程真实在跑的协程。
        // 不能拿 RUNNING 当守卫：它是 :subtitle 进程的静态字段，进程被回收后服务重建时
        // 会残留旧值，导致「点了但没有任何通知」——早上那次就是这么被吞掉的。
        if (job?.isActive == true) {
            Log.i(TAG, "ignore duplicate start, job still active")
            return START_NOT_STICKY
        }
        val force = intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false
        val onlyId = intent?.getLongExtra(EXTRA_ONLY_ID, -1L) ?: -1L
        batchStartedAt = SystemClock.elapsedRealtime()
        statusFinalized = false
        RUNNING.set(true)
        cancelled.set(false)
        // 内核锁：进程被强杀时由 OS 释放，主进程据此判断"任务是否真在跑"。
        // 必须在启动协程前拿锁，否则 UI 可能在窗口期误判为空闲。
        GenStatus.acquireRunLock(this)
        startForegroundCompat(
            buildNotification(
                when {
                    onlyId > 0 -> "准备生成该视频的字幕…"
                    force -> "准备重新生成全部字幕…"
                    else -> "准备生成字幕…"
                },
                0,
                false,
            ),
        )
        // 必须在开始干活前就拿锁：否则用户一锁屏，下面第一批解码就停了
        acquireWakeLock()
        publishStatus("准备生成字幕…", force = true)

        job = scope.launch {
            runBatch(force, onlyId)
        }
        return START_NOT_STICKY
    }

    /**
     * 前台服务声明了 mediaProcessing 类型。targetSdk=35 时 Android 15+ 要求按类型启动，
     * 显式传入类型可以避免部分 ROM 上"类型未指定"的隐式降级。
     */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Android 15 起，mediaProcessing 前台服务 24 小时内累计最多跑 6 小时。
     * 超时系统会回调这里，几秒内不 stopSelf 就抛 RemoteServiceException 直接崩进程。
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        fileLog("onTimeout fgsType=$fgsType -> graceful stop")
        cancelled.set(true)
        statusFinalized = true
        // 立即落盘：超时后进程随时被系统回收，不能等 runBatch 的 finally 慢慢刷状态。
        RUNNING.set(false)
        val text = "已达系统 6 小时上限，已暂停。点通知「继续生成」接着跑"
        publishStatus(text, force = true)
        try {
            updateNotification(text, 100, false, canResume = true)
        } catch (_: Throwable) {
        }
        // onTimeout 之后服务已不是前台服务，普通 stopSelf 会连带删掉通知。
        // 先 detach，用户才能看到「为什么停了」以及继续按钮。
        detachForeground()
        job?.cancel()
        stopSelf()
    }

    /**
     * 在 6 小时硬上限前主动收尾。继续跑满会被系统 onTimeout 强停，
     * 当前视频的半成品也不会写盘，提前停没有数据损失。
     */
    private fun pauseBeforeLimit() {
        if (cancelled.get()) return
        fileLog("auto pause before 6h FGS limit")
        cancelled.set(true)
        statusFinalized = true
        RUNNING.set(false)
        val text = "已跑满 5 小时 50 分，自动暂停。点通知「继续生成」接着跑"
        publishStatus(text, force = true)
        try {
            updateNotification(text, 100, false, canResume = true)
        } catch (_: Throwable) {
        }
        detachForeground()
        job?.cancel()
        stopSelf()
    }

    private fun detachForeground() {
        try {
            stopForeground(STOP_FOREGROUND_DETACH)
        } catch (_: Throwable) {
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MagPlay:subtitleGen").apply {
                setReferenceCounted(false)
                // 上限 12 小时，避免异常退出时永久占锁
                acquire(12 * 60 * 60 * 1000L)
            }
            fileLog("wakelock acquired（锁屏后可继续）")
        } catch (t: Throwable) {
            fileLog("wakelock acquire failed: ${t.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
        fileLog("wakelock released")
    }

    /** 每 60 秒记一次心跳，事后可从 gen.log 判断锁屏期间是否仍在推进。 */
    private fun heartbeat(text: String) {
        if (batchStartedAt > 0L &&
            SystemClock.elapsedRealtime() - batchStartedAt >= AUTO_PAUSE_MS
        ) {
            pauseBeforeLimit()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBeatAt < 60_000L) return
        lastBeatAt = now
        lastBeatText = text
        fileLog("beat: $text")
        // 心跳顺带刷新 status.json：即使解码进度回调稀疏，自检卡片最多也只滞后 60s
        publishStatus(text, force = true)
    }

    /**
     * 把当前状态写进 status.json，让主进程的「后台自检」卡片能看到。
     * 正常进度 2s 节流（解码进度回调很密），关键节点用 force=true 立刻写。
     */
    private fun publishStatus(text: String = statusText, force: Boolean = false) {
        // 暂停/超时后，finally 与取消通知不能把原因刷掉
        if (statusFinalized && text != statusText) return
        val now = System.currentTimeMillis()
        if (!force && now - lastStatusAt < 2_000L) return
        lastStatusAt = now
        statusText = text
        GenStatus.write(
            context = this,
            running = RUNNING.get(),
            text = text,
            wakeLock = wakeLock?.isHeld == true,
        )
    }

    private suspend fun runBatch(force: Boolean, onlyId: Long = -1L) {
        var generator: OfflineSubtitleGenerator? = null
        try {
            // 引擎要在扫描前确定：待办判定依赖"这份字幕是不是当前引擎生成的"
            val backend = AsrSettings.backend(this@SubtitleGenerationService)
            fileLog("engine: ${backend.id} (${backend.label})")
            val (all, pendingInit) = if (onlyId > 0) {
                // 单视频模式：只按 id 精确取一条，不扫全量
                val one = withContext(Dispatchers.IO) {
                    VideoScanner.findById(this@SubtitleGenerationService, onlyId)
                }
                if (one == null) {
                    fileLog("single: video gone, id=$onlyId")
                    emptyList<VideoItem>() to emptyList<VideoItem>()
                } else {
                    // 用户显式点了「生成本视频字幕」= 覆盖重来：先清旧字幕和失败记录，
                    // 否则已有可用字幕会被判成"无需处理"，点了没有任何反应。
                    withContext(Dispatchers.IO) {
                        SubtitleStore.clearForRegenerate(this@SubtitleGenerationService, one)
                        GenAttempts.forget(this@SubtitleGenerationService, one)
                        fileLog("single: ${one.name} (${one.relativePath})")
                    }
                    listOf(one) to listOf(one)
                }
            } else withContext(Dispatchers.IO) {
                val scanned = VideoScanner.scan(this@SubtitleGenerationService)
                fileLog("scan: total=${scanned.size} force=$force")
                if (force) {
                    // 重新生成：直接删除所有已存在的字幕（UI 已二次确认），再全量重做
                    var cleared = 0
                    scanned.forEach {
                        if (SubtitleStore.clearForRegenerate(this@SubtitleGenerationService, it)) cleared++
                    }
                    // 清空「已试过」记录，让之前跳过的视频重新参与
                    GenAttempts.clear(this@SubtitleGenerationService)
                    fileLog("force mode: cleared $cleared srt files, reset attempt records")
                    scanned to scanned
                } else {
                    // 只跳过「存在且校验通过」的字幕；坏文件（时间轴错乱/覆盖过低）重新生成
                    var skippedByAttempt = 0
                    var staleEngine = 0
                    val todo = scanned.filterNot { video ->
                        val usable = SubtitleStore.hasUsable(this@SubtitleGenerationService, video)
                        if (usable) {
                            if (SubtitleStore.hasUsableForEngine(
                                    this@SubtitleGenerationService, video, backend.id,
                                )
                            ) {
                                GenAttempts.forget(this@SubtitleGenerationService, video)
                                return@filterNot true
                            }
                            // 字幕可用但不是当前引擎生成的：换模型后需要重做
                            staleEngine++
                            return@filterNot false
                        }
                        // 已经试过且拿不到可用字幕的先跳过，避免每次重解码（无对白短视频）
                        val why = GenAttempts.shouldSkip(this@SubtitleGenerationService, video)
                        if (why != null) {
                            skippedByAttempt++
                            return@filterNot true
                        }
                        if (SubtitleStore.exists(this@SubtitleGenerationService, video)) {
                            fileLog("invalid srt, will regenerate: ${video.name}")
                        }
                        false
                    }
                    if (skippedByAttempt > 0) {
                        fileLog("skipped by attempt record: $skippedByAttempt")
                    }
                    if (staleEngine > 0) {
                        fileLog("stale engine (${backend.id}) -> regenerate: $staleEngine")
                    }
                    scanned to todo
                }
            }
            var pending = pendingInit
            // 带字幕轨的视频（容器自带软字幕，如 text/3gpp-tt）无需 ASR 生成。
            // 放在加载模型之前过滤：省下几百 MB 模型加载 + 整段解码识别。
            // 只读取容器轨道头，每个视频几毫秒。
            var skippedByTrack = 0
            if (pending.isNotEmpty()) {
                val withoutTrack = mutableListOf<VideoItem>()
                for (video in pending) {
                    val hasTrack = withContext(Dispatchers.IO) {
                        SubtitleTrackDetector.hasTextTrack(
                            this@SubtitleGenerationService, video.uri,
                        )
                    }
                    if (hasTrack) {
                        skippedByTrack++
                        fileLog("skip built-in subtitle track: ${video.name}")
                    } else {
                        withoutTrack.add(video)
                    }
                }
                pending = withoutTrack
                if (skippedByTrack > 0) {
                    fileLog("skipped by subtitle track: $skippedByTrack")
                }
            }

            Log.i(TAG, "batch start: total=${all.size} pending=${pending.size} skippedByTrack=$skippedByTrack")
            if (pending.isEmpty()) {
                updateNotification(
                    when {
                        skippedByTrack > 0 -> "有字幕轨，无需生成（跳过 $skippedByTrack 个）"
                        onlyId > 0 -> "该视频已不存在（可能被删除或移动）"
                        else -> "所有视频均已有字幕"
                    },
                    100,
                    false,
                )
                // 不在这里 stopSelf：交给 finally 统一 detach，通知才能留在通知栏
                return
            }

            // 模型整批只加载一次（重操作，切到 Default 线程，避免卡主线程）
            withContext(Dispatchers.Default) {
                updateNotification("加载 ${backend.label} 模型（约 ${backend.sizeMb}MB）…", 0, true)
                generator = OfflineSubtitleGenerator(
                    this@SubtitleGenerationService,
                    backend,
                    onDiagnostic = { fileLog(it) },
                ).also { it.prepare() }
            }
            val gen = generator ?: run {
                updateNotification("模型加载失败", 0, false)
                return
            }
            if (!gen.isPrepared()) {
                fileLog("engine load failed: ${gen.lastError}")
                updateNotification("模型加载失败：${gen.lastError ?: "未知原因"}", 0, false)
                return
            }
            fileLog("engine ready: ${backend.id} (${backend.label})")

            var done = 0
            var succeeded = 0
            var failed = 0
            // 失败原因计数：结束时直接写进通知，用户不用去翻日志就知道为什么没生成
            val failReasons = linkedMapOf<String, Int>()
            fun countFail(reason: String) {
                failReasons[reason] = (failReasons[reason] ?: 0) + 1
            }

            for (video in pending) {
                if (cancelled.get() || !currentCoroutineContext().isActive) break
                val name = video.name
                updateNotification("(${done + 1}/${pending.size}) $name", 0, true)
                heartbeat("${done + 1}/${pending.size} $name")

                try {
                    // 解码与识别流水线：每解出一块（约 60s）立刻识别，处理完即丢。
                    // 全程只驻留一块 PCM，内存与视频时长无关。
                    val allCues = mutableListOf<SrtCue>()
                    val durMs = withContext(Dispatchers.IO) {
                        try {
                            AudioDecoder.decodeStreaming(
                                context = this@SubtitleGenerationService,
                                uri = video.uri,
                                onProgress = { p ->
                                    heartbeat("decode ${done + 1}/${pending.size} $name $p%")
                                    updateNotification(
                                        "(${done + 1}/${pending.size}) 解码音频 $p%",
                                        (p * 0.5f).toInt(), true,
                                    )
                                },
                                shouldContinue = { !cancelled.get() },
                            ) { chunk ->
                                if (cancelled.get()) return@decodeStreaming
                                val cues = gen.generate(
                                    chunk,
                                    isActive = { !cancelled.get() },
                                    onProgress = { prog ->
                                        val pct = 50 + (
                                            chunk.offsetMs * 50 /
                                                video.durationMs.coerceAtLeast(1L)
                                            ).toInt()
                                        updateNotification(
                                            "(${done + 1}/${pending.size}) ${prog.stage}",
                                            pct.coerceIn(50, 95), true,
                                        )
                                    },
                                )
                                allCues.addAll(cues)
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "decode threw: ${video.name}", t)
                            null
                        }
                    }
                    if (durMs == null) {
                        failed++
                        countFail("无音轨")
                        Log.w(TAG, "no audio / decode failed: $name")
                        fileLog("no audio: $name")
                        GenAttempts.record(this@SubtitleGenerationService, video, "noaudio")
                        done++
                        continue
                    }
                    if (allCues.isEmpty()) {
                        failed++
                        countFail("未检测到语音")
                        Log.w(TAG, "no speech recognized: $name")
                        fileLog("no speech: $name")
                        GenAttempts.record(this@SubtitleGenerationService, video, "nospeech")
                        done++
                        continue
                    }

                    // 洗净 + 校验：丢掉倒挂/越界条目，保证写出的时间轴单调且在片长内
                    val durationForCheck = if (video.durationMs > 0) video.durationMs else durMs
                    val clean = SrtFile.sanitize(allCues, durationForCheck)
                    if (clean.isEmpty()) {
                        failed++
                        countFail("时间轴无效")
                        Log.w(TAG, "all cues invalid after sanitize: $name")
                        fileLog("all cues invalid: $name raw=${allCues.size}")
                        GenAttempts.record(this@SubtitleGenerationService, video, "invalid")
                        done++
                        continue
                    }
                    fileLog(
                        "recognized: $name dur=${durationForCheck}ms raw=${allCues.size} " +
                            "clean=${clean.size} first=${clean.first().startMs}-${clean.first().endMs} " +
                            "last=${clean.last().startMs}-${clean.last().endMs}",
                    )

                    // 3) 写 SRT（IO 线程）
                    val srt = SrtFile.serialize(clean)
                    val uri = withContext(Dispatchers.IO) {
                        SubtitleStore.writeSrt(this@SubtitleGenerationService, video, srt)
                    }
                    if (uri != null) {
                        succeeded++
                        // 记录生成引擎：换模型后这批字幕会被判定为"需要重做"
                        SubtitleStore.recordEngine(this@SubtitleGenerationService, video, backend.id)
                        Log.i(TAG, "subtitle saved: $name (${clean.size} cues)")
                        // 写成功了但校验不过（台词太少、覆盖不到片长）也记录下来，
                        // 否则下一轮又被当成"缺字幕"重新解码。
                        if (SrtFile.isUsable(clean, durationForCheck)) {
                            GenAttempts.forget(this@SubtitleGenerationService, video)
                        } else {
                            fileLog("short srt: $name cues=${clean.size}")
                            GenAttempts.record(this@SubtitleGenerationService, video, "short")
                        }
                    } else {
                        failed++
                        countFail("写入失败")
                        Log.w(TAG, "write srt failed: $name")
                        fileLog("write failed: $name")
                        GenAttempts.record(this@SubtitleGenerationService, video, "writefail")
                    }
                } catch (t: Throwable) {
                    failed++
                    countFail("识别异常")
                    Log.e(TAG, "generate failed: $name", t)
                    fileLog("generate threw: $name ${t.message}")
                }
                done++
            }

            val reasonText = if (failReasons.isEmpty()) "" else
                "（" + failReasons.entries.joinToString("、") { "${it.key} ${it.value}" } + "）"
            val trackText = if (skippedByTrack > 0) " / 跳过字幕轨 $skippedByTrack" else ""
            val msg = when {
                cancelled.get() -> "已取消（成功 $succeeded / 失败 $failed$trackText）"
                onlyId > 0 && succeeded > 0 -> "字幕已生成，进视频即可打开"
                onlyId > 0 -> "没生成字幕 $reasonText"
                else -> "完成：成功 $succeeded / 失败 $failed / 共 ${pending.size}$trackText$reasonText"
            }
            if (!statusFinalized) updateNotification(msg, 100, false)
            Log.i(TAG, "batch finished: $msg")
            fileLog("batch finished: $msg")
        } finally {
            // 先刷状态再关模型：generator.close() 释放 1.2GB native 模型可能耗时数秒，
            // 系统超时停服务时进程可能在此之前被回收。状态必须先写，否则用户看到的
            // 永远是超时前一拍的"识别中"，误以为还在跑（实测 11-27 的批量生成就这样）。
            RUNNING.set(false)
            if (!statusFinalized) publishStatus("批次结束", force = true)
            releaseWakeLock()
            // 无论正常/取消/异常，都释放大模型。
            // 必须 NonCancellable：服务被 stopSelf/onDestroy 取消时，普通 withContext 会直接
            // 抛 CancellationException 跳过关闭，228MB 模型泄漏；再点一次又加载一份，反复几次吃穿 native 内存。
            withContext(NonCancellable + Dispatchers.Default) {
                generator?.close()
            }
            // 关键：结束时把通知从前台服务解绑（DETACH），而不是让它随 stopSelf 一起消失。
            // 否则进度条一闪就没，用户根本不知道到底成没成功；现在它会留在通知栏（可手动滑掉）。
            detachForeground()
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        scope.coroutineContext[Job]?.cancel()
        GenStatus.releaseRunLock()
    }

    // ---- 日志：logcat 环形缓冲很短，崩溃后往往抓不到；同时落一份文件日志便于事后定位 ----
    private fun fileLog(msg: String) {
        try {
            val dir = File(getExternalFilesDir(null), "logs")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "gen.log")
            if (f.exists() && f.length() > 200 * 1024) {
                val bak = File(dir, "gen.log.1")
                if (bak.exists()) bak.delete()
                f.renameTo(bak)
            }
            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            f.appendText("$ts $msg\n")
        } catch (_: Throwable) {}
    }

    // ---- 通知 ----
    private fun buildNotification(
        text: String,
        progress: Int,
        ongoing: Boolean,
        canResume: Boolean = false,
    ): Notification {
        createChannelIfNeeded()
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getService(
            this, 1, Intent(this, SubtitleGenerationService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val resumeIntent = PendingIntent.getService(
            this, 2, Intent(this, SubtitleGenerationService::class.java).setAction(ACTION_RESUME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_subtitle)
            .setContentTitle("离线字幕生成")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // LOW 在小米上会被折进「静默通知」，状态栏看不到，用户以为没在生成
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (ongoing) {
            builder.setProgress(100, progress, false)
                .addAction(android.R.drawable.ic_delete, "取消", cancelIntent)
        }
        if (canResume) {
            builder.addAction(android.R.drawable.ic_media_play, "继续生成", resumeIntent)
        }
        return builder.build()
    }

    private fun updateNotification(
        text: String,
        progress: Int,
        ongoing: Boolean,
        canResume: Boolean = false,
    ) {
        // 识别阶段只刷通知、不走 heartbeat，时限检查必须在这里也做一次。
        if (ongoing && batchStartedAt > 0L &&
            SystemClock.elapsedRealtime() - batchStartedAt >= AUTO_PAUSE_MS
        ) {
            pauseBeforeLimit()
            return
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text, progress, ongoing, canResume))
        // 自检卡片跟着通知文案走，省得两处各维护一份状态
        if (!statusFinalized) publishStatus(text)
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // 旧渠道 subtitle_gen 被 ROM 固定在 LOW（静默、状态栏不显示）。
            // 同 id 重建改不动 importance，直接换 id 走新渠道。
            val ch = NotificationChannel(
                CHANNEL_ID, "字幕生成", NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "离线字幕生成进度；暂停后可从通知继续"
            }
            nm.createNotificationChannel(ch)
        }
    }

    companion object {
        private const val TAG = "SubtitleGenService"
        private const val CHANNEL_ID = "subtitle_gen_v2"
        private const val NOTIFICATION_ID = 2026
        const val ACTION_CANCEL = "com.magplay.subtitle.CANCEL"
        const val ACTION_RESUME = "com.magplay.subtitle.RESUME"
        /** 6h 上限前 10 分钟收尾，留足写盘与 detach 的时间。 */
        private const val AUTO_PAUSE_MS = 350L * 60 * 1000L
        private const val EXTRA_FORCE = "com.magplay.subtitle.FORCE"
        private const val EXTRA_ONLY_ID = "com.magplay.subtitle.ONLY_ID"

        /** 批次级单实例守卫：服务实例被系统重建时也不会并发跑两批。 */
        private val RUNNING = AtomicBoolean(false)

        /** 是否已有任务在跑（UI 用它避免"点了没反应"）。 */
        fun isRunning(): Boolean = RUNNING.get()

        /**
         * 启动批量生成。
         * @param force false = 只为"没有可用字幕"的视频生成（增量补齐）；
         *              true  = 直接删除所有字幕文件（不备份），再全量重新生成。
         */
        fun start(context: Context, force: Boolean = false) {
            val intent = Intent(context, SubtitleGenerationService::class.java)
                .putExtra(EXTRA_FORCE, force)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 只为某一个视频生成字幕（长按菜单入口）。
         *
         * 与 [start] 的区别：不扫全量、不看别的视频、已有字幕会被覆盖重做。
         * 服务内部按 id 直接用 `_ID=?` 查一条，几十毫秒就返回。
         */
        fun startSingle(context: Context, videoId: Long) {
            val intent = Intent(context, SubtitleGenerationService::class.java)
                .putExtra(EXTRA_ONLY_ID, videoId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
