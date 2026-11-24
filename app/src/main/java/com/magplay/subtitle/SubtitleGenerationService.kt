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

        // 防重复点击：已有任务在跑则忽略重复 start
        if (RUNNING.get() || job?.isActive == true) {
            Log.i(TAG, "ignore duplicate start, job still active")
            return START_NOT_STICKY
        }
        val force = intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false
        RUNNING.set(true)
        cancelled.set(false)
        startForegroundCompat(
            buildNotification(if (force) "准备重新生成全部字幕…" else "准备生成字幕…", 0, false),
        )
        // 必须在开始干活前就拿锁：否则用户一锁屏，下面第一批解码就停了
        acquireWakeLock()
        publishStatus("准备生成字幕…", force = true)

        job = scope.launch {
            runBatch(force)
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
        try {
            updateNotification("已达系统 6 小时上限，已暂停。回到 App 再点一次可继续", 100, false)
        } catch (_: Throwable) {
        }
        job?.cancel()
        stopSelf()
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

    private suspend fun runBatch(force: Boolean) {
        var generator: OfflineSubtitleGenerator? = null
        try {
            val (all, pending) = withContext(Dispatchers.IO) {
                val scanned = VideoScanner.scan(this@SubtitleGenerationService)
                fileLog("scan: total=${scanned.size} force=$force")
                if (force) {
                    // 重新生成：先备份并清空所有已存在的字幕，再全量重做
                    var cleared = 0
                    scanned.forEach {
                        if (SubtitleStore.clearForRegenerate(this@SubtitleGenerationService, it)) cleared++
                    }
                    fileLog("force mode: cleared $cleared srt files")
                    scanned to scanned
                } else {
                    // 只跳过「存在且校验通过」的字幕；坏文件（时间轴错乱/覆盖过低）重新生成
                    val todo = scanned.filterNot {
                        val ok = SubtitleStore.hasUsable(this@SubtitleGenerationService, it)
                        if (!ok && SubtitleStore.exists(this@SubtitleGenerationService, it)) {
                            fileLog("invalid srt, will regenerate: ${it.name}")
                        }
                        ok
                    }
                    scanned to todo
                }
            }
            Log.i(TAG, "batch start: total=${all.size} pending=${pending.size}")
            if (pending.isEmpty()) {
                updateNotification("所有视频均已有字幕", 100, false)
                stopSelf()
                return
            }

            // 模型整批只加载一次（重操作，切到 Default 线程，避免卡主线程）
            withContext(Dispatchers.Default) {
                updateNotification("加载语音识别模型（约 228MB）…", 0, true)
                generator = OfflineSubtitleGenerator(this@SubtitleGenerationService).also { it.prepare() }
            }
            val gen = generator ?: run {
                updateNotification("模型加载失败", 0, false)
                stopSelf()
                return
            }

            var done = 0
            var succeeded = 0
            var failed = 0

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
                        Log.w(TAG, "no audio / decode failed: $name")
                        done++
                        continue
                    }
                    if (allCues.isEmpty()) {
                        failed++
                        Log.w(TAG, "no speech recognized: $name")
                        fileLog("no speech: $name")
                        done++
                        continue
                    }

                    // 洗净 + 校验：丢掉倒挂/越界条目，保证写出的时间轴单调且在片长内
                    val durationForCheck = if (video.durationMs > 0) video.durationMs else durMs
                    val clean = SrtFile.sanitize(allCues, durationForCheck)
                    if (clean.isEmpty()) {
                        failed++
                        Log.w(TAG, "all cues invalid after sanitize: $name")
                        fileLog("all cues invalid: $name raw=${allCues.size}")
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
                        Log.i(TAG, "subtitle saved: $name (${clean.size} cues)")
                    } else {
                        failed++
                        Log.w(TAG, "write srt failed: $name")
                        fileLog("write failed: $name")
                    }
                } catch (t: Throwable) {
                    failed++
                    Log.e(TAG, "generate failed: $name", t)
                    fileLog("generate threw: $name ${t.message}")
                }
                done++
            }

            val msg = if (cancelled.get()) {
                "已取消（成功 $succeeded / 失败 $failed）"
            } else {
                "完成：成功 $succeeded / 失败 $failed / 共 ${pending.size}"
            }
            updateNotification(msg, 100, false)
            Log.i(TAG, "batch finished: $msg")
            fileLog("batch finished: $msg")
        } finally {
            // 无论正常/取消/异常，都释放大模型。
            // 必须 NonCancellable：服务被 stopSelf/onDestroy 取消时，普通 withContext 会直接
            // 抛 CancellationException 跳过关闭，228MB 模型泄漏；再点一次又加载一份，反复几次吃穿 native 内存。
            withContext(NonCancellable + Dispatchers.Default) {
                generator?.close()
            }
            RUNNING.set(false)
            releaseWakeLock()
            publishStatus("批次结束", force = true)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        scope.coroutineContext[Job]?.cancel()
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
    private fun buildNotification(text: String, progress: Int, ongoing: Boolean): Notification {
        createChannelIfNeeded()
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getService(
            this, 1, Intent(this, SubtitleGenerationService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_subtitle)
            .setContentTitle("离线字幕生成")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (ongoing) {
            builder.setProgress(100, progress, false)
                .addAction(android.R.drawable.ic_delete, "取消", cancelIntent)
        }
        return builder.build()
    }

    private fun updateNotification(text: String, progress: Int, ongoing: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text, progress, ongoing))
        // 自检卡片跟着通知文案走，省得两处各维护一份状态
        publishStatus(text)
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "字幕生成", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    companion object {
        private const val TAG = "SubtitleGenService"
        private const val CHANNEL_ID = "subtitle_gen"
        private const val NOTIFICATION_ID = 2026
        const val ACTION_CANCEL = "com.magplay.subtitle.CANCEL"
        private const val EXTRA_FORCE = "com.magplay.subtitle.FORCE"

        /** 批次级单实例守卫：服务实例被系统重建时也不会并发跑两批。 */
        private val RUNNING = AtomicBoolean(false)

        /**
         * 启动批量生成。
         * @param force false = 只为"没有可用字幕"的视频生成（增量补齐）；
         *              true  = 先备份并清空所有字幕文件，再全量重新生成。
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
    }
}
