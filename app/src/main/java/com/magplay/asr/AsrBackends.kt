package com.magplay.asr

import android.content.Context
import java.io.File

/**
 * 唯一的离线识别后端：FireRedASR2 AED。
 *
 * 模型文件放 App 内部私有目录 `files/models/firered_aed/`，由 App 内的下载器安装，
 * 或从本机 `dist/push_models.sh` 推入。assets 里不再内置任何 ASR 模型。
 */
enum class AsrBackend(
    val id: String,
    val label: String,
    val note: String,
    val sizeMb: Int,
    /** assets 下的兜底目录；AED 只能从内部目录加载。 */
    val assetDir: String?,
    val files: List<String>,
) {
    FIRERED_AED(
        id = "firered_aed",
        label = "FireRedASR2 AED",
        note = "1.2GB · 中文准确率优先",
        sizeMb = 1180,
        assetDir = null,
        files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"),
    ),
    ;

    companion object {
        const val DEFAULT_ID = "firered_aed"

        fun fromId(id: String?): AsrBackend =
            entries.firstOrNull { it.id == id } ?: FIRERED_AED
    }
}

/** 模型文件定位：只从 App 内部 `files/models/` 读。 */
object AsrModels {

    /** App 内部私有目录，属主是 App，sherpa-onnx 直接读绝对路径。 */
    fun root(context: Context): File = File(context.filesDir, "models")

    fun dirOf(context: Context, backend: AsrBackend): File = File(root(context), backend.id)

    /** 该后端是否可用：三个文件都存在且非空。 */
    fun isAvailable(context: Context, backend: AsrBackend): Boolean {
        val dir = dirOf(context, backend)
        return backend.files.all { File(dir, it).isFile && File(dir, it).length() > 0 }
    }

    fun available(context: Context): List<AsrBackend> =
        AsrBackend.entries.filter { isAvailable(context, it) }

    /** 模型文件路径；缺失返回 null（调用方负责提示安装）。 */
    fun path(context: Context, backend: AsrBackend, rel: String): String? {
        val f = File(dirOf(context, backend), rel)
        return if (f.isFile && f.length() > 0) f.absolutePath else null
    }

    fun onFileSystem(context: Context, backend: AsrBackend): Boolean =
        File(dirOf(context, backend), backend.files.first()).isFile

    fun sizeMbOnDisk(context: Context, backend: AsrBackend): Long {
        val dir = dirOf(context, backend)
        if (!dir.isDirectory) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
    }
}
