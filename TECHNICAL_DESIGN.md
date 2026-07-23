# MagPlay（磁力播） - 技术设计文档

> 版本：v0.1 | 日期：2026-07-23 | 状态：初稿

---

## 1. 整体架构

### 1.1 架构概述

采用 **单 Activity + MVVM + Clean Architecture** 的分层架构，配合 Jetpack Compose 构建声明式 UI。

```
┌──────────────────────────────────────────────┐
│                  UI Layer                     │
│   Compose Screens / ViewModels / NavHost      │
├──────────────────────────────────────────────┤
│               Domain Layer                    │
│       UseCases / Repositories (接口)          │
├──────────────────────────────────────────────┤
│                Data Layer                     │
│  Room DB / FileSystem / JNI(Native)           │
├──────────────────────────────────────────────┤
│              Native Layer (C++)               │
│  libtorrent / whisper.cpp / translator        │
└──────────────────────────────────────────────┘
```

### 1.2 分层职责

| 层 | 职责 | 关键约束 |
|---|---|---|
| UI Layer | Compose 页面渲染、ViewModel 状态管理、用户交互 | 不直接访问数据源，不包含业务逻辑 |
| Domain Layer | UseCase 封装业务逻辑、Repository 接口定义 | 纯 Kotlin，无 Android 依赖，可单元测试 |
| Data Layer | Room DAO、文件 IO、JNI 桥接、Repository 实现 | 所有 IO 操作在主线程外执行 |
| Native Layer | libtorrent 下载引擎、ASR 推理、NMT 翻译推理 | C++17，通过 JNI 暴露接口给 Kotlin |

---

## 2. 技术栈

### 2.1 语言与框架

| 类型 | 选择 | 版本 | 说明 |
|---|---|---|---|
| 语言 | Kotlin | 2.0+ | 主要开发语言 |
| UI | Jetpack Compose + Material3 | BOM 2026.x | 声明式 UI |
| 导航 | Compose Navigation | 2.8+ | 单 Activity 内页面路由 |
| DI | Hilt | 2.51+ | 依赖注入 |
| 异步 | Kotlin Coroutines + Flow | 1.9+ | 所有 IO 和跨层通信 |
| 构建 | Gradle + Kotlin DSL | 8.x + AGP 8.x | |

### 2.2 核心依赖

| 类型 | 选择 | 说明 |
|---|---|---|
| 数据库 | Room | SQLite 抽象，任务/播放记录/设置持久化 |
| 视频播放 | Media3 ExoPlayer | Google 官方媒体播放器，支持所有目标格式与编码 |
| 图片加载 | Coil | Compose 原生图片加载（视频缩略图） |
| 数据存储偏好 | DataStore (Preferences) | 简单键值设置项 |

### 2.3 Native 层（C++，通过 JNI/NDK 集成）

| 模块 | 选择 | 说明 |
|---|---|---|
| 下载引擎 | **libtorrent** | 成熟的 BitTorrent C++ 库，支持 DHT、uTP、选择性下载 |
| 语音识别(ASR) | **sherpa-onnx** | 支持多种 ASR 模型（Whisper/Paraformer/SenseVoice），流式识别，ONNX 推理 |
| 机器翻译(NMT) | **CTranslate2** + Opus-MT 模型 | 设备端神经机器翻译，支持英文→中文等语言对 |

> **选型说明：**
>
> **libtorrent**：BitTorrent 领域最成熟的 C++ 库，支持 DHT 网络（不依赖 Tracker）、选择性下载（仅下载用户勾选的文件）、TCP/uTP 传输。通过 JNI 包装为 Kotlin 可调用的接口。
>
> **sherpa-onnx**：基于 ONNX Runtime 的语音识别框架，同时支持 Whisper（高精度，适合英文）和 Paraformer（中文友好）模型，提供流式识别 API（音频边采集边输出文本），延迟可控。
>
> **CTranslate2 + Opus-MT**：CTranslate2 是专为 Transformer 模型优化的推理引擎，支持 INT8 量化，在移动端推理速度快。Opus-MT 提供预训练的英→中翻译模型，模型体积小（~70MB）。

---

## 3. 项目模块划分

```
MagPlay/
├── app/                          # 主工程入口，DI 配置
├── core/
│   ├── common/                   # 通用工具（扩展函数、常量、日志）
│   ├── database/                 # Room 数据库定义、DAO、Entity
│   ├── model/                    # 跨模块共享的 Domain Model
│   └── ui/                       # 通用 Compose 组件（主题、Loading、ErrorView 等）
├── feature/
│   ├── download/                 # 磁力下载模块
│   │   ├── data/                 # 下载任务 Repository 实现
│   │   ├── domain/               # 下载相关 UseCase
│   │   └── ui/                   # 下载页面 Compose UI
│   ├── player/                   # 视频播放模块
│   │   ├── data/
│   │   ├── domain/
│   │   └── ui/
│   ├── subtitle/                 # 实时字幕模块（ASR + 翻译）
│   │   ├── data/
│   │   ├── domain/
│   │   └── ui/
│   ├── thunderscan/              # 迅雷视频扫描导入模块
│   │   ├── data/
│   │   ├── domain/
│   │   └── ui/
│   └── settings/                 # 设置模块
│       ├── data/
│       ├── domain/
│       └── ui/
└── native/                       # C++ 原生代码
    ├── libtorrent_bridge/        # libtorrent JNI 桥接
    ├── asr_bridge/               # sherpa-onnx ASR JNI 桥接
    └── translate_bridge/         # CTranslate2 翻译 JNI 桥接
```

---

## 4. 数据层设计

### 4.1 Room 数据库表

#### 下载任务表 `download_tasks`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | INTEGER (PK, auto) | 主键 |
| `magnet_uri` | TEXT | 磁力链接完整 URI |
| `name` | TEXT | 任务名称（种子名或用户自定义） |
| `status` | TEXT | 状态：PENDING / DOWNLOADING / PAUSED / COMPLETED / FAILED |
| `total_bytes` | INTEGER | 选中文件总字节数 |
| `downloaded_bytes` | INTEGER | 已下载字节数 |
| `download_speed` | INTEGER | 当前速度 (bytes/s)，仅运行时有效 |
| `peer_count` | INTEGER | 已连接 Peer 数，仅运行时有效 |
| `eta_seconds` | INTEGER | 预估剩余秒数，仅运行时有效 |
| `save_dir` | TEXT | 文件保存目录路径 |
| `error_message` | TEXT | 失败原因（status=FAILED 时） |
| `created_at` | INTEGER | 创建时间戳 |
| `updated_at` | INTEGER | 最后更新时间戳 |

#### 下载文件表 `download_files`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | INTEGER (PK, auto) | 主键 |
| `task_id` | INTEGER (FK) | 所属下载任务 |
| `file_name` | TEXT | 文件名 |
| `file_path` | TEXT | 种子内相对路径 |
| `file_size` | INTEGER | 文件大小（字节） |
| `is_selected` | INTEGER | 是否被用户勾选下载 (0/1) |
| `downloaded_bytes` | INTEGER | 该文件已下载字节数 |
| `is_video` | INTEGER | 是否为视频文件 (0/1) |

#### 播放记录表 `play_history`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | INTEGER (PK, auto) | 主键 |
| `video_path` | TEXT (UNIQUE) | 视频文件绝对路径 |
| `video_name` | TEXT | 视频文件名 |
| `duration_ms` | INTEGER | 视频总时长（毫秒） |
| `position_ms` | INTEGER | 上次播放位置（毫秒） |
| `last_played_at` | INTEGER | 最后播放时间戳 |

#### 字幕模型表 `subtitle_models`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | INTEGER (PK, auto) | 主键 |
| `model_type` | TEXT | 类型：ASR / TRANSLATE |
| `language` | TEXT | 语言代码（zh / en / en-zh 等） |
| `model_name` | TEXT | 模型名称标识 |
| `file_path` | TEXT | 模型文件本地路径 |
| `file_size` | INTEGER | 模型文件大小（字节） |
| `is_downloaded` | INTEGER | 是否已下载完成 (0/1) |

### 4.2 DataStore 键值设置

| Key | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `download_path` | String | `{app_private}/Videos` | 下载保存路径 |
| `cellular_data_warning` | Boolean | true | 移动数据下载前是否弹窗 |
| `default_playback_speed` | Float | 1.0 | 默认播放倍速 |
| `default_subtitle_enabled` | Boolean | false | 默认字幕开关 |
| `subtitle_font_size` | String | "medium" | 字幕字体大小 (small/medium/large) |
| `hardware_decode` | Boolean | true | 硬件解码优先 |
| `theme_mode` | String | "system" | 主题 (light/dark/system) |
| `last_memory_speed` | Float | 1.5 | 长按倍速记忆倍率 |

---

## 5. 模块详细设计

### 5.1 下载模块

#### 5.1.1 核心流程

```
用户输入磁力链接
      │
      ▼
MagnetUriValidator 校验格式
      │ (magnet:?xt=urn:btih:...)
      ▼
DownloadEngine.addMagnetUri(uri)
      │ JNI → libtorrent
      ▼
libtorrent 通过 DHT 获取 metadata（种子文件列表）
      │ 回调 → Kotlin
      ▼
展示文件列表 → 用户勾选 → 设置 file priority=0（不下载）或 priority>0（下载）
      │
      ▼
libtorrent 开始选择性下载
      │ 定时回调（每秒）→ Kotlin
      ▼
Room 更新进度（每 5 秒写入一次），UI 通过 Flow 更新
```

#### 5.1.2 JNI 接口设计（libtorrent_bridge）

```kotlin
// Kotlin 侧接口
object DownloadEngine {
    // 初始化引擎（设置 DHT 端口、下载目录等）
    fun init(config: EngineConfig)

    // 添加磁力链接，返回任务句柄
    suspend fun addMagnetUri(uri: String): TaskHandle

    // 获取种子元数据（文件列表）
    suspend fun getTorrentInfo(handle: TaskHandle): TorrentInfo

    // 设置文件优先级（0=不下载，1=正常，7=最高）
    fun setFilePriority(handle: TaskHandle, fileIndex: Int, priority: Int)

    // 控制任务
    fun pause(handle: TaskHandle)
    fun resume(handle: TaskHandle)
    fun remove(handle: TaskHandle, deleteFiles: Boolean)

    // 获取任务状态（进度、速度、Peer 数等）
    fun getStatus(handle: TaskHandle): TaskStatus
}
```

#### 5.1.3 并发控制

- libtorrent Session 内部管理并发连接，Kotlin 侧维护一个任务队列（`Channel<TaskHandle>`）。
- 同时 `active_downloads=3`，通过 libtorrent 的 `set_max_uploads/downloads` 或 Kotlin 层控制任务暂停/恢复实现。

#### 5.1.4 后台下载

- 使用 `ForegroundService` + 通知栏常驻通知，确保下载不被系统杀死。
- Service 内持有 libtorrent Session 单例，App Activity 销毁不影响下载。

### 5.2 播放模块

#### 5.2.1 核心架构

基于 **Media3 ExoPlayer** 构建，封装为 `MagPlayer`：

```
MagPlayer（包装 ExoPlayer）
├── 播放控制：play / pause / seekTo / setPlaybackSpeed
├── 手势处理：长按倍速、左右滑动手势（亮度/音量）
├── 状态管理：StateFlow<PlayerState> 暴露给 ViewModel
└── 字幕覆盖层：Compose Canvas 叠加字幕文本
```

#### 5.2.2 手势实现

| 手势 | 区域 | 实现方式 |
|---|---|---|
| 长按倍速 | 全屏 | `detectDragGesturesAfterLongPress` → 左滑降档/右滑升档 → 松手恢复 1.0x |
| 亮度调节 | 左半屏竖向滑动 | `detectVerticalDragGestures` → 修改 `LayoutParams.screenBrightness` |
| 音量调节 | 右半屏竖向滑动 | `detectVerticalDragGestures` → 修改 `AudioManager` 音量 |
| 快进/快退 | 双击左右 | `detectTapGestures(onDoubleTap)` → seek +10s/-10s |

#### 5.2.3 播放进度持久化

- 每 3 秒通过 `player.currentPosition` 写入 Room `play_history` 表。
- 进入播放器时查询 `play_history.position_ms` 并 seek。

### 5.3 字幕模块

#### 5.3.1 整体管线

```
ExoPlayer 音频流
      │ MediaCodec 解码为 PCM
      ▼
AudioBuffer (16kHz, 16bit, mono)
      │ JNI → sherpa-onnx 流式 ASR
      ▼
识别文本片段（带时间戳）
      │
      ├─ 源语言 == 中文 → 直接展示
      │
      └─ 源语言 != 中文 → JNI → CTranslate2 翻译
                                    │
                                    ▼
                              翻译后中文文本
                                    │
                                    ▼
                          Compose Canvas 渲染字幕叠加层
```

#### 5.3.2 ASR JNI 接口

```kotlin
object AsrEngine {
    // 初始化 ASR 引擎，加载模型
    fun init(modelPath: String, language: String): Boolean

    // 送入音频数据，返回识别结果
    fun processAudio(pcmData: ShortArray): AsrResult?

    // 标记音频流结束，获取最终结果
    fun finalize(): AsrResult?

    // 释放资源
    fun release()
}

data class AsrResult(
    val text: String,           // 识别文本
    val isPartial: Boolean,     // 是否为中间结果（流式识别中尚未确认的部分）
    val language: String,       // 检测到的语言
    val startMs: Long,          // 该句在音频中的起始时间
    val endMs: Long             // 结束时间
)
```

#### 5.3.3 翻译 JNI 接口

```kotlin
object TranslateEngine {
    // 初始化翻译引擎
    fun init(modelPath: String, sourceLang: String, targetLang: String): Boolean

    // 翻译单句文本
    suspend fun translate(text: String): String

    // 释放资源
    fun release()
}
```

#### 5.3.4 模型管理

- 模型文件从自建 CDN 下载（通过 `DownloadManager` 或应用内 HTTP 下载）。
- 下载时展示进度，支持断点续传。
- 模型校验：下载完成后校验 SHA256。
- 模型存放路径：`{app_private}/models/`。

#### 5.3.5 流式识别时序

```
音频时间轴 ────────────────────────────────────────►

   chunk[0]    chunk[1]    chunk[2]    chunk[3]    ...
     │            │            │            │
     ▼            ▼            ▼            ▼
  ASR 处理    ASR 处理    ASR 处理    ASR 处理
     │            │            │            │
     ▼            ▼            ▼            ▼
 partial_0    partial_1    final_0      partial_2
 ("我")       ("我们今")   ("我们今天")  ("来讨论")
                                          │
                                    翻译 + 渲染到 Canvas
```

- 每个音频 chunk 约 200ms，送入 ASR 后可能返回 partial（中间结果）或 final（最终确认）结果。
- final 结果触发翻译（如需要），翻译完成后更新字幕显示。
- 端到端延迟目标：音频到达 → 字幕显示 ≤ 1s。

### 5.4 迅雷视频扫描导入模块

#### 5.4.1 扫描流程

```
用户点击"扫描迅雷视频"
      │
      ▼
遍历预设路径列表（详见 PRD F4.1）下的所有文件
      │
      ▼
过滤：扩展名为视频格式 AND 文件大小 ≥ 1MB
      │
      ▼
展示扫描结果列表（文件名、大小、原路径）
      │
      ▼
用户选择「复制」或「移动」→ 确认执行
      │
      ▼
串行逐文件处理（PRD F4.3 五步安全流程）
      │
      ▼
展示结果摘要：成功 X 个，失败 Y 个
```

#### 5.4.2 安全导入实现

```kotlin
suspend fun importFile(
    sourceFile: File,
    destDir: File,
    mode: ImportMode  // COPY or MOVE
): Result<File> = withContext(Dispatchers.IO) {
    try {
        // Step 1: 复制到目标目录（临时文件名）
        val tmpFile = File(destDir, "${sourceFile.name}.tmp")
        sourceFile.copyTo(tmpFile, overwrite = true)

        // Step 2: 校验大小
        if (tmpFile.length() != sourceFile.length()) {
            tmpFile.delete()
            return@withContext Result.failure(ImportException("大小校验失败"))
        }

        // Step 3: 重命名为正式文件名
        val destFile = File(destDir, sourceFile.name)
        tmpFile.renameTo(destFile)

        // Step 4: 移动模式下删除原文件
        if (mode == ImportMode.MOVE) {
            sourceFile.delete()
        }

        Result.success(destFile)
    } catch (e: Exception) {
        // Step 5: 清理临时文件，原文件不动
        File(destDir, "${sourceFile.name}.tmp").delete()
        Result.failure(e)
    }
}
```

#### 5.4.3 路径访问

- Android 10+ 使用 Scoped Storage，通过 `MediaStore` API 读取公共目录文件。
- 对于 `/Android/data/` 下的其他应用目录，需使用 SAF (`ACTION_OPEN_DOCUMENT_TREE`) 引导用户授权。首次使用时弹出系统文件夹选择器，引导用户选择对应目录授权。

### 5.5 设置模块

#### 5.5.1 存储空间统计

```kotlin
suspend fun getStorageStats(): StorageStats {
    val videosSize = File(videoDir).walkTopDown().sumOf { it.length() }
    val modelsSize  = File(modelDir).walkTopDown().sumOf { it.length() }
    val cacheSize   = File(cacheDir).walkTopDown().sumOf { it.length() }
    val dbSize      = context.getDatabasePath("magplay.db").length()
    return StorageStats(videosSize, modelsSize, cacheSize, dbSize)
}
```

---

## 6. 关键数据流

### 6.1 下载状态流

```
libtorrent (C++) ──每秒状态回调──► JNI Bridge ──► Kotlin Flow
                                                        │
                                              ┌─────────┴──────────┐
                                              ▼                    ▼
                                     ForegroundService      DownloadViewModel
                                     (通知栏更新)            (UI 进度更新)
                                              │
                                              ▼
                                    Room DB (每 5 秒写入)
```

### 6.2 播放 + 字幕流

```
ExoPlayer ──音频 PCM──► ASR Engine (sherpa-onnx) ──text──► Translate Engine (CTranslate2)
     │                                                            │
     │                                                            ▼
     ▼                                                      MutableStateFlow<String>
MediaCodec 视频解码                                              │
     │                                                            ▼
     ▼                                                      SubtitleOverlay
SurfaceView / TextureView                                (Compose Canvas 叠加)
```

### 6.3 App 生命周期与下载

```
Activity onCreate
      │
      ▼
bindService(DownloadService) ──► DownloadService 独立进程
      │                                │
      ▼                                ▼
Flow collect                     libtorrent Session
更新 UI                          持续运行
      │
Activity onDestroy
      │
      ▼
unbindService ──► DownloadService 继续运行（Foreground）
```

---

## 7. 性能设计

### 7.1 下载引擎

- libtorrent 的磁盘缓存设为 256MB，减少频繁 IO。
- DHT 节点持久化缓存到文件，下次启动时快速恢复 DHT 路由表。
- 选择性下载机制本身避免无效 IO。

### 7.2 播放器

- ExoPlayer 默认开启硬件解码（`DefaultRenderersFactory`）。
- 4K 视频软解时，使用 ExoPlayer 的 `DefaultLoadControl` 调低缓冲时长，控制内存。
- 通过 `TrackSelector` 限制最大分辨率，低端设备自动降级。

### 7.3 字幕

- ASR 模型使用 INT8 量化版本，降低推理内存和 CPU 占用。
- 音频重采样到 16kHz mono 后送入 ASR，减少计算量。
- 翻译模型使用 CTranslate2 的 INT8 量化 + 批量处理（攒 2-3 句后批量翻译）。
- 关掉字幕时完全释放 native 资源（`release()`），不占用 CPU。

### 7.4 内存预算（1080p 播放 + 字幕场景）

| 组件 | 预估内存 |
|---|---|
| ExoPlayer（硬解） | ~80 MB |
| ASR 模型（INT8 Whisper small） | ~150 MB |
| 翻译模型（INT8 Opus-MT） | ~70 MB |
| UI (Compose) | ~50 MB |
| 其他 | ~50 MB |
| **合计** | **≈ 400 MB** |

---

## 8. 安全与隐私

### 8.1 Android 权限

| 权限 | 用途 | 必要性 |
|---|---|---|
| `INTERNET` | P2P 下载、模型下载 | 必须 |
| `FOREGROUND_SERVICE` | 后台下载 | 必须 |
| `POST_NOTIFICATIONS` | 下载完成通知 | 必须 |
| `ACCESS_NETWORK_STATE` | 检测网络状态 | 必须 |
| `WAKE_LOCK` | 后台下载时保持 CPU | 必须 |
| `RECEIVE_BOOT_COMPLETED` | 开机恢复下载任务 | 可选 |

> 明确**不申请**的权限：`READ_EXTERNAL_STORAGE`、`READ_MEDIA_*`（通过 SAF/MediaStore 按需访问）、`ACCESS_FINE_LOCATION`、`READ_PHONE_STATE` 等。

### 8.2 数据隔离

- 所有用户数据（视频、数据库、模型）存储在 `context.filesDir` 和 `context.externalMediaDirs` 下。
- `android:requestLegacyExternalStorage="false"`，遵循 Scoped Storage。
- 网络通信仅限 DHT/Tracker P2P 协议端口，不发起任何 HTTP 遥测或上报请求。

### 8.3 代码层面

- 不集成任何广告 SDK、统计 SDK、Crash 上报 SDK。
- ProGuard/R8 混淆发布版本。
- Native 库编译时开启 `-fstack-protector`、`-D_FORTIFY_SOURCE=2`。

---

## 9. 构建配置

### 9.1 目标版本

| 配置 | 值 |
|---|---|
| minSdk | 26 (Android 8.0) |
| targetSdk | 35 (Android 15) |
| compileSdk | 35 |
| NDK | 27.x |
| Kotlin | 2.0+ |
| AGP | 8.x |

### 9.2 ABI 支持

```
ndk {
    abiFilters "arm64-v8a", "armeabi-v7a"
}
```

x86 和 x86_64 不在支持范围内（PRD 要求仅 ARM/ARM64）。

### 9.3 Native 预编译策略

- libtorrent、sherpa-onnx、CTranslate2 均需预编译为 Android 平台的 `.so`。
- 通过 CMake + NDK 工具链交叉编译，产物通过 `jniLibs` 集成。
- 模型文件 (.onnx / .bin) 作为 assets 或首次启动时下载。

---

## 10. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|---|---|---|
| libtorrent JNI 复杂度高 | 开发周期延长 | 优先封装核心 API（添加链接、文件选择、状态查询），高级功能后续迭代 |
| ASR 在低端设备实时性不足 | 字幕延迟超标 | 提供轻量模型选项（Whisper tiny vs small）；CPU 低于 4 核时自动切换到轻量模型 |
| 翻译模型体积大 | 首次下载时间长 | 仅按需下载所需语言对的模型（默认仅英→中）；使用量化版本降低体积 |
| Android 分区存储限制访问迅雷目录 | 扫描失败 | 通过 SAF 授权机制引导用户；无法授权的路径在扫描结果中标注"无权限" |
| DHT 网络在中国大陆连通性问题 | 下载速度慢 | libtorrent 内置 DHT bootstrap 节点；可选配置 Tracker 列表作为补充 |
| 死种资源无法下载（迅雷可通过 P2SP 服务器缓存兜底） | 用户对比迅雷后认为 MagPlay 能力不足 | 在下载页提供说明入口解释 P2P 原理；超时后主动建议用户检查资源有效性，避免让用户误以为是 App 问题 |

---

## 11. 用户体验设计要点

产品的成败往往不在技术，而在用户感知。以下是在技术设计阶段就需要考虑的体验问题及其设计决策。

### 11.1 首次启动：空状态焦虑

**问题**：用户安装 App 后打开，页面全是空的，不知道该做什么，3 秒内就可能卸载。

**设计决策**：
- 视频列表页（主页）空状态不展示"暂无视频"这种无意义文案，而是直接展示一个磁力链接输入区 + 粘贴按钮，文案引导："粘贴磁力链接开始下载"。
- 首次启动检测剪贴板，若有磁力链接则弹出 BottomSheet 询问是否一键添加，减少用户操作步骤。
- 底部导航栏保持可见（下载 / 视频 / 设置），让用户感知 App 的结构。

### 11.2 死种与慢速：信任危机

**问题**：用户粘贴了磁力链接，等了很久没速度或完全没有 Peer，第一反应是"App 坏了"，而不是"这个种子没资源"。

**设计决策**：
- 元数据解析阶段展示具体状态文案，避免用户对着"加载中"干等：
  - "正在通过 DHT 网络查找种子信息..."
  - "已找到种子，正在连接 Peer..."
  - "当前连接 0 个 Peer，该资源可能较为冷门"
- 超过 30 秒元数据解析仍未完成时，主动提示："解析超时，该磁力链接可能已失效或网络受限，建议检查链接或稍后重试"。
- 下载中速度长时间为 0 时，提示："暂无可用 Peer，可能是资源冷门或网络受限，App 会持续尝试连接"。
- 将 Peer 数量、DHT 节点数等指标以用户可理解的方式展示（如"已连接 5 个分享者"而非"Peers: 5"）。

### 11.3 字幕模型下载：最大的体验断点

**问题**：用户兴致勃勃打开视频、点字幕按钮，结果弹出一个 150MB 的模型下载，用户可能直接放弃。

**设计决策**：
- **触发时机前置**：不等到用户点字幕按钮才下载。在视频列表页放置一个常驻的"字幕模型"入口，让用户提前感知并下载。
- 首次进入播放器时，若模型未下载，字幕按钮上显示一个小红点或标识，点击后弹出说明卡片而非直接开始下载：
  - 卡片内容：简要说明字幕功能（设备端实时识别，无需联网）、模型大小、建议在 WiFi 下使用。
  - 两个按钮："立即下载"（高亮）和"稍后再说"。
- 模型下载使用系统 `DownloadManager`，即使 App 退到后台也能继续。
- 下载进度在设置页面展示，下载完成后通知栏推送提醒。

### 11.4 复杂手势的发现性

**问题**：长按倍速、左右滑亮度/音量、长按+滑动切换倍速档位，这些手势用户很难自己发现。

**设计决策**：
- 首次进入播放器时，在屏幕中央叠加半透明手势引导提示，3 秒后自动消失，不在后续播放中重复出现：
  - 左侧图标提示"上下滑动调节亮度"
  - 右侧图标提示"上下滑动调节音量"
  - 中央底部提示"长按临时倍速播放，左右滑动切换倍速"
- 在设置页面的"播放设置"中提供"手势操作说明"入口，以图文展示所有手势。
- 锁屏按钮放在播放控制栏右侧显眼位置，点击后有短暂的 toast 提示"屏幕已锁定/解锁"。

### 11.5 字幕准确性的预期管理

**问题**：ASR 准确率约 90%+，意味着每 10 个字就可能有 1 个错字。如果用户期望的是 YouTube 级别的字幕质量（YouTube 用的是服务端超大模型），就会觉得产品很烂。

**设计决策**：
- 字幕首次开启时，在字幕区域上方短暂显示一行提示（5 秒后自动消失）："字幕由设备端 AI 实时生成，可能不完美"。
- 用户可在设置中永久关闭该提示。
- 不要在 UI 上使用"AI 字幕"这类夸大词汇，用"实时语音字幕"更准确。
- 字幕文本样式上，对于置信度低于阈值的识别结果，使用稍淡的颜色显示（暗示不确定性）。

### 11.6 存储空间焦虑

**问题**：App 同时存储视频文件和 AI 模型，动辄几十 GB。用户在系统设置里看到 App 占用巨大空间时会产生负面情绪。

**设计决策**：
- 设置页面的存储展示要细分（视频 / 模型 / 缓存 / 其他），让用户清楚知道空间用在哪。
- 下载前检测可用空间，若剩余空间少于选中文件的 1.2 倍，弹窗警告："存储空间不足，下载可能失败。当前可用 X GB，需要 Y GB"。
- 视频列表项展示文件大小，让用户有删除的决策依据。
- 缓存清理功能要展示清理项的具体内容（如"3 个已删除任务的残留文件，共 150MB"），而非一个模糊的"清理缓存"按钮。

### 11.7 网络切换的下载保护

**问题**：用户在 WiFi 下开始下载，出门后自动切到移动数据，App 静默消耗大量流量。虽然 PRD 要求弹窗确认才开始移动数据下载，但已经开始的下载在切换网络后如何处理？

**设计决策**：
- 监听网络状态变化（`ConnectivityManager`）。从 WiFi 切到移动数据时，自动**暂停**所有下载任务，在通知栏显示"已暂停 X 个下载（已切换到移动数据）"，通知附带"继续下载"按钮。
- 用户点击"继续下载"时，弹出确认对话框说明当前任务剩余大小。
- 该行为在设置中提供开关："移动数据下自动暂停下载"（默认开启）。

### 11.8 SAF 授权引导的用户困惑

**问题**：扫描迅雷视频时，Android 系统弹出 SAF 文件夹选择器，普通用户看到系统原生 UI 会困惑，不知道要选什么。

**设计决策**：
- 在触发 SAF 之前，先在 App 内显示一个引导卡片，用截图 + 箭头示意用户应该选择哪个文件夹，文案为："请在弹出的系统窗口中，选择以下文件夹以授权访问"。
- 对于 Android 11+ `/Android/data/` 目录已受保护无法直接通过 SAF 访问的情况，在扫描结果中标注"受系统保护，无法访问"，避免用户认为是 App 的问题。

### 11.9 多文件种子的选择体验

**问题**：种子内可能包含几十甚至上百个文件，文件名混乱（如 `01.mp4`、`sample.mp4`、广告文件等），用户难以判断哪些是需要的内容。

**设计决策**：
- 文件列表默认按文件大小降序排列，视频文件置顶，非视频文件折叠到"其他文件"分组。
- 视频文件自动勾选，非视频文件默认不勾选（PRD 已定义）。
- 小于 50MB 的视频文件标记为"可能为预告片或样片"，默认不勾选。
- 文件名过长的做省略处理，长按可查看完整路径。

### 11.10 倍速手势的误触

**问题**：用户在拖动进度条或正常操作时可能误触发长按倍速，造成播放速率突然变化，体验突兀。

**设计决策**：
- 长按触发倍速的延迟设为 400ms（而非系统默认的 `ViewConfiguration.getLongPressTimeout()` 即 500ms），在响应速度和防误触之间取平衡。
- 倍速激活时，在屏幕中央短暂显示倍速指示器（如"1.5x"大字），0.5 秒后渐隐，确保用户明确感知到倍速已激活。
- 松手恢复正常速度时也显示"1.0x"指示器 0.3 秒后消失，与激活时的动效形成对称反馈。

### 11.11 导入移动模式的空间预检

**问题**：移动模式下，安全导入流程需要先复制再删原文件，意味着需要 2 倍临时空间。若存储空间不足，复制中途失败，体验很差。

**设计决策**：
- 导入前先扫描目标存储分区的剩余空间，若所有选中文件的体积之和超过剩余空间，直接拒绝移动模式（按钮置灰 + 提示空间不足），只允许复制模式或分批操作。
- 复制模式下同样做空间预检，展示"需要约 X GB 空间，当前可用 Y GB"。

### 11.12 下载完成的文件定位

**问题**：用户下载完成后，从通知栏点进来，若直接跳转到视频列表，可能找不到刚下载的文件（列表排序方式不同导致文件不在预期的位置）。

**设计决策**：
- 下载完成的通知点击后，直接跳转到视频列表页，并将刚完成的视频项**高亮闪烁**（短暂背景色变化动画），方便用户定位。
- 下载完成时同时弹出 Snackbar："XXX.mp4 下载完成"，附带"立即播放"按钮。

### 11.13 下载任务的批量操作（参照迅雷）

**问题**：用户可能同时添加了十几个磁力链接，想一次性删除所有失败任务或暂停所有等待中的任务，逐条操作效率极低。

**设计决策**：
- 下载列表页长按任意任务项进入**多选模式**，每个任务项左侧出现勾选框。
- 多选模式下底部浮现操作栏：批量暂停 / 批量继续 / 批量删除。
- 操作栏提供"全选"快捷按钮，同时支持按状态快捷全选（如"全选失败任务"）。
- 退出多选模式：点击返回键或点击顶部"取消"按钮。

### 11.14 下载任务的时间分组（参照迅雷）

**问题**：下载任务列表扁平排列，任务多了之后杂乱无章，用户难以区分新旧任务。

**设计决策**：
- 下载列表按时间分组展示：今天 / 昨天 / 本周 / 更早。
- 每个分组显示折叠/展开箭头，默认全部展开。
- 分组头部显示该组的任务数量和总大小。

### 11.15 滑动删除（参照 115）

**问题**：删除一个下载任务或视频需要长按→点删除→确认，路径太长。

**设计决策**：
- 下载列表和视频列表均支持**左滑删除**：
  - 左滑露出红色"删除"按钮，再次点击确认删除（防止误操作）。
  - 删除下载任务时弹出底部确认弹窗，询问是否同时删除已下载的文件。
- 被左滑删除的项提供 Snackbar 撤回能力（3 秒内可点"撤销"恢复）。

### 11.16 通知栏快捷控制

**问题**：用户在游戏或看其他内容时想暂停下载，需要切换到 MagPlay App 再操作，中断当前活动。

**设计决策**：
- Foreground Service 通知栏不仅展示进度，还内置**暂停/继续**按钮（类似音乐播放器的通知栏控制）。
- 通知栏展示当前活跃任务数 + 总下载速度汇总，而非仅展示单个任务。
- 下载全部完成时，通知自动切换为"X 个下载已完成"的静态通知（不带按钮），5 秒后自动消失。

### 11.17 播放器内的视频信息面板

**问题**：用户想知道当前播放视频的详细信息（分辨率、编码、文件大小），在播放器内无处查看。

**设计决策**：
- 播放器控制栏提供信息按钮（"i"图标），点击后从底部滑出一个信息面板：
  - 文件名、文件大小、分辨率、视频编码、音频编码、帧率、总时长。
- 面板半透明覆盖在视频画面底部 40% 区域，点击面板外区域关闭。

### 11.18 多音轨视频的音轨切换

**问题**：部分视频（尤其是双语电影）包含多条音轨（如英文原声 + 中文配音），用户无法切换，只能听默认音轨。

**设计决策**：
- 播放器检测到视频包含多条音轨时，在控制栏显示音轨切换按钮（耳机图标）。
- 点击弹出音轨列表（显示语言标签，如"English"、"中文"），当前选中项高亮。
- 单音轨视频不显示此按钮，避免引入无用控件。

### 11.19 打开方式：视频文件关联播放

**问题**：用户在文件管理器或其他 App 中看到视频文件，想直接用 MagPlay 播放以使用实时字幕功能，但 MagPlay 没有出现在系统"打开方式"列表中。

**设计决策**：
- 在 AndroidManifest 中为播放器 Activity 注册 `<intent-filter>`，声明 `ACTION_VIEW`，并匹配 PRD 中所有视频 MIME 类型（`video/mp4`、`video/x-matroska`、`video/avi`、`video/quicktime`、`video/x-ms-wmv`、`video/x-flv`、`video/webm`、`video/mp2t`、`video/3gpp`）。
- 用户从文件管理器点击视频 → 系统弹出"打开方式"列表 → MagPlay 出现在列表中 → 选择后直接进入播放器。
- 若该视频已在 MagPlay 的 play_history 中有记录，自动恢复上次播放进度（断续播放对用户在外部打开的视频同样生效）。
- 从外部打开的视频**不会**被自动复制或导入到 MagPlay 视频目录，仅在播放器中临时播放。这样做的好处是不打扰文件管理器原本的组织方式，用户只是想用 MagPlay 的字幕功能看一次而已。

### 11.20 存储空间不足时的智能建议

**问题**：用户下载新视频时空间不足，被迫手动去清理，但不知道该删什么。

**设计决策**：
- 空间不足弹窗中，除提示"需要 X GB，可用 Y GB"外，列出清理建议：
  - "3 个已完成下载且已观看的视频，释放约 Z GB"（查询 play_history 中已看完的视频 + 对应文件大小）。
  - "字幕模型未下载的临时缓存，释放约 W MB"。
- 每个建议项附带勾选框，用户勾选后一键清理，清理完成自动继续下载。

---

> **文档状态说明**：v0.4，第 11 章精简至 20 个 UX 场景。删除 11.17（列表/网格切换）、11.18（缩略图）、11.23（应用内更新）；11.21 分享入口改为 11.19 打开方式（视频文件关联播放）。
