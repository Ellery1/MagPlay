# MagPlay 技术设计（对应 PRD v0.3）

> 2026-11-26 | 待实施 | 只覆盖本轮 R1~R10；旧版中与本轮冲突的方案作废

## 0. 改动清单

| 文件 | 涉及 |
|---|---|
| `ui/VideoListScreen.kt` | R1 R3 R4 R6 |
| `MainActivity.kt` | R6 |
| `player/PlayerScreen.kt` | R7 R8 R9 R10 |
| `data/VideoFiles.kt` | R1 |
| `data/PlaybackProgress.kt`（新增） | R8 |
| `asr/AsrBackends.kt`、`AsrModelConfigFactory.kt`、`subtitle/AsrSettings.kt` | R2 R3 R4 |
| `subtitle/SubtitleGenerationService.kt`、`AsrModelConfigFactory.kt`、`SrtFile.kt` | R5 |
| `assets/models/asr/*`、`dist/models/*` | R2 R3 |
| `res/xml/network_security_config.xml`（新增）、`AndroidManifest.xml` | R4 |
| `scripts/` 下新增服务器部署/清理脚本 | R3 R4 |

---

## R1 文件夹菜单

- 命中判定：现有根 `pointerInput` 里 `videoRects` 改为「视频优先、文件夹兜底」。新增 `dirRects` 命中（`dirRects` 已有，当前只服务拖拽高亮），命中文件夹时走同一套长按/右滑分支，只是最终 `actionTarget` 换成 `DirActionTarget`。
- 菜单：`AlertDialog` 两行：重命名（输入文本框）、删除（红色）。视频原菜单不变。
- 数据操作：在 `VideoFiles` 新增
  - `renameDir(context, dirPath, newName): FileOpResult` — 对 `relativePath` 以 `dirPath/` 开头（含子目录）的每条视频，复用 `move()` 的 `ContentValues` 更新 `RELATIVE_PATH`，字幕跟随、`GenAttempts` 与 `rekeyEngine` 同步；逐条处理，汇总「成功 X / 失败 Y」，授权失败走现有 `permLauncher` 重试。
  - `deleteDir(context, dirPath): FileOpResult` — 同样筛选，逐条调用 `delete()`，二次确认文案带文件数。
- 批量操作期间禁用列表交互（`busy` 状态），避免重复点击。根目录（`dirPath` 为空）直接不挂菜单。

## R2 + R3 模型瘦身与单一引擎

- 删文件：`assets/models/asr/model.int8.onnx`、`bbpe.model`、`assets/models/asr/paraformer/`；保留 `silero_vad_v5.onnx`（按官方字节数校验，禁止镜像替换）。
- 删代码：`AsrBackend` 只留 `FIRERED_AED`；`AsrModelConfigFactory.modelConfig()` 删其余 `when` 分支；`AsrSettings.PRIORITY` 与 `setBackend()` 删除，`backend()` 恒返回 AED；`VideoListScreen` 生成对话框删引擎 chip 行；`BenchRunner/BenchService` 候选列表同步收敛。
- 本机 `dist/models/` 删除 `paraformer/`、`firered_ctc/`、`qwen3/` 及 `fetch_asr_models.sh` 对应下载分支，只保留 `firered_aed/` 和新清单。
- **手机端删除**：无线 adb `192.168.31.39:5555`（`dist/push_models.sh` 已有连接逻辑）。执行
  `adb -s 192.168.31.39:5555 shell run-as com.magplay rm -rf files/models/paraformer files/models/firered_ctc files/models/qwen3`，
  再用 `run-as com.magplay find files/models -type f -exec stat -c '%s %n' {} +` 确认只剩 `firered_aed`。
  历史 `dist/push_models.log` 记录过该 IP 连不上：**连不上立刻找用户，不反复重试、不换十种写法撞**。不要动用户未授权目录。

## R4 模型下载

**服务器**（我操作）：在该机器部署静态 HTTP（如 nginx 单目录），路径 `/magplay/firered_aed/`，放 encoder.int8.onnx / decoder.int8.onnx / tokens.txt + `manifest.json`（三文件字节数、SHA256、版本号）。上传后 `stat -c%s` 与 `sha256sum` 逐个比对本地文件。域名不用，固定 IP 直连。

**App**：
- 新增 `subtitle/ModelDownloader.kt`：OkHttp 不可用（无依赖），用 `HttpURLConnection`。按文件串行下载 → 写 `<name>.part` → 完成后校验清单里的 size + SHA256（`MessageDigest`）→ `renameTo` 正式名；失败/取消删 `.part`。支持 `Range` 断点续传（服务端静态文件天然支持）；进度用 `StateFlow<DownloadState>` 暴露。目标目录 `context.filesDir/models/firered_aed/`。
- `network_security_config.xml`：只对 `47.103.215.190` 配 `<domain-config cleartextTrafficPermitted="true">`；Manifest 的 `<application>` 引用 `android:networkSecurityConfig`。禁止全局明文开关。
- `VideoListScreen` 顶栏加下载按钮 → `ModelInstallDialog`（大小、WiFi 建议、进度、取消）。生成入口统一走 `withModelReady(context) { ... }`：未安装弹「前往下载」，下载中提示稍后。两个入口（顶栏、视频菜单）都要包。
- 下载用系统 DownloadManager 会导致文件落在公共目录再搬（历史属主问题），本方案选择应用内下载直接写 filesDir。

## R5 漏句治理

样本：用户手机上的 `欲女XXX.mp4`（漏句严重）。步骤：
1. 先跑基线：重新生成该片，导出 .srt 与 App 日志（`AppLog` 导出），按用户给的时间点标注缺失句。
2. 在 `SubtitleGenerationService` 的 VAD 切段与识别环节加诊断日志（段起止、识别文本长度、是否空、sanitize 前后条数），定位缺失发生在哪一层。
3. 按命中层修：
   - VAD 漏切 → 调 `AsrModelConfigFactory.vadConfig()` 参数（threshold 0.5→0.35/0.4、minSpeechDuration 0.25→0.15、maxSpeechSeconds 20→25），段间加 100~200ms 重叠，避免边界吞字。
   - ASR 空返回 → 对该段重试一次（提高 `AudioPreprocess.normalize` 增益上限或去掉高通的副本），日志区分「真的无语音」和「识别失败」。
   - 切句/校验丢弃 → `SubtitleLineBreaker.split()` 的 `filter { it.second.last > it.second.first }` 与 `SrtFile.sanitize()` 改为「修时间不丢文本」；**不得放宽 `isUsable()` 的脏数据门槛**。
4. 验收：同片重生成，缺失句出现；正常句不回退；重复/误切条数不显著上升。

## R6 目录与滚动位置

- 状态提升到 `MainActivity`：`currentDir`、`query`、`selectedType`、`scrollIndex`、`scrollOffset` 五个 `mutableStateOf` 提到 `MagPlayApp`，作为 `VideoListScreen` 参数传入（去掉内部同名 `remember`）。
- `onSelect` 时记录 `listState.firstVisibleItemIndex` 与 `firstVisibleItemScrollOffset`；`onBack`（播放器左上角、BackHandler）只把 `selected=null`，列表 Composable 不重建，状态自然保留；若因配置变化重建，用 `rememberLazyListState(initialFirstVisibleItemIndex = savedIndex, initialFirstVisibleItemScrollOffset = savedOffset)`。
- 视频移走/删除后，`sorted` 变短 → `LaunchedEffect(sorted.size) { listState.scrollToItem(savedIndex.coerceAtMost(sorted.lastIndex)) }` 兜底。

## R7 状态栏

- `PlayerScreen` 用 `WindowInsetsControllerCompat(activity.window, activity.window.decorView)`：控制条显示时 `show(WindowInsetsCompat.Type.statusBars())`，隐藏时 `hide(...)`；与 `controlsSeq` 的 3 秒定时器、单击切换同一入口。
- 亮色画面图标：`WindowCompat.setDecorFitsSystemWindows(window, false)` 已开启；按画面亮度采样（复用 `readBrightness()` 或取视频平均亮度）设置 `APPEARANCE_LIGHT_STATUS_BARS`；取不到时用半透明黑色渐变遮罩保证可读。
- 不动 `enableEdgeToEdge()` 与其它沉浸式设置。

## R8 播放进度

- 新增 `data/PlaybackProgress.kt`：`SharedPreferences("play_progress")`，key = MediaStore id（`VideoItem.id`），value = `positionMs / durationMs / updatedAt` 的 JSON（无新依赖，手写字符串或 `JSONObject`）。
- `PlayerScreen`：`STATE_READY` 后只读一次 `savedPosition`（`LaunchedEffect` + `remember { mutableStateOf(true) }` 防重复 seek），`player.seekTo()`；播放中 `LaunchedEffect(Unit) { while(true){ delay(3000); save(currentPosition) } }`；`DisposableEffect.onDispose` 再存一次；`STATE_ENDED` 调 `clear()`。
- 扫描后清理：`VideoScanner.scan()` 返回 id 集合，删掉不在其中的记录（可放在 `onRefresh`）。
- 异常值：`position in 0..duration` 才采用，否则 0。

## R9 中央按钮

- 撤销 `exo_play_pause.isClickable=false`；保留 1.7x 视觉缩放。
- 感应区：在 `PlayerView` 之上叠一个 Compose `Box(Modifier.align(Alignment.Center).size(96.dp))`（透明，只在控制条可见时 `pointerInput` 生效），点击走 `togglePlayPause()`。**同时**保留 `PlayerView.setOnTouchListener` 的双击判定：中央 96dp 区域内的点击也纳入「最后一次点击」判定，双击命中时 `removeCallbacks(singleTapRunnable)` 并只执行一次 `togglePlayPause()`。
- 结果：单击按钮 = 一次切换；双击（含落在按钮上）= 一次切换。显示的按钮仍由 media3 绘制，点击由上层 Box 接管，避免 media3 自身点击与手势层重复触发。

## R10 保屏

- `PlayerScreen` 的 `DisposableEffect`：`activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)`，`onDispose` 里 `clearFlags(FLAG_KEEP_SCREEN_ON)`（与旋转/亮度恢复放同一 Effect）。与暂停状态无关，只要页面在就持有。
- 距离传感器误触：若真机仍出现黑屏，补 `PowerManager.newWakeLock(SCREEN_BRIGHT_WAKE_LOCK or ON_AFTER_RELEASE, "magplay:playback")`，退出时 release；`WAKE_LOCK` 权限 Manifest 已有。
- 验证：播放/暂停各静置 10 分钟；退出后确认 `dumpsys power` 中 App 不再持有锁。

---

## 实施顺序

1. R2/R3（含手机端模型清理）→ 2. R4 服务器 + App 下载 → 3. R9/R10/R7 → 4. R8/R6 → 5. R1 → 6. R5（依赖用户给漏句时间点）。

每阶段真机验证后再进下一阶段。无线 adb 连不上就按 PRD 的依赖约定找用户。
