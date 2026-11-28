package com.magplay.ui

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.magplay.data.AppLog
import com.magplay.data.PowerGuard
import com.magplay.subtitle.GenStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志页：把排错能力做进 App，不依赖 adb。
 * 关键动作：刷新（重读快照）、导出分享（txt 可直接微信发给开发者）、清空。
 */
@Composable
fun LogScreen(onBack: () -> Unit) {
    // 系统返回键要回上一级，不能直接退出 App——其他页面都是这个行为，只有这里退出极易误触
    androidx.activity.compose.BackHandler(onBack = onBack)
    val context = LocalContext.current
    var seq by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    LaunchedEffect(seq) {
        // 读磁盘文件是 IO，别放主线程（切换页面时可能卡一帧）
        text = withContext(Dispatchers.IO) { AppLog.snapshot() }.ifBlank { "（暂无日志）" }
        scroll.scrollTo(scroll.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("运行日志", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "共 ${text.count { it == '\n' } + 1} 行 · 导出后可直接用微信发出去",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { seq++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
            IconButton(onClick = { shareLog(context) }) {
                Icon(Icons.Filled.Share, contentDescription = "导出分享")
            }
            IconButton(onClick = { confirmClear = true }) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = "清空日志")
            }
        }

        SelfCheckCard(context)

        SelectionContainer(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                text = text,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空日志") },
            text = { Text("将清空当前内存缓冲和磁盘历史日志，不可恢复。建议先导出再清空。") },
            confirmButton = {
                TextButton(onClick = {
                    AppLog.clear()
                    confirmClear = false
                    seq++
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

/**
 * 后台自检卡片：把「锁屏后还能不能继续跑」这件事变成一眼可见的三个指标。
 * 数据来源两条：本进程直接问系统（电池优化豁免），跨进程读 status.json（服务/唤醒锁/心跳）。
 */
@Composable
private fun SelfCheckCard(context: android.content.Context) {
    // 1s 重算一次，用来显示"最近心跳 x 秒前"这种会走的值
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
    val snap = remember(tick) { GenStatus.read(context) }
    val exempt = remember(tick) { PowerGuard.isIgnoringBatteryOptimizations(context) }
    // status.json 会在进程被强杀时残留 running=true（最多 2 分钟才按心跳判死）。
    // 运行锁是内核级的，进程一死就释放，用它做最终裁决，结论立即准确。
    val locked = remember(tick) { GenStatus.isRunLocked(context) }

    val dark = isSystemInDarkTheme()
    val okColor = if (dark) Color(0xFF5EE0B8) else Color(0xFF0CA678)
    val warnColor = if (dark) Color(0xFFFFC65C) else Color(0xFFC77700)
    val badColor = if (dark) Color(0xFFFF8A80) else Color(0xFFD64545)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    val ageSec = (snap?.ageMs ?: Long.MAX_VALUE).coerceAtLeast(0L) / 1000L
    val running = snap?.running == true && locked
    val stale = running && ageSec >= 180

    // 一句话结论：普通用户只想知道"锁屏后还能不能继续跑"，
    // 不需要看懂"前台服务 / 唤醒锁 / 心跳"这些词。
    val (headline, headColor) = when {
        stale -> "生成好像卡住了：${formatAge(ageSec)}没有响应" to badColor
        running && !exempt -> "正在生成，但锁屏后可能被系统停掉" to warnColor
        running && ageSec < 90 -> "正在生成，锁屏后可以继续" to okColor
        running -> "正在生成，最近响应偏慢" to warnColor
        snap == null -> "还没有生成过字幕" to muted
        snap.running -> "上次生成已中断（App 被关闭或覆盖安装）" to warnColor
        else -> "当前没有生成任务" to muted
    }

    var showDetail by remember { mutableStateOf(false) }

    val (svcText, svcColor) = when {
        snap == null -> "无记录" to muted
        running && ageSec < 120 -> "运行中" to okColor
        running -> "疑似被冻结：${ageSec}s 无心跳" to badColor
        snap.running -> "已中断（进程已退出）" to warnColor
        else -> "空闲" to muted
    }
    val (beatText, beatColor) = when {
        snap == null -> "—" to muted
        !running -> "已结束 ${formatAge(ageSec)}" to muted
        ageSec < 90 -> "${ageSec}s 前（正常）" to okColor
        ageSec < 180 -> "${ageSec}s 前（偏慢）" to warnColor
        else -> "${ageSec}s 前（停滞）" to badColor
    }
    val (lockText, lockColor) = when {
        snap == null -> "—" to muted
        snap.wakeLock && running -> "已持有（锁屏可续跑）" to okColor
        running -> "未持有（锁屏会停）" to badColor
        snap.wakeLock -> "已释放" to muted
        else -> "未持有" to muted
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(headColor, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    headline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = headColor,
                    modifier = Modifier.weight(1f),
                )
                if (!exempt) {
                    TextButton(
                        onClick = { PowerGuard.requestIgnoreBatteryOptimizations(context) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp,
                            vertical = 0.dp,
                        ),
                    ) {
                        Text("去允许", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (!snap?.text.isNullOrBlank()) {
                Text(
                    text = "当前：${snap?.text}",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = { showDetail = !showDetail },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text(
                    if (showDetail) "收起详情" else "查看详情",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (showDetail) {
                StatusRow(
                    label = "电池优化",
                    value = if (exempt) "已豁免" else "未豁免（锁屏可能被限制）",
                    color = if (exempt) okColor else badColor,
                )
                StatusRow(label = "前台服务", value = svcText, color = svcColor)
                StatusRow(label = "唤醒锁", value = lockText, color = lockColor)
                StatusRow(label = "最近心跳", value = beatText, color = beatColor)
                if (isXiaomiLike()) {
                    Text(
                        text = "小米/华为还需：设置 → 应用管理 → MagPlay → 省电策略=无限制 + 自启动。" +
                            "覆盖安装会保留，卸载重装需重做。",
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatAge(sec: Long): String = when {
    sec < 60 -> "${sec}s 前"
    sec < 3600 -> "${sec / 60} 分钟前"
    else -> "${sec / 3600} 小时前"
}

private fun isXiaomiLike(): Boolean {
    val m = Build.MANUFACTURER.lowercase(Locale.US)
    val b = Build.BRAND.lowercase(Locale.US)
    return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ||
        b.contains("xiaomi") || b.contains("redmi") || b.contains("poco") ||
        m.contains("huawei") || m.contains("honor")
}

/** 把完整日志写到 cache/share 下，用系统分享面板发出去（微信 / 邮件 / 文件传输助手）。 */
private fun shareLog(context: android.content.Context) {
    try {
        val stamp = SimpleDateFormat("MMdd-HHmm", Locale.US).format(Date())
        val dir = File(context.cacheDir, "share")
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, "MagPlay-log-$stamp.txt")
        out.writeText(AppLog.exportText())
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            out,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "MagPlay 运行日志 $stamp")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出运行日志"))
    } catch (t: Throwable) {
        AppLog.e("LogScreen", "分享日志失败", t)
    }
}
