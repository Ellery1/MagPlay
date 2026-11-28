package com.magplay.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.magplay.data.AppLog
import com.magplay.data.DirOpResult
import com.magplay.data.FileOpResult
import com.magplay.asr.AsrModels
import com.magplay.data.NetGuard
import com.magplay.data.PowerGuard
import com.magplay.data.ThumbnailLoader
import com.magplay.data.VideoFiles
import com.magplay.data.VideoItem
import com.magplay.data.VideoRename
import com.magplay.data.VideoScanner
import com.magplay.subtitle.GenAttempts
import com.magplay.subtitle.AsrSettings
import com.magplay.subtitle.GenStatus
import com.magplay.subtitle.ModelDownloader
import com.magplay.subtitle.SubtitleGenerationService
import com.magplay.subtitle.SubtitleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.roundToInt

enum class SortKey(val label: String) {
    NAME("名称"), DATE("时间"), SIZE("大小"), DURATION("时长"),
}

@Composable
fun VideoListScreen(
    videos: List<VideoItem>,
    onSelect: (VideoItem) -> Unit,
    onRefresh: () -> Unit,
    onOpenLogs: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /** R6：播放页/日志页覆盖在本页之上时，禁用返回键拦截与手势，避免抢事件。 */
    paused: Boolean = false,
    /** 是否已拿到读取视频权限。false 时第一屏显示"去授权"，而不是一片空白。 */
    hasPermission: Boolean = true,
    /** 正在扫描 MediaStore。true 时显示"正在读取视频…"，避免看着像卡死。 */
    scanning: Boolean = false,
    scannedCount: Int = 0,
    scanTotal: Int = 0,
    onRequestPermission: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var currentDir by remember { mutableStateOf("") }
    // R3：每个目录记住自己的滚动位置。切走再回来时回到离开时的位置，而不是顶部。
    val dirScrollStates = remember { mutableMapOf<String, LazyListState>() }
    var sortKey by remember { mutableStateOf(SortKey.DATE) }
    var ascending by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    var selectedType by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<VideoItem?>(null) }
    var showGenSubtitleDialog by remember { mutableStateOf(false) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }
    // R4：模型下载 / 未安装引导
    var showModelDialog by remember { mutableStateOf(false) }
    var installedVersion by remember { mutableIntStateOf(if (ModelDownloader.isInstalled(context)) 1 else 0) }
    val modelReady = installedVersion > 0
    val dlState by ModelDownloader.state.collectAsState()
    LaunchedEffect(dlState) {
        if (dlState is ModelDownloader.State.Installed && ModelDownloader.isInstalled(context)) {
            installedVersion = 1
        }
    }
    // 非 null 表示弹了"锁屏继续"引导框，值是本次要启动的 force 标记
    var pendingGenForce by remember { mutableStateOf<Boolean?>(null) }

    // ---- 字幕任务常驻状态 ----
    // 通知可能被系统折叠、被用户划掉，甚至被安装/强杀打断后什么都不剩，
    // 首页自己留一条：运行中显示进度，被打断时直接给「继续生成」。
    var genSnap by remember { mutableStateOf<GenStatus.Snapshot?>(null) }
    var genBusy by remember { mutableStateOf(false) }
    var genBannerHidden by remember { mutableStateOf(false) }
    LaunchedEffect(paused) {
        while (!paused) {
            genBusy = GenStatus.isRunLocked(context)
            genSnap = GenStatus.read(context)
            delay(1500L)
        }
    }
    // 从"电池优化白名单设置页"返回后要启动的批次（先确认授权结果，再启动）
    var pendingBatteryForce by remember { mutableStateOf<Boolean?>(null) }
    // 通知权限被拒后，引导用户去系统设置打开
    var notifyDeniedDialog by remember { mutableStateOf(false) }
    // 顶栏「更多」菜单
    var showMoreMenu by remember { mutableStateOf(false) }
    // 移动网络下下载模型前先确认一次
    var confirmMobileDownload by remember { mutableStateOf(false) }
    // 系统通知权限弹窗回来后要启动的批次 / 单视频
    var pendingNotifyForce by remember { mutableStateOf<Boolean?>(null) }
    var pendingSingleId by remember { mutableStateOf(-1L) }
    // 目录重命名后按「可见项」恢复滚动位置（目录本身不变，见 doDirRename）
    var pendingScrollRestore by remember { mutableStateOf<ScrollRestore?>(null) }
    val searching = query.isNotBlank()

    // ---- 长按拖拽移动 ----
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    // 触发"回上一级"的顶部热区。原来是 72dp——正好盖住第一个文件夹卡片，
    // 想拖到第一个文件夹时经常被误判成回上级。收到 24dp（贴着列表最顶端），
    // 再加 0.7s 停留确认，才真正改目录。
    val topZonePx = with(density) { 24.dp.toPx() }
    /** 按住视频往右划多远算"呼出菜单"。32dp 大约 4mm，比长按快得多，也不容易误触。 */
    val menuSwipePx = with(density) { 32.dp.toPx() }
    var dragVideo by remember { mutableStateOf<VideoItem?>(null) }
    var dragMoved by remember { mutableStateOf(false) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }   // 窗口坐标
    var hoverDir by remember { mutableStateOf<String?>(null) }
    var jumpHint by remember { mutableStateOf(false) }
    var topArmed by remember { mutableStateOf(true) }
    var topJob by remember { mutableStateOf<Job?>(null) }
    /** 本次拖拽是否已经"吸附"回过上一级：松手时若没落到文件夹卡片上，就默认移入该目录。 */
    var jumpedUp by remember { mutableStateOf(false) }
    val dirRects = remember { mutableMapOf<String, Rect>() }
    /** 视频行在窗口中的位置，按键 id 索引；全屏手势层靠它判断按到了哪一项。 */
    val videoRects = remember { mutableMapOf<Long, Rect>() }
    val videosState = rememberUpdatedState(videos)
    var listTopInWindow by remember { mutableStateOf(0f) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }

    // ---- 长按菜单 / 删除 / 系统授权 ----
    var actionTarget by remember { mutableStateOf<VideoItem?>(null) }
    var deleteTarget by remember { mutableStateOf<VideoItem?>(null) }
    var pendingOp by remember { mutableStateOf<PendingFileOp?>(null) }
    var genPlanText by remember { mutableStateOf<String?>(null) }
    // R1：文件夹菜单 / 重命名 / 删除
    var dirActionTarget by remember { mutableStateOf<String?>(null) }
    var dirRenameTarget by remember { mutableStateOf<String?>(null) }
    var dirRenameValue by remember { mutableStateOf("") }
    var dirDeleteTarget by remember { mutableStateOf<String?>(null) }
    var pendingDirOp by remember { mutableStateOf<PendingDirOp?>(null) }

    // 系统授权弹窗（"允许 MagPlay 修改/删除此文件？"）回来后重试一次
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { res ->
        val dirOp = pendingDirOp
        if (dirOp != null) {
            pendingDirOp = null
            if (res.resultCode == Activity.RESULT_OK) {
                val result = when (dirOp) {
                    is PendingDirOp.Rename ->
                        VideoFiles.renameDir(context, videosState.value, dirOp.dir, dirOp.newName)
                    is PendingDirOp.Delete ->
                        VideoFiles.deleteDir(context, videosState.value, dirOp.dir)
                }
                when (result) {
                    is DirOpResult.Ok -> {
                        Toast.makeText(
                            context,
                            if (dirOp is PendingDirOp.Rename) "已重命名 ${result.count} 个视频"
                            else "已删除 ${result.count} 个视频及字幕",
                            Toast.LENGTH_SHORT,
                        ).show()
                        // 重命名必须留在原目录：被改的是 currentDir 的子目录，
                        // 退到父目录是 off-by-one，还会连滚动位置一起丢。
                        if (dirOp is PendingDirOp.Delete) {
                            currentDir = currentDir.substringBeforeLast("/", "")
                        }
                        scope.launch { onRefresh() }
                    }
                    is DirOpResult.NeedPermission ->
                        Toast.makeText(context, "系统仍未放行这些文件", Toast.LENGTH_SHORT).show()
                    is DirOpResult.Failed ->
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(context, "未授权，操作已取消", Toast.LENGTH_SHORT).show()
            }
            return@rememberLauncherForActivityResult
        }
        val op = pendingOp
        pendingOp = null
        if (op != null) {
            if (res.resultCode != Activity.RESULT_OK) {
                Toast.makeText(context, "未授权，操作已取消", Toast.LENGTH_SHORT).show()
            } else {
                val retry = if (op.targetDir == null) {
                    VideoFiles.delete(context, op.video)
                } else {
                    VideoFiles.move(context, op.video, op.targetDir)
                }
                when (retry) {
                    is FileOpResult.Ok -> {
                        Toast.makeText(context, retry.message, Toast.LENGTH_SHORT).show()
                        scope.launch { onRefresh() }
                    }
                    is FileOpResult.Failed -> {
                        Toast.makeText(context, retry.message, Toast.LENGTH_SHORT).show()
                    }
                    is FileOpResult.NeedPermission -> {
                        Toast.makeText(context, "系统仍未放行该文件", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /** 视频是别的 App 建的，先拉起系统授权弹窗，同意后自动重试。 */
    fun requestPermission(video: VideoItem, targetDir: String?, uris: List<android.net.Uri>) {
        pendingOp = PendingFileOp(video, targetDir)
        val sender = runCatching {
            val resolver = context.contentResolver
            if (targetDir == null) {
                android.provider.MediaStore.createDeleteRequest(resolver, uris).intentSender
            } else {
                android.provider.MediaStore.createWriteRequest(resolver, uris).intentSender
            }
        }.getOrNull()
        if (sender == null) {
            pendingOp = null
            Toast.makeText(context, "当前系统不支持逐次授权，请授予「所有文件访问权限」", Toast.LENGTH_LONG).show()
        } else {
            permLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    fun doMove(video: VideoItem, targetDir: String) {
        if (video.relativePath.trimEnd('/') == targetDir.trimEnd('/')) {
            // 拖到"它已经所在的目录"是无效操作，但不能静默返回：根目录会把顶层目录里的
            // 视频也列出来（见 shownVideos 的父级判断），很容易拖了个寂寞还以为手势坏了。
            Toast.makeText(
                context,
                "它已经在 ${targetDir.trimEnd('/')} 里了，不需要移动",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        when (val r = VideoFiles.move(context, video, targetDir)) {
            is FileOpResult.Ok -> {
                Toast.makeText(context, r.message, Toast.LENGTH_SHORT).show()
                scope.launch { onRefresh() }
            }
            is FileOpResult.NeedPermission -> requestPermission(video, targetDir, r.uris)
            is FileOpResult.Failed -> Toast.makeText(context, r.message, Toast.LENGTH_SHORT).show()
        }
    }

    fun doDelete(video: VideoItem) {
        when (val r = VideoFiles.delete(context, video)) {
            is FileOpResult.Ok -> {
                Toast.makeText(context, r.message, Toast.LENGTH_SHORT).show()
                scope.launch { onRefresh() }
            }
            is FileOpResult.NeedPermission -> requestPermission(video, null, r.uris)
            is FileOpResult.Failed -> Toast.makeText(context, r.message, Toast.LENGTH_SHORT).show()
        }
    }

    /** R4：所有生成入口的统一守卫 —— 模型未装时引导下载，不启动生成。 */
    fun requireModel(onReady: () -> Unit) {
        when {
            ModelDownloader.isInstalled(context) -> onReady()
            dlState is ModelDownloader.State.Downloading ->
                Toast.makeText(context, "模型正在下载，完成后再试", Toast.LENGTH_SHORT).show()
            else -> showModelDialog = true
        }
    }

    /**
     * 服务跑在 :subtitle 进程，主进程的静态字段永远是 false，不能用。
     * 也不能用 status.json 的时间戳：进程被强杀时 running 会残留，得等 3 分钟才失效，
     * 这期间用户重开 App 点生成会被误拦。
     * 改用内核文件锁——服务持锁期间为真，进程死亡 OS 立即释放，零等待。
     */
    fun isGenBusy(): Boolean = GenStatus.isRunLocked(context)

    val requestNotifyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // 没有通知权限，进度就只能靠 UI 显示：先明确告诉用户，并给出"去开启"入口
        if (!granted) notifyDeniedDialog = true
        val force = pendingNotifyForce
        if (force != null) {
            pendingNotifyForce = null
            if (isGenBusy()) {
                Toast.makeText(context, "已有字幕任务在跑，进度见通知栏", Toast.LENGTH_SHORT).show()
            } else {
                SubtitleGenerationService.start(context, force = force)
                Toast.makeText(
                    context,
                    if (granted) "已开始后台生成，进度见通知栏"
                    else "已开始后台生成；通知栏看不到进度，因为通知权限没开",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        } else {
            val id = pendingSingleId
            pendingSingleId = -1L
            if (id > 0L) {
                if (isGenBusy()) {
                    Toast.makeText(context, "已有字幕任务在跑，进度见通知栏", Toast.LENGTH_SHORT).show()
                } else {
                    SubtitleGenerationService.startSingle(context, id)
                    Toast.makeText(
                        context,
                        if (granted) "已开始生成字幕，进度见通知栏"
                        else "已开始生成字幕；通知栏看不到进度，因为通知权限没开",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    fun startGeneration(force: Boolean) {
        if (isGenBusy()) {
            Toast.makeText(context, "已有字幕任务在跑，进度见通知栏", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotifyForce = force
            requestNotifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        SubtitleGenerationService.start(context, force = force)
        Toast.makeText(
            context,
            if (force) "已开始重新生成全部字幕，进度见通知栏"
            else "已开始补齐缺失字幕，进度见通知栏",
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun startGenSingle(id: Long) {
        if (isGenBusy()) {
            Toast.makeText(context, "已有字幕任务在跑，进度见通知栏", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSingleId = id
            requestNotifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        SubtitleGenerationService.startSingle(context, id)
        Toast.makeText(context, "已开始生成字幕，进度见通知栏", Toast.LENGTH_SHORT).show()
    }

    /**
     * 从「电池优化」设置页返回时先看授权结果，再决定要不要启动。
     * 旧逻辑是先 start 再拉设置页：文案承诺"锁屏后继续"，实际启动那一刻根本没这个权限，
     * 承诺兑现不了，任务照样会在锁屏后停住。
     */
    val activityLifecycle = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycle
    androidx.compose.runtime.DisposableEffect(activityLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val force = pendingBatteryForce ?: return@LifecycleEventObserver
                pendingBatteryForce = null
                if (PowerGuard.isIgnoringBatteryOptimizations(context)) {
                    AppLog.i("Gen", "电池优化已豁免，启动字幕任务 force=$force")
                    startGeneration(force)
                } else {
                    // 没批下来就再问一次：可以继续去设置，也可以选择直接开始（会提示锁屏可能停）
                    pendingGenForce = force
                }
            }
        }
        activityLifecycle?.addObserver(observer)
        onDispose { activityLifecycle?.removeObserver(observer) }
    }

    fun launchDirPermission(uris: List<android.net.Uri>) {
        val sender = runCatching {
            val resolver = context.contentResolver
            // 重命名要写、删除要删；统一用 write 请求，多数情形可覆盖，
            // 真遇到 delete 被拒时系统会在重试时再提示。
            android.provider.MediaStore.createWriteRequest(resolver, uris).intentSender
        }.getOrNull()
        if (sender == null) {
            pendingDirOp = null
            Toast.makeText(context, "当前系统不支持逐次授权，请授予「所有文件访问权限」", Toast.LENGTH_LONG).show()
        } else {
            permLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    /** 拖动中：更新位置、顶部吸附回上一级、命中文件夹高亮。 */
    fun onDragPosition(p: Offset) {
        dragPos = p
        val inTop = p.y < listTopInWindow + topZonePx
        if (inTop != jumpHint) jumpHint = inTop
        if (inTop) {
            hoverDir = null
            // 停留确认：手指在顶部热区里停住 0.7s 才回上一级。
            // 只是路过顶部（去够第一个文件夹卡片）不会触发。
            if (topArmed && topJob == null && currentDir.isNotEmpty() && query.isBlank()) {
                topJob = scope.launch {
                    delay(TOP_DWELL_MS)
                    topJob = null
                    // 到点了还要确认手指仍在顶部、且拖拽没有结束
                    if (dragVideo != null && dragPos.y < listTopInWindow + topZonePx &&
                        currentDir.isNotEmpty()
                    ) {
                        topArmed = false
                        jumpedUp = true
                        dirRects.clear()
                        hoverDir = null
                        currentDir = currentDir.substringBeforeLast("/", "")
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
            }
        } else {
            topJob?.cancel()
            topJob = null
            if (!topArmed) topArmed = true
            hoverDir = dirRects.entries.firstOrNull { it.value.contains(p) }?.key
        }
    }

    fun resetDrag() {
        topJob?.cancel()
        topJob = null
        dragVideo = null
        dragMoved = false
        hoverDir = null
        jumpHint = false
        topArmed = true
        jumpedUp = false
    }

    /**
     * 松手：落在文件夹上就移进去；已经吸附回过上一级、又没落到任何卡片上，就移进上一级目录。
     *
     * 后半条是关键：拖到顶部回上级后，列表内容整个换了一套，用户往往直接松手（或落在视频行/空白处），
     * 旧逻辑此时 target 为 null，静默什么都不做——表现就是"回到上级了但文件没动"。
     */
    fun finishDrag() {
        val v = dragVideo
        val moved = dragMoved
        val hit = hoverDir
        val jumped = jumpedUp
        resetDrag()
        if (v == null || !moved) return
        val target = when {
            hit != null && query.isBlank() ->
                if (currentDir.isEmpty()) "$hit/" else "$currentDir/$hit/"
            jumped && currentDir.isEmpty() -> {
                Toast.makeText(context, "已回到根目录，请拖到某个文件夹上再松手", Toast.LENGTH_SHORT).show()
                null
            }
            jumped -> "$currentDir/"
            else -> null
        } ?: return
        doMove(v, target)
    }

    // 打开生成对话框时统计一次：待生成多少、有多少已判定无对白被跳过
    LaunchedEffect(showGenSubtitleDialog) {
        if (!showGenSubtitleDialog) {
            genPlanText = null
            return@LaunchedEffect
        }
        genPlanText = "正在统计…"
        genPlanText = withContext(Dispatchers.IO) {
            val all = VideoScanner.scan(context)
            val curEngine = AsrSettings.backend(context)
            var todo = 0
            var stale = 0
            var skipped = 0
            all.forEach { v ->
                if (SubtitleStore.hasUsableForEngine(context, v, curEngine.id)) return@forEach
                if (SubtitleStore.hasUsable(context, v)) {
                    // 有字幕，但是旧引擎生成的
                    stale++
                    return@forEach
                }
                if (GenAttempts.shouldSkip(context, v) != null) skipped++ else todo++
            }
            buildString {
                append("待生成 ${todo + stale} 个")
                if (stale > 0) append("（其中 $stale 个是旧引擎字幕，需用 ${curEngine.label} 重做）")
                if (skipped > 0) append("；另有 $skipped 个已判定无对白，已跳过")
            }.also { AppLog.i("GenPlan", it) }
        }
    }

    // 系统返回键/边缘右滑手势：先在子目录内逐级回退，到根目录才退出 App。
    // 这样边缘右滑不会被系统直接 finish，而是走这里回上级。
    BackHandler(enabled = !paused && (currentDir.isNotEmpty() || searching)) {
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

    // 子目录 = 当前目录下所有视频路径的下一级目录。
    // 注意：不能再要求「rest 里必须还有 /」——否则像 Pictures/ 这种直接躺着视频、
    // 下面没建子目录的情况，Pictures 文件夹卡片本身就不会出现在根目录里。
    val childDirs = remember(videos, currentDir, searching) {
        if (searching) {
            emptyList()
        } else {
            val prefix = if (currentDir.isEmpty()) "" else "$currentDir/"
            videos.mapNotNull { video ->
                val dir = video.relativePath.trimEnd('/')
                if (dir.length <= prefix.length || !dir.startsWith(prefix)) return@mapNotNull null
                dir.substring(prefix.length).substringBefore('/').ifEmpty { null }
            }.distinct().sorted()
        }
    }

    /** 每个子目录下的视频数（文件夹卡片上的"共 N 个"）。预先算一遍，别在 item 里反复遍历全表。 */
    val dirVideoCounts = remember(videos, currentDir, searching) {
        if (searching) {
            emptyMap()
        } else {
            val prefix = if (currentDir.isEmpty()) "" else "$currentDir/"
            val map = mutableMapOf<String, Int>()
            videos.forEach { v ->
                val dir = v.relativePath.trimEnd('/')
                if (dir.length > prefix.length && dir.startsWith(prefix)) {
                    val leaf = dir.substring(prefix.length).substringBefore('/')
                    if (leaf.isNotEmpty()) map[leaf] = (map[leaf] ?: 0) + 1
                }
            }
            map
        }
    }

    val shownVideos = remember(videos, currentDir, query, searching, selectedType) {
        val base = if (searching) {
            videos.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.relativePath.contains(query, ignoreCase = true)
            }
        } else {
            // 只显示「视频本身就放在这个目录里」的那些。
            // 旧写法是 substringBeforeLast("/")，会把 Download/、Pictures/ 这类一级目录里的视频
            // 也算到根目录名下（父级求出来都是 ""），于是根目录能看到一堆其实在子目录里的视频，
            // 移走之后"看起来没动"——这正是用户报的「主列表还能看到它」。
            videos.filter { it.relativePath.trimEnd('/') == currentDir }
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

    /**
     * 快照列表第一个可见项。重命名后文件夹卡片可能因为新名字的字母序换到另一行，
     * 只保留 index 会让用户看到的内容跑掉；按 key 复位才能保证"原位置"。
     */
    fun snapshotScroll(renamedLeaf: String? = null, newName: String? = null): ScrollRestore {
        val st = dirScrollStates[currentDir]
        val idx = st?.firstVisibleItemIndex ?: 0
        val off = st?.firstVisibleItemScrollOffset ?: 0
        val firstKey = if (idx < childDirs.size) {
            val leaf = childDirs.getOrNull(idx)
            when {
                leaf == null -> null
                renamedLeaf != null && leaf == renamedLeaf && newName != null -> "dir:$newName"
                else -> "dir:$leaf"
            }
        } else {
            sorted.getOrNull(idx - childDirs.size)?.id?.toString()
        }
        return ScrollRestore(currentDir, firstKey, idx, off)
    }

    LaunchedEffect(videos) {
        val restore = pendingScrollRestore ?: return@LaunchedEffect
        pendingScrollRestore = null
        if (restore.dir != currentDir) return@LaunchedEffect
        val st = dirScrollStates.getOrPut(restore.dir) { LazyListState() }
        val target = restore.firstKey?.let { key ->
            if (key.startsWith("dir:")) {
                childDirs.indexOf(key.removePrefix("dir:"))
            } else {
                val i = sorted.indexOfFirst { it.id.toString() == key }
                if (i >= 0) i + childDirs.size else -1
            }
        }?.takeIf { it >= 0 } ?: restore.index
        val max = (childDirs.size + sorted.size - 1).coerceAtLeast(0)
        if (target != st.firstVisibleItemIndex ||
            restore.offset != st.firstVisibleItemScrollOffset
        ) {
            st.scrollToItem(target.coerceAtMost(max), restore.offset)
        }
    }

    /** R1：目录批量重命名 / 删除。授权不足时走 pendingDirOp，授权回来后重试。 */
    fun doDirRename(dir: String, newName: String) {
        when (val r = VideoFiles.renameDir(context, videosState.value, dir, newName)) {
            is DirOpResult.Ok -> {
                // 关键修复：不改 currentDir。被重命名的 dir 是 currentDir 的子目录，
                // 之前 substringBeforeLast 让它退回父目录，还顺手换了 LazyListState 的 key。
                pendingScrollRestore = snapshotScroll(dir.substringAfterLast("/"), newName)
                Toast.makeText(context, "已重命名 ${r.count} 个视频", Toast.LENGTH_SHORT).show()
                scope.launch { onRefresh() }
            }
            is DirOpResult.NeedPermission -> {
                pendingDirOp = PendingDirOp.Rename(dir, newName)
                launchDirPermission(r.uris)
            }
            is DirOpResult.Failed -> Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
        }
    }

    fun doDirDelete(dir: String) {
        when (val r = VideoFiles.deleteDir(context, videosState.value, dir)) {
            is DirOpResult.Ok -> {
                Toast.makeText(context, "已删除 ${r.count} 个视频及字幕", Toast.LENGTH_SHORT).show()
                currentDir = currentDir.substringBeforeLast("/", "")
                scope.launch { onRefresh() }
            }
            is DirOpResult.NeedPermission -> {
                pendingDirOp = PendingDirOp.Delete(dir)
                launchDirPermission(r.uris)
            }
            is DirOpResult.Failed -> Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootOrigin = it.positionInWindow() }
            .pointerInput(currentDir, searching, paused) {
                if (paused || currentDir.isEmpty() || searching) return@pointerInput
                var totalDx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDx = 0f },
                    onHorizontalDrag = { _, amount -> totalDx += amount },
                    onDragEnd = {
                        if (totalDx > 140f) currentDir = currentDir.substringBeforeLast("/", "")
                    },
                    onDragCancel = { totalDx = 0f },
                )
            }
            .pointerInput(paused) {
                if (paused) return@pointerInput
                // 长按弹菜单 / 长按拖动移动。
                // 挂在根节点上（列表的父级）：事件一定经过这里，且列表行被 LazyColumn 回收时
                // 手势不会被连带取消——拖到顶部回上一级时正是这种场景。
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downAt = SystemClock.uptimeMillis()
                    val timeoutMs = viewConfiguration.longPressTimeoutMillis
                    val slop = viewConfiguration.touchSlop
                    val hitVideo = videoRects.entries
                        .firstOrNull { it.value.contains(down.position + rootOrigin) }
                        ?.key
                        ?.let { id -> videosState.value.firstOrNull { it.id == id } }
                    // R1：文件夹命中（视频优先；没命中视频时才看文件夹）
                    val hitDirName = if (hitVideo == null) {
                        dirRects.entries.firstOrNull {
                            it.value.contains(down.position + rootOrigin)
                        }?.key
                    } else null
                    if (hitVideo == null && hitDirName == null) return@awaitEachGesture

                    // 三种结局：
                    // ① 按住不动到点 → 长按菜单
                    // ② 按住往右划一小段 → 直接唤菜单（不用等 500ms）
                    // ③ 纵向移动（滚列表）/ 往左划（返回上级）→ 让给下层
                    // 旧写法只要位移超过 touchSlop 就取消长按，手指稍微一偏就变成"点进播放"，
                    // 这就是用户说的"长按经常直接进播放"。
                    var longPressed = false
                    var swiped = false
                    var released = false
                    var movedMuch = false
                    var releaseChange: PointerInputChange? = null
                    val tapTimeoutMs = 300L      // 短按上限：超过它再松手视为"长按意图"，弹菜单而不是播放
                    val movedSlop = slop * 2f     // 容忍手指自然微抖
                    while (true) {
                        val remain = downAt + timeoutMs - SystemClock.uptimeMillis()
                        if (remain <= 0) {
                            longPressed = true
                            break
                        }
                        val event = withTimeoutOrNull(remain) { awaitPointerEvent() }
                        if (event == null) {
                            longPressed = true
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            released = true
                            releaseChange = change
                            break
                        }
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if ((change.position - down.position).getDistance() > movedSlop) movedMuch = true
                        if (dx > menuSwipePx && dx > kotlin.math.abs(dy) * 1.5f) {
                            change.consume()
                            swiped = true
                            break
                        }
                        // 只有"纵向为主"或"往左划"才让给下层；横向小抖动不再打断长按
                        if (kotlin.math.abs(dy) > slop || dx < -slop) break
                        if (change.isConsumed) break
                    }

                    if (swiped) {
                        // 手指还按着，先把剩余事件吃掉，等抬起来再弹菜单，免得上屏就点到菜单项
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            ch.consume()
                            if (!ch.pressed) break
                        }
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (hitVideo != null) actionTarget = hitVideo
                        else if (hitDirName != null) dirActionTarget = fullDirPath(currentDir, hitDirName)
                        return@awaitEachGesture
                    }

                    // 抬手（未到右滑/长按超时）：点击全部在这一层裁决，列表项已无 clickable，
                    // 所以只有这里主动调 onSelect 才会播放——从根上杜绝"长按松手误播放"。
                    if (released) {
                        val heldMs = SystemClock.uptimeMillis() - downAt
                        releaseChange?.consume()
                        if (!movedMuch && heldMs < tapTimeoutMs) {
                            // 短按：视频播放 / 文件夹进入
                            if (hitVideo != null) onSelect(hitVideo)
                            else if (hitDirName != null) {
                                currentDir = if (currentDir.isEmpty()) hitDirName else "$currentDir/$hitDirName"
                                query = ""
                            }
                        } else if (!movedMuch) {
                            // 按住一会儿才松手（没等到 500ms 长按判定）：按长按意图弹菜单
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (hitVideo != null) actionTarget = hitVideo
                            else if (hitDirName != null) dirActionTarget = fullDirPath(currentDir, hitDirName)
                        }
                        return@awaitEachGesture
                    }
                    if (!longPressed) return@awaitEachGesture   // 滚动/返回：让给下层

                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (hitVideo == null && hitDirName != null) {
                        // 文件夹：长按只弹菜单，不进入拖拽移动
                        dirActionTarget = fullDirPath(currentDir, hitDirName)
                        return@awaitEachGesture
                    }
                    // 长按到点立即弹菜单，不再等松手。
                    // 旧逻辑要松手才弹，用户按住半天屏幕毫无反应，会以为长按没生效。
                    actionTarget = hitVideo
                    // 菜单已经弹出，但手指仍按着时如果开始拖动，说明用户是要移动而不是操作菜单：
                    // 收起菜单、转为拖拽移动（原有移动能力保留）。
                    // 触摸序列在按下时就绑定到本窗口，后出现的 BottomSheet 不会抢走后续 move 事件。
                    var dragStarted = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        if (!dragStarted &&
                            (change.position - down.position).getDistance() > movedSlop
                        ) {
                            dragStarted = true
                            actionTarget = null
                            dragVideo = hitVideo
                            dragMoved = true
                            dragPos = change.position + rootOrigin
                        }
                        if (dragStarted) onDragPosition(change.position + rootOrigin)
                    }
                    if (dragStarted) finishDrag() else resetDrag()
                }
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
                // R4：模型下载入口（已装时打勾提示）
                IconButton(onClick = { showModelDialog = true }) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "下载离线字幕模型",
                        tint = if (modelReady) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { requireModel { showGenSubtitleDialog = true } }) {
                    Icon(Icons.Filled.Subtitles, contentDescription = "生成离线字幕")
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "重新扫描")
                }
                // 日志/设置收进「更多」：顶栏原来挤了五个图标，小屏上快放不下
                Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多：运行日志、设置")
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("运行日志") },
                            leadingIcon = { Icon(Icons.Filled.Article, contentDescription = null) },
                            onClick = {
                                showMoreMenu = false
                                onOpenLogs()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("设置") },
                            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            onClick = {
                                showMoreMenu = false
                                onOpenSettings()
                            },
                        )
                    }
                }
            }

            // ---- 字幕任务提示条：通知栏之外的第二个入口 ----
            val banner = genSnap
            if (!genBannerHidden && banner != null && (genBusy || banner.running)) {
                GenStatusBar(
                    text = banner.text,
                    running = genBusy,
                    onResume = { startGeneration(false) },
                    onDismiss = { genBannerHidden = true },
                )
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { listTopInWindow = it.boundsInWindow().top },
            ) {
            val listState = dirScrollStates.getOrPut(currentDir) { LazyListState() }
            val itemCount = childDirs.size + sorted.size
            // 全面屏底部有一条手势条，列表最后一行会被它压住，这里把安全距离补进 contentPadding
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            // 平板上单列会被拉得很宽、一行字横跨整屏：屏幕够宽时给两侧加留白，把阅读宽度收在
            // 600dp 左右。没直接改多列——列表位置恢复是按 index 记的，换网格会把"上次看到哪"打乱。
            val screenWidthDp = LocalConfiguration.current.screenWidthDp
            val sidePad = if (screenWidthDp >= 600) {
                (12 + ((screenWidthDp - 600) / 2).coerceAtMost(160)).dp
            } else {
                12.dp
            }

            when {
                // 一、没给读视频权限：说清原因 + 直接给两个出口，而不是一片空白
                !hasPermission -> StateHint(
                    icon = Icons.Filled.Lock,
                    title = "需要读取视频的权限",
                    desc = "MagPlay 只读你手机里的视频文件，不会上传、不会修改。" +
                        "授权后这里会列出所有视频。",
                    primaryText = "去授权",
                    onPrimary = onRequestPermission,
                    secondaryText = "系统设置",
                    onSecondary = onOpenAppSettings,
                )

                // 二、正在扫描：给出"读到第几个"，大目录也不会像卡死
                scanning -> StateHint(
                    icon = Icons.Filled.Movie,
                    title = "正在读取视频…",
                    desc = if (scanTotal > 0) "已找到 $scannedCount / $scanTotal 个" else "正在读取媒体库，请稍等",
                    showSpinner = true,
                )

                // 三、真的没有视频 / 搜索没结果
                itemCount == 0 -> StateHint(
                    icon = if (searching) Icons.Filled.Search else Icons.Filled.Movie,
                    title = if (searching) "没有匹配的视频" else "这里还没有视频",
                    desc = if (searching) {
                        "换个关键词试试，或清空搜索回到目录。"
                    } else {
                        "把视频放进手机的 Movies、Download 等目录后，点下面的按钮重新扫描。"
                    },
                    primaryText = if (searching) "清空搜索" else "重新扫描",
                    onPrimary = { if (searching) query = "" else onRefresh() },
                )

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = sidePad, end = sidePad, top = 4.dp,
                            bottom = 24.dp + navBottom,
                        ),
                        userScrollEnabled = dragVideo == null,
                    ) {
                        if (!searching) {
                            items(childDirs, key = { "dir:$it" }) { dir ->
                                DirCard(
                                    name = dir,
                                    highlighted = dragMoved && hoverDir == dir,
                                    onBounds = { rect -> dirRects[dir] = rect },
                                    onGone = { dirRects.remove(dir) },
                                    onEnter = {
                                        currentDir = if (currentDir.isEmpty()) dir else "$currentDir/$dir"
                                        query = ""
                                    },
                                    fileCount = dirVideoCounts[dir] ?: 0,
                                )
                            }
                        }
                        items(sorted, key = { it.id }) { video ->
                            // 长按/拖拽由外层全屏手势层统一处理：列表项会在切目录时被回收，
                            // 手势放在项里会被连带取消，拖到顶部回上一级就断了。
                            VideoCard(
                                video = video,
                                onBounds = { rect -> videoRects[video.id] = rect },
                                onGone = { videoRects.remove(video.id) },
                                onOpen = { onSelect(video) },
                            )
                        }
                    }

                    // 右侧滚动位置指示条：滚动时随 firstVisibleItemIndex 移动
                    if (itemCount > 6) {
                        val firstIndex = listState.firstVisibleItemIndex
                        val scrollFrac = firstIndex.toFloat() / itemCount.coerceAtLeast(1)
                        val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                        val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
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
                                color = trackColor,
                                topLeft = Offset(cx - 1f, 0f),
                                size = Size(2f, trackH),
                                cornerRadius = CornerRadius(1f),
                            )
                            drawRoundRect(
                                color = thumbColor,
                                topLeft = Offset(cx - 4f, y),
                                size = Size(8f, thumbH),
                                cornerRadius = CornerRadius(4f),
                            )
                        }
                    }
                }
            }
            }
        }

        // ---- 拖拽浮层 ----
        val draggingVideo = dragVideo
        if (draggingVideo != null && dragMoved) {
            // 顶部提示：分两阶段——"停留一下才回上级" / "已回到上级，松手就移到这里"
            val topLabel = when {
                jumpedUp && currentDir.isEmpty() -> "松手无效：根目录不能作为目标"
                jumpedUp -> "松手移入：${currentDir.substringAfterLast("/")}"
                jumpHint && currentDir.isNotEmpty() -> "停留一下返回上一级"
                else -> null
            }
            if (topLabel != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 6.dp),
                ) {
                    Text(
                        text = topLabel,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 10.dp,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (dragPos.x - rootOrigin.x - 90.dp.toPx()).roundToInt(),
                            (dragPos.y - rootOrigin.y - 26.dp.toPx()).roundToInt(),
                        )
                    }
                    .width(180.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(
                        Icons.Filled.DriveFileMove,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = draggingVideo.name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    // ---- R1：文件夹长按菜单 ----
    dirActionTarget?.let { dir ->
        val count = videosState.value.count {
            val p = it.relativePath.trim('/')
            p == dir.trim('/') || p.startsWith("${dir.trim('/')}/")
        }
        MenuSheet(
            title = dir.substringAfterLast("/"),
            subtitle = "该文件夹含 $count 个视频",
            onDismiss = { dirActionTarget = null },
        ) {
            MenuRow(Icons.Filled.Edit, "重命名文件夹") {
                dirActionTarget = null
                dirRenameValue = dir.substringAfterLast("/")
                dirRenameTarget = dir
            }
            MenuRow(Icons.Filled.Delete, "删除文件夹及其中全部文件", danger = true) {
                dirActionTarget = null
                dirDeleteTarget = dir
            }
        }
    }

    // ---- R1：文件夹重命名输入 ----
    dirRenameTarget?.let { dir ->
        AlertDialog(
            onDismissRequest = { dirRenameTarget = null },
            title = { Text("重命名文件夹") },
            text = {
                Column {
                    Text("该文件夹及子目录下的视频会移动到新目录，字幕同步跟随。",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = dirRenameValue,
                        onValueChange = { dirRenameValue = it },
                        label = { Text("新名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = dirRenameValue.trim()
                    dirRenameTarget = null
                    if (name.isNotEmpty()) doDirRename(dir, name)
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { dirRenameTarget = null }) { Text("取消") } },
        )
    }

    // ---- R1：文件夹删除二次确认 ----
    dirDeleteTarget?.let { dir ->
        val count = videosState.value.count {
            val p = it.relativePath.trim('/')
            p == dir.trim('/') || p.startsWith("${dir.trim('/')}/")
        }
        AlertDialog(
            onDismissRequest = { dirDeleteTarget = null },
            title = { Text("删除文件夹？") },
            text = {
                Text(
                    "将物理删除「${dir.substringAfterLast("/")}」及其子目录中的 $count 个视频和同名字幕，无法恢复。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dirDeleteTarget = null
                    doDirDelete(dir)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { dirDeleteTarget = null }) { Text("取消") } },
        )
    }

    // ---- 长按菜单：本视频字幕 / 重命名 / 删除 ----
    actionTarget?.let { target ->
        MenuSheet(
            title = target.name,
            onDismiss = { actionTarget = null },
        ) {
            // 只处理这一条，不扫全量：已生成过的再点就是覆盖重做
            val hasSrt = SubtitleStore.exists(context, target)
            MenuRow(
                Icons.Filled.Subtitles,
                if (hasSrt) "重新生成这个视频的字幕" else "为这个视频生成字幕",
            ) {
                actionTarget = null
                requireModel { startGenSingle(target.id) }
            }
            MenuRow(Icons.Filled.Edit, "重命名") {
                actionTarget = null
                renameTarget = target
            }
            MenuRow(Icons.Filled.Delete, "删除", danger = true) {
                actionTarget = null
                deleteTarget = target
            }
        }
    }

    // ---- 删除确认 ----
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除视频？") },
            text = {
                Column {
                    Text(
                        target.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "会从设备上物理删除该视频文件，同名字幕（.srt）一并删除，无法恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    doDelete(target)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
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

    // ---- R4：模型下载 / 未安装引导 ----
    if (showModelDialog) {
        val busy = dlState is ModelDownloader.State.Downloading
        AlertDialog(
            onDismissRequest = { if (!busy) showModelDialog = false },
            title = { Text(if (modelReady) "字幕模型已安装" else "安装离线字幕模型") },
            text = {
                Column {
                    Text(
                        if (modelReady) "FireRedASR2 AED 已就绪，可直接生成离线字幕。"
                        else "字幕在本机生成，不联网。需先安装 FireRedASR2 AED 模型（约 1.2GB），建议在 WiFi 下下载。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    when (val s = dlState) {
                        is ModelDownloader.State.Downloading -> {
                            // 只给百分比看不懂进度到哪了：补上进度条和已下载/总大小
                            val overall = if (s.totalBytes > 0) {
                                (s.doneBytes.toFloat() / s.totalBytes).coerceIn(0f, 1f)
                            } else {
                                s.percent / 100f
                            }
                            LinearProgressIndicator(
                                progress = { overall },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "第 ${s.fileIndex}/${s.fileCount} 个文件（${s.fileName}）  ${(overall * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (s.totalBytes > 0) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "已下载 ${formatSize(s.doneBytes)} / ${formatSize(s.totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        is ModelDownloader.State.Failed -> Text(
                            "失败：${s.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        else -> Unit
                    }
                }
            },
            confirmButton = {
                when {
                    modelReady -> TextButton(onClick = { showModelDialog = false }) { Text("知道了") }
                    busy -> TextButton(onClick = {
                        ModelDownloader.cancel()
                        // 已下载的部分会被清掉，下次从头开始，说明白免得用户以为续传
                        Toast.makeText(context, "已取消下载，下次从头开始", Toast.LENGTH_SHORT).show()
                    }) { Text("取消下载") }
                    else -> TextButton(onClick = {
                        // 1.2GB，移动网络下先问一句
                        if (NetGuard.isCellular(context)) {
                            confirmMobileDownload = true
                        } else {
                            scope.launch { ModelDownloader.install(context) }
                        }
                    }) { Text("开始下载") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showModelDialog = false }, enabled = !busy) { Text("关闭") }
            },
        )
    }

    // ---- 移动网络下下载 1.2GB 模型：先确认 ----
    if (confirmMobileDownload) {
        AlertDialog(
            onDismissRequest = { confirmMobileDownload = false },
            title = { Text("当前是移动网络") },
            text = {
                Text("离线字幕模型约 1.2GB，用移动网络下载会消耗较多流量。建议连上 WiFi 再下。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmMobileDownload = false
                    scope.launch { ModelDownloader.install(context) }
                }) { Text("仍然下载") }
            },
            dismissButton = {
                TextButton(onClick = { confirmMobileDownload = false }) { Text("等 WiFi") }
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
                    genPlanText?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    GenOptionCard(
                        title = "补齐缺失字幕",
                        desc = "只为还没有可用字幕的视频生成，已有字幕跳过。日常补充用这个。",
                    ) {
                        showGenSubtitleDialog = false
                        if (PowerGuard.isIgnoringBatteryOptimizations(context)) {
                            startGeneration(false)
                        } else {
                            pendingGenForce = false
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    GenOptionCard(
                        title = "重新生成",
                        desc = "直接删除全部已有字幕文件（不备份，不可恢复），再对每个视频重新生成。耗时最长，字幕不准/时间轴错乱时用。",
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
                    "将直接删除当前所有视频已有的字幕文件，不做备份，删除后不可恢复。" +
                        "确认后逐个重新生成；视频较多、较长时可能跑很久，可在通知栏随时取消。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRegenerateConfirm = false
                    if (PowerGuard.isIgnoringBatteryOptimizations(context)) {
                        startGeneration(true)
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

    // ---- 通知权限被拒：告诉用户会失去什么，并给一个直达入口 ----
    if (notifyDeniedDialog) {
        AlertDialog(
            onDismissRequest = { notifyDeniedDialog = false },
            title = { Text("通知权限没开") },
            text = {
                Text(
                    "字幕在后台生成时，进度、取消、继续都只在通知栏里。不开通知权限，你看不到进度，" +
                        "也找不到「继续生成」的入口。任务已经按你的选择启动了。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    notifyDeniedDialog = false
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                            )
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text("去开启") }
            },
            dismissButton = {
                TextButton(onClick = { notifyDeniedDialog = false }) { Text("暂不") }
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
                    // 只跳设置页，不在这里启动：等用户回来、确认真的拿到白名单后再启动
                    pendingGenForce = null
                    pendingBatteryForce = force
                    PowerGuard.requestIgnoreBatteryOptimizations(context)
                }) { Text("去设置并开始") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingGenForce = null
                    startGeneration(force)
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
private fun DirCard(
    name: String,
    highlighted: Boolean = false,
    onBounds: (Rect) -> Unit = {},
    onGone: () -> Unit = {},
    onEnter: (() -> Unit)? = null,
    fileCount: Int = 0,
) {
    // 列表滚出屏幕后旧坐标必须失效，否则拖拽会命中已经不在屏幕上的文件夹
    androidx.compose.runtime.DisposableEffect(name) {
        onDispose { onGone() }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        border = if (highlighted) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .onGloballyPositioned { onBounds(it.boundsInWindow()) }
            // 读屏用户：整行念成一个可点击的文件夹，双击进入
            .semantics(mergeDescendants = true) {
                contentDescription = if (fileCount > 0) "文件夹 $name，$fileCount 个视频" else "文件夹 $name"
                role = Role.Button
                if (onEnter != null) {
                    onClick(label = "打开文件夹") {
                        onEnter()
                        true
                    }
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(FileTint.folderBg()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    tint = FileTint.folderFg(),
                    modifier = Modifier.size(26.dp),
                )
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
    onBounds: (Rect) -> Unit = {},
    onGone: () -> Unit = {},
    onOpen: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var thumb by remember(video.id) { mutableStateOf<ImageBitmap?>(ThumbnailLoader.cached(video.id)) }
    androidx.compose.runtime.DisposableEffect(video.id) {
        onDispose { onGone() }
    }
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
            .onGloballyPositioned { onBounds(it.boundsInWindow()) }
            .padding(vertical = 4.dp)
            // 读屏用户：整行念成"文件名 + 时长"，双击播放。
            // 触摸点击仍由外层手势层裁决，这里只补无障碍动作，不改触摸行为。
            .semantics(mergeDescendants = true) {
                contentDescription = "${video.name}，时长 ${formatDuration(video.durationMs)}"
                role = Role.Button
                if (onOpen != null) {
                    onClick(label = "播放") {
                        onOpen()
                        true
                    }
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(FileTint.videoBg()),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = thumb
                if (bitmap != null) {
                    Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(
                        Icons.Filled.Movie,
                        contentDescription = null,
                        tint = FileTint.videoFg(),
                        modifier = Modifier.size(30.dp),
                    )
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

/** 第一屏的说明卡片：没权限 / 扫描中 / 没视频，三种情况各说各话，别都丢一片空白。 */
@Composable
private fun StateHint(
    icon: ImageVector,
    title: String,
    desc: String,
    primaryText: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
    showSpinner: Boolean = false,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 48.dp),
    ) {
        if (showSpinner) {
            CircularProgressIndicator(modifier = Modifier.size(38.dp), strokeWidth = 3.dp)
        } else {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(52.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            desc,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (primaryText != null && onPrimary != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onPrimary) { Text(primaryText) }
        }
        if (secondaryText != null && onSecondary != null) {
            TextButton(onClick = onSecondary) { Text(secondaryText) }
        }
    }
}

/** 待系统授权后重试的文件操作：targetDir 为 null 表示删除。 */
private data class PendingFileOp(val video: VideoItem, val targetDir: String?)

/** 重命名后恢复可见项用：目录 + 首个可见项 key + 原 index/offset。 */
private data class ScrollRestore(
    val dir: String,
    val firstKey: String?,
    val index: Int,
    val offset: Int,
)

/** R1：等待系统授权后重试的目录批量操作。 */
private sealed interface PendingDirOp {
    data class Rename(val dir: String, val newName: String) : PendingDirOp
    data class Delete(val dir: String) : PendingDirOp
}

private fun fullDirPath(current: String, name: String): String =
    if (current.isEmpty()) name.trim('/') else "${current.trim('/')}/${name.trim('/')}"

/**
 * 长按操作面板。从底部滑出，替代原来居中的 AlertDialog。
 *
 * 用对话框当上下文菜单是错的：对话框是"确认/输入"范式，出现在屏幕正中，
 * 与用户刚才长按的位置完全脱节；而且 AlertDialog 的 confirmButton 留空后，
 * 底部只剩一个孤零零的「取消」，视觉重心是歪的。
 */
/**
 * 首页的字幕任务提示条（通知栏之外的第二个入口）。
 *
 * 运行中：显示进度，可一键隐藏。
 * 被打断：进程被安装/强杀终止时锁已释放、status.json 还残留 running=true，
 * 这里据此给出「继续生成」，点一下从下一条视频接着跑，不必去通知栏翻按钮。
 */
@Composable
private fun GenStatusBar(
    text: String,
    running: Boolean,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
) {
    val container = if (running) MaterialTheme.colorScheme.surfaceVariant
    else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (running) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.onSecondaryContainer
    val pct = Regex("(\\d{1,3})\\s*%").find(text)?.groupValues?.get(1)?.toIntOrNull()
    Surface(
        color = container,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (running) Icons.Filled.Subtitles else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (running) "正在生成字幕" else "上次生成被打断了",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = fg,
                    )
                    Text(
                        text = text.ifBlank { "—" },
                        style = MaterialTheme.typography.labelSmall,
                        color = fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (running) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "隐藏提示",
                            tint = fg,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    TextButton(onClick = onResume) { Text("继续生成") }
                }
            }
            if (pct != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuSheet(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** 长按面板里的一行。56dp 最小高度，满足触控标准。 */
@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    return String.format("%02d:%02d:%02d", totalSec / 3600, totalSec % 3600 / 60, totalSec % 60)
}

/** 拖到列表顶端后要停留多久才认定为"回上一级"。太短会误触，太长会嫌烦。 */
private const val TOP_DWELL_MS = 700L
