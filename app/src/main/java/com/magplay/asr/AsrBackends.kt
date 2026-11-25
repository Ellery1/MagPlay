package com.magplay.asr

import android.content.Context
import java.io.File

/**
 * 可切换的离线识别后端。
 *
 * 模型文件放外部私有目录 `Android/data/com.magplay/files/models/<id>/`，
 * 由 `scripts/fetch_asr_models.sh` 下载后 adb 推入；assets 里只保留 Paraformer 兜底。
 * 这样换模型不需要重装 APK —— 一个 1GB 的模型塞进 assets 会让安装和更新都变得很难受。
 */
enum class AsrBackend(
    val id: String,
    val label: String,
    val note: String,
    val sizeMb: Int,
    /** assets 下的兜底目录；null 表示只能从外部目录加载。 */
    val assetDir: String?,
    val files: List<String>,
) {
    PARAFORMER(
        id = "paraformer",
        label = "Paraformer",
        note = "内置 · 快 · 无标点",
        sizeMb = 228,
        assetDir = "models/asr/paraformer",
        files = listOf("model.int8.onnx", "tokens.txt"),
    ),
    FIRERED_CTC(
        id = "firered_ctc",
        label = "FireRedASR2 CTC",
        note = "740MB · 精度高一档 · 支持 20+ 方言 · 速度快",
        sizeMb = 740,
        assetDir = null,
        files = listOf("model.int8.onnx", "tokens.txt"),
    ),
    FIRERED_AED(
        id = "firered_aed",
        label = "FireRedASR2 AED",
        note = "1.2GB · 精度更高 · 慢约 3 倍",
        sizeMb = 1180,
        assetDir = null,
        files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"),
    ),
    QWEN3(
        id = "qwen3",
        label = "Qwen3-ASR 0.6B",
        note = "980MB · LLM 级精度最高 · 最慢",
        sizeMb = 980,
        assetDir = null,
        files = listOf(
            "conv_frontend.onnx",
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            "tokenizer/vocab.json",
            "tokenizer/merges.txt",
            "tokenizer/tokenizer_config.json",
        ),
    ),
    ;

    companion object {
        fun fromId(id: String?): AsrBackend =
            entries.firstOrNull { it.id == id } ?: PARAFORMER
    }
}

/** 模型文件定位：外部目录优先，assets 兜底。 */
object AsrModels {

    /**
     * 模型根目录：**App 内部私有目录** `files/models`。
     *
     * 为什么不用外部 `Android/data/.../files`：adb push 建的目录和文件属主是 shell，
     * App（uid 10403）连目录都进不去，sherpa-onnx 读 tokens.txt 会失败并**直接硬退出进程**
     * （logcat: `Read binary file: Load ... failed` → exit 255）。
     * 走内部目录后由 App 自己拥有，`run-as` 也能写入，见 dist/push_models.sh。
     */
    fun root(context: Context): File = File(context.filesDir, "models")

    fun dirOf(context: Context, backend: AsrBackend): File = File(root(context), backend.id)

    /** 该后端是否可用（外部目录文件齐，或 assets 内置）。 */
    fun isAvailable(context: Context, backend: AsrBackend): Boolean {
        val dir = dirOf(context, backend)
        if (backend.files.all { File(dir, it).isFile && File(dir, it).length() > 0 }) return true
        val assetDir = backend.assetDir ?: return false
        return runCatching {
            backend.files.all { rel -> context.assets.open("$assetDir/$rel").use { true } }
        }.getOrDefault(false)
    }

    /** 已就绪的后端列表（用于 UI 展示）。 */
    fun available(context: Context): List<AsrBackend> =
        AsrBackend.entries.filter { isAvailable(context, it) }

    /**
     * 拿到某个模型文件的路径。
     * 外部目录存在 → 返回绝对路径（sherpa-onnx 见到以 / 开头就按文件系统读）；
     * 否则返回 assets 相对路径。
     */
    fun path(context: Context, backend: AsrBackend, rel: String): String? {
        val f = File(dirOf(context, backend), rel)
        if (f.isFile && f.length() > 0) return f.absolutePath
        val assetDir = backend.assetDir ?: return null
        return "$assetDir/$rel"
    }

    /**
     * 该后端的模型是否落在文件系统（而非 assets）。
     * 是的话给 sherpa-onnx 传 null AssetManager，见 [com.magplay.asr.NullAssets]。
     */
    fun onFileSystem(context: Context, backend: AsrBackend): Boolean =
        File(dirOf(context, backend), backend.files.first()).isFile

    fun sizeMbOnDisk(context: Context, backend: AsrBackend): Long {
        val dir = dirOf(context, backend)
        if (!dir.isDirectory) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
    }
}
