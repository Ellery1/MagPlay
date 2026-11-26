package com.magplay

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import com.magplay.data.PlaybackProgress
import com.magplay.data.VideoItem
import com.magplay.data.VideoScanner
import com.magplay.player.PlayerScreen
import com.magplay.ui.LogScreen
import com.magplay.ui.MagPlayTheme
import com.magplay.ui.VideoListScreen

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

    @Composable
    private fun MagPlayApp() {
        val context = LocalContext.current
        var granted by remember { mutableStateOf(hasVideoPermission()) }
        var refreshKey by remember { mutableIntStateOf(0) }
        var showLogs by remember { mutableStateOf(openLogsRequested()) }
        val requestPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            granted = isGranted
            AppLog.i("Perm", "读取视频权限：${if (isGranted) "已授予" else "被拒绝"}")
        }

        LaunchedEffect(Unit) {
            if (!granted) requestPermission.launch(videoPermission)
        }

        val videos by produceState(
            initialValue = emptyList<VideoItem>(),
            granted,
            refreshKey,
        ) {
            value = if (!granted) {
                emptyList()
            } else {
                val started = System.currentTimeMillis()
                val list = VideoScanner.scan(context)
                // R8：清理已删除视频的播放进度记录
                PlaybackProgress.prune(context, list.map { it.id }.toSet())
                AppLog.i("Scan", "扫描完成：${list.size} 个视频，耗时 ${System.currentTimeMillis() - started}ms")
                list
            }
        }

        var selected by remember { mutableStateOf<VideoItem?>(null) }
        val current = selected
        // R6：列表页常驻组合树，播放页/日志页覆盖其上。
        // 这样 currentDir、搜索词与 LazyColumn 滚动位置在进出视频时天然保留。
        Box(modifier = Modifier.fillMaxSize()) {
            VideoListScreen(
                videos = videos,
                onSelect = {
                    AppLog.i("Open", "播放 ${it.name}（${it.durationMs / 1000}s，${it.relativePath}）")
                    selected = it
                },
                onRefresh = {
                    AppLog.i("Scan", "手动重新扫描")
                    refreshKey++
                },
                onOpenLogs = { showLogs = true },
                paused = showLogs || current != null,
            )
            if (showLogs) LogScreen(onBack = { showLogs = false })
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
