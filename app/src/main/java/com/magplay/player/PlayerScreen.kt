package com.magplay.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.magplay.data.AppLog
import com.magplay.data.VideoItem
import com.magplay.subtitle.SrtCue
import com.magplay.subtitle.SrtFile
import com.magplay.subtitle.SubtitleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MODE_NONE = 0
private const val MODE_SPEED = 1
private const val MODE_BRIGHTNESS = 2
private const val MODE_VOLUME = 3
private const val MODE_SEEK = 4

private const val HINT_SPEED = 11
private const val HINT_BRIGHTNESS = 12
private const val HINT_VOLUME = 13
private const val HINT_INFO = 14

/**
 * 手势提示。
 * @param sticky true 表示不参与 1.2s 自动消失（长按倍率期间需一直显示，松手才由 ACTION_UP 清掉）
 */
private data class GestureHint(
    val type: Int,
    val text: String,
    val progress: Float,
    val sticky: Boolean = false,
)

/**
 * 字幕只有一条链路：离线 .srt 文件。
 * 曾经的"实时识别"（伪流式 VAD + 离线模型逐句识别）已按需求砍掉——它整句说完才出字幕，
 * 与画面差 0.5~1 秒，且准确率不可能超过离线全片段识别。准确率优先，只留离线。
 */
@Composable
fun PlayerScreen(item: VideoItem, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val minVolume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
    } else 0
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    // 横屏播放；亮度是本 Activity 私有的，退出时恢复进入前的值
    DisposableEffect(Unit) {
        val previousOrientation = activity?.requestedOrientation
        val originalBrightness = activity?.window?.attributes?.screenBrightness
            ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            if (previousOrientation != null) activity.requestedOrientation = previousOrientation
            activity?.window?.attributes?.let { lp ->
                lp.screenBrightness = originalBrightness
                activity.window.attributes = lp
            }
        }
    }

    // ---- 字幕状态：唯一来源是同目录同名 .srt ----
    var subtitleText by remember { mutableStateOf("") }
    var subtitleOn by remember { mutableStateOf(false) }

    // 进入播放页时查同目录同名 .srt；只有校验通过才用
    var offlineCues by remember { mutableStateOf<List<SrtCue>>(emptyList()) }
    var offlineBad by remember { mutableStateOf(false) }
    val hasOffline = offlineCues.isNotEmpty()

    val player = remember(item.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(item.uri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // 播放失败记进 App 内日志：用户导出后即可定位，无需 adb
                AppLog.e(
                    "Player",
                    "播放失败 ${item.name}（errorCode=${error.errorCodeName}）: ${error.message}",
                    error,
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    AppLog.i("Player", "就绪 ${item.name}，时长 ${player.duration}ms")
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    var hint by remember { mutableStateOf<GestureHint?>(null) }
    var hintSeq by remember { mutableIntStateOf(0) }

    // ---- 控件按需显示：默认隐藏，点画面显示，3 秒后自动隐藏 ----
    var controlsVisible by remember { mutableStateOf(false) }
    var controlsSeq by remember { mutableIntStateOf(0) }
    fun revealControls() {
        controlsVisible = true
        controlsSeq++
    }

    // ---- 画面旋转：两态往复 —— 首次逆时针 90°（竖屏），再点顺时针 90°（回横屏），如此循环 ----
    var rotated by remember { mutableStateOf(false) }
    fun rotateScreen() {
        val act = activity ?: return
        rotated = !rotated
        act.requestedOrientation = if (rotated) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        controlsVisible = true
        controlsSeq++
    }

    fun showHint(type: Int, text: String, progress: Float, sticky: Boolean = false) {
        hint = GestureHint(type, text, progress.coerceIn(0f, 1f), sticky)
        hintSeq++
    }

    /** 双击暂停 / 恢复。已播完则从头播放，避免 playWhenReady=true 仍停在片尾。 */
    fun togglePlayPause() {
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            player.playWhenReady = true
        } else {
            player.playWhenReady = !player.playWhenReady
        }
        Log.i(
            "MagPlayPlayer",
            "double tap -> playWhenReady=${player.playWhenReady} state=${player.playbackState}",
        )
    }

    // ---- 字幕开关 ----
    fun toggleSubtitle() {
        if (!hasOffline) {
            showHint(
                HINT_INFO,
                if (offlineBad) "字幕文件异常，请在列表页重新生成" else "该视频还没有字幕，请在列表页生成",
                0f,
            )
            return
        }
        subtitleOn = !subtitleOn
        subtitleText = ""
        showHint(HINT_INFO, if (subtitleOn) "字幕 开" else "字幕 关", 0f)
        AppLog.i("Subtitle", if (subtitleOn) "开启字幕：${item.name}" else "关闭字幕：${item.name}")
    }

    // 进入播放页：查同目录同名 .srt，校验通过才用
    LaunchedEffect(item.uri) {
        val raw = withContext(Dispatchers.IO) { SubtitleStore.loadCues(context, item) }
        if (raw == null) {
            offlineCues = emptyList()
            offlineBad = false
            return@LaunchedEffect
        }
        if (SrtFile.isUsable(raw, item.durationMs)) {
            val clean = SrtFile.sanitize(raw, item.durationMs)
            offlineCues = clean
            offlineBad = false
            subtitleOn = true
            Log.i("MagPlayPlayer", "offline subtitle matched: ${clean.size} cues for ${item.name}")
            AppLog.i("Subtitle", "离线字幕命中：${clean.size} 条，${item.name}")
        } else {
            // 文件在但内容不可用（时间轴错乱/覆盖过低）：当作没有字幕，提示重新生成
            offlineCues = emptyList()
            offlineBad = true
            Log.w(
                "MagPlayPlayer",
                "offline subtitle invalid: ${raw.size} cues, duration=${item.durationMs}ms, ${item.name}",
            )
            AppLog.w("Subtitle", "离线字幕校验不通过（${raw.size} 条 / 片长 ${item.durationMs}ms）：${item.name}")
            showHint(HINT_INFO, "字幕文件不完整，建议重新生成", 0f)
        }
    }

    // 离线字幕：按播放进度每 200ms 查找当前 cue
    LaunchedEffect(subtitleOn, offlineCues) {
        if (!subtitleOn) return@LaunchedEffect
        while (true) {
            val cue = SrtFile.cueAt(offlineCues, player.currentPosition)
            val text = cue?.text ?: ""
            if (text != subtitleText) subtitleText = text
            delay(200)
        }
    }

    var speed by remember { mutableFloatStateOf(1f) }

    fun applySpeed(value: Float) {
        val clamped = value.coerceIn(1f, 4f)
        speed = clamped
        player.playbackParameters = PlaybackParameters(clamped)
    }

    fun readBrightness(): Float {
        val override = activity?.window?.attributes?.screenBrightness
            ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (override >= 0f) return override.coerceIn(0.01f, 1f)
        return try {
            (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS).toFloat() / 255f)
                .coerceIn(0.01f, 1f)
        } catch (_: Exception) {
            0.5f
        }
    }

    fun writeBrightness(value: Float) {
        val lp = activity?.window?.attributes ?: return
        lp.screenBrightness = value.coerceIn(0.01f, 1f)
        activity.window.attributes = lp
    }

    val handler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(handler) {
        onDispose { handler.removeCallbacksAndMessages(null) }
    }

    LaunchedEffect(hintSeq) {
        // sticky 提示（长按倍率）不自动消失，由 ACTION_UP 显式清除
        if (hintSeq > 0 && hint?.sticky != true) {
            delay(1200)
            if (hint?.sticky != true) hint = null
        }
    }
    LaunchedEffect(controlsSeq) {
        if (controlsSeq > 0) {
            delay(3000)
            controlsVisible = false
        }
    }

    // ref 供点击画面切换控件使用
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                var downX = 0f
                var downY = 0f
                var gestureStartY = 0f
                var mode = MODE_NONE
                var longPressFired = false
                var dragBaseSpeed = 1f
                var speedBeforeLongPress = 1f
                var baseBrightness = 0.5f
                var baseVolume = 0
                var pendingVolume = 0
                var seekBaseX = 0f
                var seekBasePosition = 0L

                // 双击判定：上一次抬手的时间/坐标 + 待执行的单击动作
                var lastTapMs = 0L
                var lastTapX = 0f
                var lastTapY = 0f
                var singleTapRunnable: Runnable? = null

                val density = ctx.resources.displayMetrics.density
                val topExcludePx = 68f * density
                val bottomExcludePx = 72f * density
                val dragLockPx = 14f * density

                // 倍率提示常驻（sticky）：长按还在就一直显示，松手才消失
                val speedHint: (Float) -> Unit = { s ->
                    showHint(HINT_SPEED, "%.2fx".format(s), (s - 1f) / 3f, sticky = true)
                }

                val longPressAction = Runnable {
                    longPressFired = true
                    mode = MODE_NONE
                    speedBeforeLongPress = speed
                    dragBaseSpeed = if (speed <= 1f) 2f else speed
                    applySpeed(dragBaseSpeed)
                    speedHint(dragBaseSpeed)
                    // 不在这里 revealControls：倍率 HUD 已挪到右上角，顶栏同时出现会互相遮挡
                }

                fun enterVerticalMode(view: android.view.View) {
                    longPressFired = true
                    gestureStartY = downY
                    // 调亮度/音量时隐藏顶部栏等无关信息，只保留手势 HUD
                    controlsVisible = false
                    val halfWidth = (if (view.width > 0) view.width else 1) / 2f
                    if (downX < halfWidth) {
                        mode = MODE_BRIGHTNESS
                        baseBrightness = readBrightness()
                        showHint(HINT_BRIGHTNESS, "亮度 ${(baseBrightness * 100).toInt()}%", baseBrightness)
                    } else {
                        mode = MODE_VOLUME
                        baseVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        pendingVolume = baseVolume
                        showHint(
                            HINT_VOLUME, "音量 $pendingVolume/$maxVolume",
                            (pendingVolume - minVolume).toFloat() / (maxVolume - minVolume).coerceAtLeast(1),
                        )
                    }
                }

                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    controllerAutoShow = false
                    setShowFastForwardButton(false)
                    setShowRewindButton(false)
                    setShowPreviousButton(false)
                    setShowNextButton(false)
                    setShowSubtitleButton(false)
                    setShowShuffleButton(false)
                    setShowVrButton(false)
                    // 右下角齿轮（exo_settings）无 setter，视图成型后直接隐藏
                    post {
                        findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)?.visibility =
                            android.view.View.GONE
                        findViewById<android.view.View>(androidx.media3.ui.R.id.exo_repeat_toggle)?.visibility =
                            android.view.View.GONE
                        // 放大中间的播放/暂停按钮（scale 会同步放大触摸热区）
                        findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play)?.let {
                            it.scaleX = 1.7f; it.scaleY = 1.7f
                        }
                        findViewById<android.view.View>(androidx.media3.ui.R.id.exo_pause)?.let {
                            it.scaleX = 1.7f; it.scaleY = 1.7f
                        }
                        // 中央播放/暂停按钮只作状态指示，不再消费触摸：
                        // 否则双击落在它上面会变成"点两下按钮"（暂停→立刻恢复），双击手势失效。
                        findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause)?.let {
                            it.isClickable = false
                            it.isFocusable = false
                        }
                    }
                    playerViewRef = this
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setOnTouchListener { view, event ->
                        if (event.y < topExcludePx || (view.height > 0 && event.y > view.height - bottomExcludePx)) {
                            return@setOnTouchListener false
                        }
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downX = event.x
                                downY = event.y
                                mode = MODE_NONE
                                longPressFired = false
                                handler.postDelayed(longPressAction, 280L)
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = event.x - downX
                                val dy = event.y - downY
                                // 必须加 mode == MODE_NONE 守卫：
                                // 否则进入 MODE_SEEK 后每次 MOVE 都会重新把 seekBaseX 设成当前手指位置，
                                // delta 恒为 0，表现为"横滑只跳一两秒"。
                                if (!longPressFired && mode == MODE_NONE) {
                                    when {
                                        abs(dy) > dragLockPx && abs(dy) > abs(dx) * 1.3f -> {
                                            // 非长按纵向：亮度/音量
                                            handler.removeCallbacks(longPressAction)
                                            enterVerticalMode(view)
                                        }
                                        abs(dx) > dragLockPx && abs(dx) > abs(dy) * 1.3f -> {
                                            // 非长按横向：快进/快退
                                            handler.removeCallbacks(longPressAction)
                                            mode = MODE_SEEK
                                            seekBaseX = event.x
                                            seekBasePosition = player.currentPosition
                                            Log.i(
                                                "MagPlaySeek",
                                                "enter seek: dx=$dx dy=$dy baseX=$seekBaseX base=${seekBasePosition}",
                                            )
                                        }
                                        (abs(dx) > dragLockPx || abs(dy) > dragLockPx) -> {
                                            handler.removeCallbacks(longPressAction)
                                        }
                                    }
                                } else if (mode == MODE_NONE && (abs(dx) > dragLockPx || abs(dy) > dragLockPx)) {
                                    if (abs(dx) > abs(dy) * 1.2f) {
                                        mode = MODE_SPEED
                                    } else {
                                        applySpeed(speedBeforeLongPress)
                                        enterVerticalMode(view)
                                    }
                                }
                                when (mode) {
                                    MODE_SEEK -> {
                                        val viewW = (if (view.width > 0) view.width else 1).toFloat()
                                        // player.duration 在视频刚加载/未就绪时可能是 TIME_UNSET(负数)，
                                        // 之前被 coerceAtLeast(0) 变成 0 → 横滑只有一两秒。
                                        // 用 MediaStore 扫描时的时长兜底，并取两者中较大的可靠值。
                                        val pd = player.duration
                                        val durationMs = if (pd > 0) pd else item.durationMs.coerceAtLeast(0L)
                                        // 整屏横滑 ≈ 全片时长（长视频也能一次到位）
                                        val deltaMs = (event.x - seekBaseX) / viewW * durationMs
                                        val target = (seekBasePosition + deltaMs.toLong())
                                            .coerceIn(0L, durationMs)
                                        Log.i(
                                            "MagPlaySeek",
                                            "w=$viewW pd=$pd itemDur=${item.durationMs} useDur=$durationMs " +
                                                "baseX=$seekBaseX x=${event.x} base=${seekBasePosition} " +
                                                "delta=${deltaMs.toLong()} target=$target",
                                        )
                                        player.seekTo(target)
                                        val sec = target / 1000L
                                        showHint(
                                            HINT_INFO,
                                            "%02d:%02d / %02d:%02d".format(
                                                sec / 60, sec % 60, durationMs / 1000 / 60, durationMs / 1000 % 60,
                                            ),
                                            0f,
                                        )
                                    }
                                    MODE_SPEED -> {
                                        val s = (dragBaseSpeed + dx / 72f * 0.25f).coerceIn(1f, 4f)
                                        applySpeed(s)
                                        speedHint(s)
                                    }
                                    MODE_BRIGHTNESS -> {
                                        val span = (if (view.height > 0) view.height else 1) * 0.75f
                                        val value = (baseBrightness + (gestureStartY - event.y) / span).coerceIn(0.01f, 1f)
                                        writeBrightness(value)
                                        showHint(HINT_BRIGHTNESS, "亮度 ${(value * 100).toInt()}%", value)
                                    }
                                    MODE_VOLUME -> {
                                        val span = (if (view.height > 0) view.height else 1) * 0.75f
                                        val next = baseVolume + (gestureStartY - event.y) / span * (maxVolume - minVolume)
                                        pendingVolume = next.roundToInt().coerceIn(minVolume, maxVolume)
                                        showHint(
                                            HINT_VOLUME, "音量 $pendingVolume/$maxVolume",
                                            (pendingVolume - minVolume).toFloat() / (maxVolume - minVolume).coerceAtLeast(1),
                                        )
                                    }
                                }
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                handler.removeCallbacks(longPressAction)
                                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                                if (mode == MODE_VOLUME && !cancelled) {
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, pendingVolume, 0)
                                }
                                // 长按倍率结束：清掉常驻 HUD，并短暂显示控件让用户看到当前倍率胶囊
                                if (hint?.type == HINT_SPEED) {
                                    hint = null
                                    if (!cancelled) revealControls()
                                }
                                // 单击 / 双击：单击切控件显示，双击暂停或恢复播放
                                if (!longPressFired && mode == MODE_NONE && !cancelled) {
                                    val now = SystemClock.uptimeMillis()
                                    val tapSlop = 80f * density
                                    val isDoubleTap = lastTapMs > 0L &&
                                        now - lastTapMs < 300L &&
                                        abs(event.x - lastTapX) < tapSlop &&
                                        abs(event.y - lastTapY) < tapSlop
                                    if (isDoubleTap) {
                                        // 双击：取消待执行的单击，切播放/暂停
                                        singleTapRunnable?.let { handler.removeCallbacks(it) }
                                        singleTapRunnable = null
                                        lastTapMs = 0L
                                        togglePlayPause()
                                    } else {
                                        // 单击延迟 300ms 执行，给第二击留出判定窗口，避免双击时控件闪一下
                                        lastTapMs = now
                                        lastTapX = event.x
                                        lastTapY = event.y
                                        val action = Runnable {
                                            singleTapRunnable = null
                                            lastTapMs = 0L
                                            controlsVisible = !controlsVisible
                                            if (controlsVisible) {
                                                controlsSeq++
                                                showController()
                                            } else {
                                                hideController()
                                            }
                                        }
                                        singleTapRunnable = action
                                        handler.postDelayed(action, 300L)
                                    }
                                }
                                true
                            }
                            else -> true
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ---- 左上角返回按钮：独立悬浮，不再有顶部黑色横条 ----
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Box(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(start = 12.dp, top = 8.dp)
                    .size(48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "退出播放",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        // ---- 底部右侧：字幕 / 倍率，与播放器自带的时间文字（00:12 / 03:45）同一水平线 ----
        // media3 1.5.1 默认控制条：exo_bottom_bar 高 60dp（时间文字垂直居中，离底约 30dp），
        // exo_progress 在其上方 52~100dp；控制条右侧那排原生按钮已全部隐藏，这块位置是空的。
        // 所以这里 bottom 14dp + 胶囊高约 30dp → 中心约 29dp，正好与时间行齐平。
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 16.dp, bottom = 14.dp),
            ) {
                // 字幕：单击开/关（只有离线 .srt 一条链路，没有可切换的来源）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            when {
                                subtitleOn -> Color(0xFF2E7D52)
                                !hasOffline -> Color.Black.copy(alpha = 0.35f)
                                else -> Color.Black.copy(alpha = 0.55f)
                            },
                        )
                        .clickable { toggleSubtitle() }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = when {
                            !hasOffline -> "无字幕"
                            subtitleOn -> "字幕 开"
                            else -> "字幕 关"
                        },
                        color = if (hasOffline || subtitleOn) Color.White else Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.width(8.dp))
                // 倍率：点一下回到 1.00x
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { applySpeed(1f) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = "%.2fx".format(speed),
                        color = if (speed > 1f) Color(0xFFFFD54F) else Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // ---- 右下角旋转按钮（独立于上面那排，保持在控制条上方） ----
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            Box(
                modifier = Modifier
                    .padding(end = 20.dp, bottom = 84.dp)
                    .size(52.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { rotateScreen() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.ScreenRotation,
                    contentDescription = "旋转画面",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        // 字幕叠加层（属于视频内容，始终显示）——贴底、字号收小
        if (subtitleOn && subtitleText.isNotBlank()) {
            Text(
                text = subtitleText,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp, start = 48.dp, end = 48.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // ---- 手势 HUD：居中胶囊 + 进度条 ----
        val currentHint = hint
        if (currentHint != null) {
            when (currentHint.type) {
                HINT_INFO -> {
                    Text(
                        text = currentHint.text,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
                HINT_SPEED -> {
                    // 右上角小胶囊，与亮度/音量 HUD 同规格；长按期间常驻（不自动消失）
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 36.dp, end = 20.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = currentHint.text,
                            color = Color(0xFFFFD97A),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(5.dp))
                        Box(
                            modifier = Modifier
                                .width(96.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.2f)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(currentHint.progress)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0xFFFFC94D)),
                            )
                        }
                    }
                }
                HINT_BRIGHTNESS, HINT_VOLUME -> {
                    // 亮度提示靠左上、音量提示靠右上，尺寸收小，不遮挡画面中央
                    val isBrightness = currentHint.type == HINT_BRIGHTNESS
                    val accent = if (isBrightness) Color(0xFFFFD97A) else Color(0xFF7DD8A8)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(if (isBrightness) Alignment.TopStart else Alignment.TopEnd)
                            .padding(
                                top = 36.dp,
                                start = if (isBrightness) 20.dp else 0.dp,
                                end = if (isBrightness) 0.dp else 20.dp,
                            )
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = currentHint.text,
                            color = accent,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(5.dp))
                        Box(
                            modifier = Modifier
                                .width(96.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.2f)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(currentHint.progress)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(accent),
                            )
                        }
                    }
                }
            }
        }
    }
}
