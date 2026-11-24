package com.magplay.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.magplay.data.PowerGuard
import com.magplay.data.ThumbnailLoader
import com.magplay.data.VideoItem
import com.magplay.data.VideoRename
import com.magplay.subtitle.SubtitleGenerationService
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

enum class SortKey(val label: String) {
    NAME("名称"), DATE("时间"), SIZE("大小"), DURATION("时长"),
}

@Composable
fun VideoListScreen(
    videos: List<VideoItem>,
    onSelect: (VideoItem) -> Unit,
    onRefresh: () -> Unit,
    onOpenLogs: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var currentDir by remember { mutableStateOf("") }
    var sortKey by remember { mutableStateOf(SortKey.DATE) }
    var ascending by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    var selectedType by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<VideoItem?>(null) }
    var showGenSubtitleDialog by remember { mutableStateOf(false) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }
    // 非 null 表示弹了"锁屏继续"引导框，值是本次要启动的 force 标记
    var pendingGenForce by remember { mutableStateOf<Boolean?>(null) }
    val searching = query.isNotBlank()

    // 系统返回键/边缘右滑手势：先在子目录内逐级回退，到根目录才退出 App。
    // 这样边缘右滑不会被系统直接 finish，而是走这里回上级。
    BackHandler(enabled = currentDir.isNotEmpty() || searching) {
        if (searching) {
            query = ""
            selectedType = null
        } else {
            currentDir = currentDir.substringBeforeLast("/", "")
        }
    }

    val typeOptions = remember(videos) {
        videos.mapNotNull { video ->
            video.name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.lowercase()
        }.distinct().sorted()
    }

    val childDirs = remember(videos, currentDir, searching) {
        if (searching) {
            emptyList()
        } else {
            val prefix = if (currentDir.isEmpty()) "" else "$currentDir/"
            videos.mapNotNull { video ->
                val path = video.relativePath.trimEnd('/')
                val rest = if (prefix.isEmpty()) path else path.removePrefix(prefix)
                if (path.startsWith(prefix) && rest.contains('/')) rest.substringBefore('/') else null
            }.distinct().sorted()
        }
    }

    val shownVideos = remember(videos, currentDir, query, searching, selectedType) {
        val base = if (searching) {
            videos.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.relativePath.contains(query, ignoreCase = true)
            }
        } else {
            videos.filter { it.relativePath.trimEnd('/').substringBeforeLast("/", "") == currentDir }
        }
        if (selectedType == null) {
            base
        } else {
            base.filter { it.name.substringAfterLast('.', "").equals(selectedType, ignoreCase = true) }
        }
    }

    val sorted = remember(shownVideos, sortKey, ascending) {
        val comparator = when (sortKey) {
            SortKey.NAME -> compareBy<VideoItem> { it.name.lowercase() }
            SortKey.DATE -> compareBy { it.dateAddedSec }
            SortKey.SIZE -> compareBy { it.size }
            SortKey.DURATION -> compareBy { it.durationMs }
        }
        if (ascending) shownVideos.sortedWith(comparator) else shownVideos.sortedWith(comparator.reversed())
    }

    Box(
        modifier = Modifier.fillMaxSize().pointerInput(currentDir, searching) {
            if (currentDir.isEmpty() || searching) return@pointerInput
            var totalDx = 0f
            detectHorizontalDragGestures(
                onDragStart = { totalDx = 0f },
                onHorizontalDrag = { _, amount -> totalDx += amount },
                onDragEnd = {
                    if (totalDx > 140f) currentDir = currentDir.substringBeforeLast("/", "")
                },
                onDragCancel = { totalDx = 0f },
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

            // ---- 顶栏 ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                if (currentDir.isNotEmpty() && !searching) {
                    IconButton(onClick = { currentDir = currentDir.substringBeforeLast("/", "") }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上级")
                    }
                } else {
                    IconButton(onClick = { currentDir = ""; query = ""; selectedType = null }) {
                        Icon(Icons.Filled.Home, contentDescription = "根目录")
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    // 根目录不显示标题（用户要求去掉"我的视频"）；子目录显示目录名
                    if (currentDir.isNotEmpty()) {
                        Text(
                            text = currentDir.substringAfterLast("/"),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = currentDir,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = { showGenSubtitleDialog = true }) {
                    Icon(Icons.Filled.Subtitles, contentDescription = "生成离线字幕")
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "重新扫描")
                }
                IconButton(onClick = onOpenLogs) {
                    Icon(Icons.Filled.Article, contentDescription = "运行日志")
                }
            }

            // ---- 类型筛选 + 排序：合并到同一行，省下的那一行用于显示更多视频 ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            ) {
                val chipColors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                // 不再有「全部」选项：一个都不选就是全部；再点一下已选中的类型即可取消筛选
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    typeOptions.forEach { ext ->
                        FilterChip(
                            selected = selectedType == ext,
                            onClick = { selectedType = if (selectedType == ext) null else ext },
                            label = { Text(ext.uppercase()) },
                            colors = chipColors,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                // 排序：与类型 chip 同高、同风格的 AssistChip，方向箭头直接显示当前升降序
                Box {
                    AssistChip(
                        onClick = { sortExpanded = true },
                        label = { Text("排序：${sortKey.label}") },
                        trailingIcon = {
                            Icon(
                                if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                                contentDescription = "当前${if (ascending) "升序" else "降序"}，点击可改",
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                    DropdownMenu(
                        expanded = sortExpanded,
                        onDismissRequest = { sortExpanded = false },
                    ) {
                        SortKey.entries.forEach { key ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        key.label,
                                        color = if (key == sortKey) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                onClick = {
                                    if (sortKey == key) {
                                        ascending = !ascending
                                    } else {
                                        sortKey = key
                                        ascending = key in setOf(SortKey.DATE, SortKey.SIZE, SortKey.DURATION)
                                    }
                                    sortExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            // ---- 列表（右侧带滚动位置指示条）----
            Box(modifier = Modifier.fillMaxSize()) {
            val listState = rememberLazyListState()
            val itemCount = childDirs.size + sorted.size
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp, end = 12.dp, top = 4.dp,
                    bottom = 24.dp,
                ),
            ) {
                if (!searching) {
                    items(childDirs, key = { "dir:$it" }) { dir ->
                        DirCard(
                            name = dir,
                            onClick = {
                                currentDir = if (currentDir.isEmpty()) dir else "$currentDir/$dir"
                                query = ""
                            },
                        )
                    }
                }
                items(sorted, key = { it.id }) { video ->
                    VideoCard(
                        video = video,
                        onClick = { onSelect(video) },
                        onLongClick = { renameTarget = video },
                    )
                }
            }

            // 右侧滚动位置指示条：滚动时随 firstVisibleItemIndex 移动
            if (itemCount > 6) {
                val firstIndex = listState.firstVisibleItemIndex
                val scrollFrac = firstIndex.toFloat() / itemCount.coerceAtLeast(1)
                Canvas(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(16.dp)
                        .padding(vertical = 12.dp),
                ) {
                    val trackH = size.height
                    val thumbH = 44f
                    val cx = size.width / 2
                    val y = (trackH - thumbH) * scrollFrac.coerceIn(0f, 1f)
                    drawRoundRect(
                        color = Color.LightGray.copy(alpha = 0.45f),
                        topLeft = Offset(cx - 1f, 0f),
                        size = Size(2f, trackH),
                        cornerRadius = CornerRadius(1f),
                    )
                    drawRoundRect(
                        color = Color.Gray.copy(alpha = 0.85f),
                        topLeft = Offset(cx - 4f, y),
                        size = Size(8f, thumbH),
                        cornerRadius = CornerRadius(4f),
                    )
                }
            }
            }
        }
    }

    // ---- 重命名对话框 ----
    renameTarget?.let { target ->
        RenameDialog(
            video = target,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                val ok = VideoRename.rename(context, target, newName)
                Toast.makeText(
                    context,
                    if (ok) "已重命名为 $newName" else "重命名失败（系统限制或权限不足）",
                    Toast.LENGTH_SHORT,
                ).show()
                renameTarget = null
                if (ok) scope.launch { onRefresh() }
            },
        )
    }

    // ---- 生成离线字幕：两个入口（全量补齐 / 清空重做）----
    if (showGenSubtitleDialog) {
        AlertDialog(
            onDismissRequest = { showGenSubtitleDialog = false },
            title = { Text("生成离线字幕") },
            text = {
                Column {
                    Text(
                        "字幕在本机生成，不联网。请选择生成方式：",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(14.dp))
                    GenOptionCard(
                        title = "全量生成",
                        desc = "只为还没有可用字幕的视频生成，已有字幕跳过。日常补充用这个。",
                    ) {
                        showGenSubtitleDialog = false
                        if (PowerGuard.isIgnoringBatteryOptimizations(context)) {
                            SubtitleGenerationService.start(context, force = false)
                            Toast.makeText(context, "已开始后台生成（仅缺失部分）", Toast.LENGTH_SHORT).show()
                        } else {
                            pendingGenForce = false
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    GenOptionCard(
                        title = "重新生成",
                        desc = "先备份并清空全部字幕文件，再对每个视频重新生成。耗时最长，字幕不准/时间轴错乱时用。",
                    ) {
                        showGenSubtitleDialog = false
                        showRegenerateConfirm = true
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showGenSubtitleDialog = false }) { Text("取消") }
            },
        )
    }

    if (showRegenerateConfirm) {
        AlertDialog(
            onDismissRequest = { showRegenerateConfirm = false },
            title = { Text("重新生成全部字幕？") },
            text = {
                Text(
                    "将清空当前所有视频的字幕文件（旧文件会先备份到 App 私有目录 backup_srt），" +
                        "然后逐个重新生成。视频较多、较长时可能跑很久，可在通知栏随时取消。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRegenerateConfirm = false
                    if (PowerGuard.isIgnoringBatteryOptimizations(context)) {
                        SubtitleGenerationService.start(context, force = true)
                        Toast.makeText(context, "已开始重新生成全部字幕", Toast.LENGTH_SHORT).show()
                    } else {
                        pendingGenForce = true
                    }
                }) { Text("清空并重做") }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateConfirm = false }) { Text("取消") }
            },
        )
    }

    // ---- 锁屏继续跑：未加入电池优化白名单时先引导一次 ----
    pendingGenForce?.let { force ->
        AlertDialog(
            onDismissRequest = { pendingGenForce = null },
            title = { Text("让字幕在锁屏后继续生成") },
            text = {
                Column {
                    Text(
                        "字幕生成要连续占用 CPU 很久，系统默认会在锁屏后休眠，导致任务停住。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "把 MagPlay 加入「电池优化白名单」后，锁屏也能继续跑。小米手机还建议在" +
                            "「应用信息 → 省电策略」里选「无限制」，并允许「自启动」。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "已加入白名单的话可以跳过，直接开始。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingGenForce = null
                    PowerGuard.requestIgnoreBatteryOptimizations(context)
                    SubtitleGenerationService.start(context, force = force)
                    Toast.makeText(context, "已开始后台生成", Toast.LENGTH_SHORT).show()
                }) { Text("去设置并开始") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingGenForce = null
                    SubtitleGenerationService.start(context, force = force)
                    Toast.makeText(context, "已开始后台生成", Toast.LENGTH_SHORT).show()
                }) { Text("直接开始") }
            },
        )
    }
}

/** 生成方式选项卡片。 */
@Composable
private fun GenOptionCard(title: String, desc: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DirCard(name: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(FileTint.FOLDER_BG),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, tint = FileTint.FOLDER_FG, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun VideoCard(
    video: VideoItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    var thumb by remember(video.id) { mutableStateOf<ImageBitmap?>(ThumbnailLoader.cached(video.id)) }
    androidx.compose.runtime.LaunchedEffect(video.id) {
        if (thumb == null && ThumbnailLoader.load(context.contentResolver, video)) {
            thumb = ThumbnailLoader.cached(video.id)
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() },
                )
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(FileTint.VIDEO_BG),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = thumb
                if (bitmap != null) {
                    Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Filled.Movie, contentDescription = null, tint = FileTint.VIDEO_FG, modifier = Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${formatSize(video.size)}  ·  ${formatDuration(video.durationMs)}  ·  ${video.relativePath}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(
    video: VideoItem,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // 默认只选中/填写主文件名，后缀单独可编辑
    val dot = video.name.lastIndexOf('.')
    val initialBase = if (dot > 0) video.name.substring(0, dot) else video.name
    val initialExt = if (dot > 0) video.name.substring(dot + 1) else ""
    var base by remember { mutableStateOf(initialBase) }
    var ext by remember { mutableStateOf(initialExt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名视频") },
        text = {
            Column {
                OutlinedTextField(
                    value = base,
                    onValueChange = { base = it },
                    label = { Text("文件名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ext,
                    onValueChange = { ext = it },
                    label = { Text("后缀（可修改）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val newName = buildString {
                    append(base.trim())
                    if (ext.isNotBlank()) append('.').append(ext.trim().trimStart('.'))
                }
                onConfirm(newName)
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
    else -> String.format("%.0f KB", bytes / 1024.0)
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    return String.format("%02d:%02d:%02d", totalSec / 3600, totalSec % 3600 / 60, totalSec % 60)
}
