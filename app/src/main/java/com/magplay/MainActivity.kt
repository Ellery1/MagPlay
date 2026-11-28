package com.magplay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.magplay.data.AppLog
import com.magplay.data.AppPrefs
import com.magplay.data.PlaybackProgress
import com.magplay.data.VideoItem
import com.magplay.data.VideoScanner
import com.magplay.player.PlayerScreen
import com.magplay.ui.LogScreen
import com.magplay.ui.MagPlayTheme
import com.magplay.ui.SettingsScreen
import com.magplay.ui.VideoListScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val videoPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun hasVideoPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, videoPermission) == PackageManager.PERMISSION_GRANTED

    private fun openLogsRequested(): Boolean =
        intent?.getBooleanExtra(EXTRA_OPEN_LOGS, false) == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppLog.init(this)
        // 主题/字幕字号等偏好要在 setContent 之前读出来，否则首帧会用默认值闪一下
        AppPrefs.init(this)
        // Android 13+ 通知权限（后台字幕生成需要显示进度）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
        }
        setContent {
            MagPlayTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MagPlayApp()
                }
            }
        }
    }

    /** 第一屏的三种状态：没权限 / 正在扫描 / 有数据。界面据此显示不同内容，不再一律空白。 */
    private sealed interface ScanUi {
        data object NoPermission : ScanUi
        data object Loading : ScanUi
        data class Ready(val videos: List<VideoItem>) : ScanUi
    }

    @Composable
    private fun MagPlayApp() {
        val context = LocalContext.current
        var granted by remember { mutableStateOf(hasVideoPermission()) }
        var refreshKey by remember { mutableIntStateOf(0) }
        var showLogs by remember { mutableStateOf(openLogsRequested()) }
        var showSettings by remember { mutableStateOf(false) }
        val requestPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            granted = isGranted
            AppLog.i("Perm", "读取视频权限：${if (isGranted) "已授予" else "被拒绝"}")
        }

        // 首次进入自动要一次权限；用户拒绝后不反复弹，由界面上的"去授权"按钮触发
        LaunchedEffect(Unit) {
            if (!granted) requestPermission.launch(videoPermission)
        }

        // 从「系统设置 → 权限」手动打开后回到 App：重新检查一次，别让用户以为没生效
        val lifecycle = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        androidx.compose.runtime.DisposableEffect(lifecycle) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && !granted && hasVideoPermission()) {
                    granted = true
                    AppLog.i("Perm", "从系统设置返回，已获得读取视频权限")
                }
            }
            lifecycle?.addObserver(observer)
            onDispose { lifecycle?.removeObserver(observer) }
        }

        // 扫描进度：大目录要几秒，界面显示"已读取 x / y 个"，避免看起来卡死
        val scanProgress = remember { MutableStateFlow(0 to 0) }
        val progressPair by scanProgress.collectAsState()

        val scanState by produceState<ScanUi>(ScanUi.Loading, granted, refreshKey) {
            if (!granted) {
                value = ScanUi.NoPermission
                return@produceState
            }
            scanProgress.value = 0 to 0
            val started = System.currentTimeMillis()
            val list = withContext(Dispatchers.IO) {
                VideoScanner.scan(context) { done, total -> scanProgress.value = done to total }
            }
            // R8：清理已删除视频的播放进度记录
            PlaybackProgress.prune(context, list.map { it.id }.toSet())
            AppLog.i("Scan", "扫描完成：${list.size} 个视频，耗时 ${System.currentTimeMillis() - started}ms")
            value = ScanUi.Ready(list)
        }

        var selected by remember { mutableStateOf<VideoItem?>(null) }
        val current = selected
        // R6：列表页常驻组合树，播放页/日志页覆盖其上。
        // 这样 currentDir、搜索词与 LazyColumn 滚动位置在进出视频时天然保留。
        Box(modifier = Modifier.fillMaxSize()) {
            VideoListScreen(
                videos = (scanState as? ScanUi.Ready)?.videos ?: emptyList(),
                onSelect = {
                    AppLog.i("Open", "播放 ${it.name}（${it.durationMs / 1000}s，${it.relativePath}）")
                    selected = it
                },
                onRefresh = {
                    AppLog.i("Scan", "手动重新扫描")
                    refreshKey++
                },
                onOpenLogs = { showLogs = true },
                onOpenSettings = { showSettings = true },
                hasPermission = scanState !is ScanUi.NoPermission,
                scanning = scanState is ScanUi.Loading,
                scannedCount = progressPair.first,
                scanTotal = progressPair.second,
                onRequestPermission = { requestPermission.launch(videoPermission) },
                onOpenAppSettings = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                paused = showLogs || showSettings || current != null,
            )
            if (showLogs) LogScreen(onBack = { showLogs = false })
            if (showSettings) SettingsScreen(onBack = { showSettings = false })
            if (current != null) {
                PlayerScreen(item = current, onBack = { selected = null })
            }
        }
    }

    companion object {
        /** 调试入口：adb shell am start -n com.magplay/.MainActivity --ez com.magplay.OPEN_LOGS true */
        const val EXTRA_OPEN_LOGS = "com.magplay.OPEN_LOGS"
    }
}
