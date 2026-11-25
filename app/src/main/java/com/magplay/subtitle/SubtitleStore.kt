package com.magplay.subtitle

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.magplay.data.VideoItem
import java.io.File

/**
 * 字幕文件存取：视频同目录同名 .srt。
 * 通过 MediaStore.Files 在公共目录写入/查找 .srt，无需 SAF 授权（RELATIVE_PATH 指向视频所在目录）。
 */
object SubtitleStore {

    private const val MIME_SRT = "application/x-subrip"

    /** 视频对应的字幕文件名：去掉视频扩展名 + .srt。 */
    fun srtName(videoName: String): String {
        val dot = videoName.lastIndexOf('.')
        val base = if (dot > 0) videoName.substring(0, dot) else videoName
        return "$base.srt"
    }

    /** 该视频是否已有匹配的字幕文件。 */
    fun exists(context: Context, video: VideoItem): Boolean =
        findSrtUri(context, video) != null

    /** 查找同目录同名 .srt，返回其 content:// Uri；无则 null。 */
    fun findSrtUri(context: Context, video: VideoItem): Uri? {
        val name = srtName(video.name)
        val rel = video.relativePath

        // 1) 精确匹配文件名（同名即可，跨目录同名的概率低；再加 relativePath 过滤更稳）
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && rel.isNotBlank()) {
            "${MediaStore.Files.FileColumns.DISPLAY_NAME}=? AND ${MediaStore.Files.FileColumns.RELATIVE_PATH}=?"
        } else {
            "${MediaStore.Files.FileColumns.DISPLAY_NAME}=?"
        }
        val args = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && rel.isNotBlank()) {
            arrayOf(name, ensureTrailingSlash(rel))
        } else {
            arrayOf(name)
        }
        try {
            val cursor = context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection, selection, args, null,
            ) ?: return null
            cursor.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                    return MediaStore.Files.getContentUri("external").buildUpon()
                        .appendPath(id.toString()).build()
                }
            }
        } catch (_: Throwable) {}

        // 2) 兜底：直接 Files 路径（某些设备/目录 MediaStore 未索引 .srt）
        if (rel.isNotBlank()) {
            val direct = File(File("/storage/emulated/0", rel), name)
            if (direct.exists()) return Uri.fromFile(direct)
        }

        // 3) 兜底：App 私有字幕目录（DCIM/Camera 等目录不允许用 Files 集合写非媒体文件）
        val priv = privateSrtFile(context, video)
        if (priv.exists()) return Uri.fromFile(priv)

        return null
    }

    /**
     * 私有兜底路径。DCIM/Camera、Pictures 等目录在 Android 11+ 不允许通过
     * MediaStore.Files 插入 .srt（非媒体文件），只能退回 App 私有目录。
     * 用 relativePath+文件名 的哈希命名，避免同名不同目录冲突。
     */
    private fun privateSrtFile(context: Context, video: VideoItem): File =
        privateSrtFile(context, video.relativePath, video.name)

    private fun privateSrtFile(context: Context, relPath: String, name: String): File {
        val dir = File(context.getExternalFilesDir(null), "subtitles")
        if (!dir.exists()) dir.mkdirs()
        val key = (relPath + "/" + name).toByteArray(Charsets.UTF_8)
        val hash = java.security.MessageDigest.getInstance("MD5")
            .digest(key).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.srt")
    }

    /** 私有兜底目录里该视频的字幕文件（不存在返回 null），删除视频时用。 */
    fun privateSrtFileOrNull(context: Context, video: VideoItem): File? =
        privateSrtFile(context, video).takeIf { it.exists() }

    /**
     * 视频移动/改名后，把私有兜底字幕挪到新键位（私有文件名是按 relativePath+文件名 哈希的）。
     * 公共目录里的字幕走 MediaStore 改 RELATIVE_PATH，不会走到这里。
     */
    fun relocatePrivateSrt(
        context: Context,
        old: VideoItem,
        newRelPath: String,
        newName: String,
    ) {
        val src = privateSrtFile(context, old)
        if (!src.exists()) return
        val dst = privateSrtFile(context, newRelPath, newName)
        try {
            if (dst.absolutePath == src.absolutePath) return
            dst.writeBytes(src.readBytes())
            src.delete()
        } catch (t: Throwable) {
            android.util.Log.w("SubtitleStore", "relocate private srt failed: ${t.message}")
        }
    }

    /** 读取字幕内容并解析。 */
    fun loadCues(context: Context, video: VideoItem): List<SrtCue>? {
        val uri = findSrtUri(context, video) ?: return null
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return null
            SrtFile.parse(text).takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            android.util.Log.w("SubtitleStore", "load cues failed: ${t.message}")
            null
        }
    }

    /**
     * 该视频是否已有"可用"的字幕：文件存在 + 内容通过时间轴/覆盖度校验。
     * 生成批次用它判断待办——坏文件不算数，会被重新生成覆盖。
     */
    fun hasUsable(context: Context, video: VideoItem): Boolean {
        val cues = loadCues(context, video) ?: return false
        return SrtFile.isUsable(cues, video.durationMs)
    }

    /**
     * 是否已有**由指定引擎**生成的可用字幕。
     *
     * 为什么要记引擎：换了更强的模型之后，旧模型生成的字幕在内容校验上是"可用"的，
     * 会被全量生成直接跳过 —— 结果就是新模型永远用不上。
     * 没有记录的老字幕（本功能上线前生成的）一律视为需要重做。
     */
    fun hasUsableForEngine(context: Context, video: VideoItem, engineId: String): Boolean =
        hasUsable(context, video) && engineOf(context, video) == engineId

    // ---- 引擎记录（srt_engine.json）----

    private fun engineFile(context: Context): File =
        File(context.getExternalFilesDir(null), "srt_engine.json")

    private fun engineKey(relativePath: String, name: String): String = "$relativePath|$name"

    private fun readEngineMap(context: Context): MutableMap<String, String> {
        val f = engineFile(context)
        if (!f.isFile) return mutableMapOf()
        return try {
            val obj = org.json.JSONObject(f.readText(Charsets.UTF_8))
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { k -> map[k] = obj.optString(k, "") }
            map
        } catch (_: Throwable) {
            mutableMapOf()
        }
    }

    @Synchronized
    private fun writeEngineMap(context: Context, map: Map<String, String>) {
        try {
            val obj = org.json.JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            engineFile(context).writeText(obj.toString(), Charsets.UTF_8)
        } catch (t: Throwable) {
            android.util.Log.w("SubtitleStore", "write engine map failed: ${t.message}")
        }
    }

    fun engineOf(context: Context, video: VideoItem): String? =
        readEngineMap(context)[engineKey(video.relativePath, video.name)]

    fun recordEngine(context: Context, video: VideoItem, engineId: String) {
        val map = readEngineMap(context)
        map[engineKey(video.relativePath, video.name)] = engineId
        writeEngineMap(context, map)
    }

    fun forgetEngine(context: Context, video: VideoItem) {
        val map = readEngineMap(context)
        if (map.remove(engineKey(video.relativePath, video.name)) != null) {
            writeEngineMap(context, map)
        }
    }

    /** 视频移动/改名后把引擎记录搬到新键位，避免白重做一遍。 */
    fun rekeyEngine(context: Context, old: VideoItem, newRelPath: String, newName: String) {
        val map = readEngineMap(context)
        val oldKey = engineKey(old.relativePath, old.name)
        val v = map.remove(oldKey) ?: return
        map[engineKey(newRelPath, newName)] = v
        writeEngineMap(context, map)
    }

    /**
     * 重新生成前清理：把现有字幕备份到私有目录后删除。
     * 用于"重新生成离线字幕"（先清空全部再全量重做），备份保证可回溯。
     */
    fun clearForRegenerate(context: Context, video: VideoItem): Boolean {
        val old = findSrtUri(context, video) ?: return false
        return try {
            backupIfInvalid(context, video, old, always = true)
            if (old.scheme == "file") File(old.path!!).delete()
            else context.contentResolver.delete(old, null, null) > 0
        } catch (t: Throwable) {
            android.util.Log.w("SubtitleStore", "clear failed: ${video.name} ${t.message}")
            false
        }
    }

    /**
     * 覆盖前把旧字幕备份到 App 私有目录（不影响用户公共目录）。
     * @param always true 时无论内容好坏都备份（重新生成场景）；false 时只备份校验不通过的坏文件。
     */
    private fun backupIfInvalid(context: Context, video: VideoItem, old: Uri, always: Boolean = false) {
        val bytes = try {
            context.contentResolver.openInputStream(old)?.use { it.readBytes() }
        } catch (_: Throwable) {
            null
        } ?: return
        if (!always) {
            val cues = SrtFile.parse(bytes.toString(Charsets.UTF_8))
            if (SrtFile.isUsable(cues, video.durationMs)) return
        }
        try {
            val dir = File(context.getExternalFilesDir(null), "backup_srt")
            if (!dir.exists()) dir.mkdirs()
            val suffix = if (always) ".bak.srt" else ".bad.srt"
            val dst = File(dir, srtName(video.name).removeSuffix(".srt") + suffix)
            dst.writeBytes(bytes)
            android.util.Log.w("SubtitleStore", "backup srt -> ${dst.absolutePath}")
        } catch (t: Throwable) {
            android.util.Log.w("SubtitleStore", "backup srt failed: ${t.message}")
        }
    }

    /**
     * 将 SRT 文本写到视频同目录。已存在则覆盖。
     * @return 写入后的 Uri，失败返回 null。
     */
    fun writeSrt(context: Context, video: VideoItem, content: String): Uri? {
        val name = srtName(video.name)
        val rel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ensureTrailingSlash(video.relativePath)
        } else {
            ""
        }

        // 若已存在，先备份坏文件再删除旧记录（避免重复）
        findSrtUri(context, video)?.let { old ->
            try {
                backupIfInvalid(context, video, old)
                if (old.scheme == "file") File(old.path!!).delete()
                else context.contentResolver.delete(old, null, null)
            } catch (_: Throwable) {}
        }

        // 先试同目录（视频同目录同名，用户首选方案）
        try {
            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, name)
                put(MediaStore.Files.FileColumns.MIME_TYPE, MIME_SRT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Files.FileColumns.RELATIVE_PATH, rel)
                    put(MediaStore.Files.FileColumns.IS_PENDING, 1)
                }
            }
            val uri = context.contentResolver.insert(
                MediaStore.Files.getContentUri("external"), values
            )
            if (uri != null) {
                val out = context.contentResolver.openOutputStream(uri)
                if (out != null) {
                    out.use {
                        it.write(content.toByteArray(Charsets.UTF_8))
                        it.flush()
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val done = ContentValues().apply { put(MediaStore.Files.FileColumns.IS_PENDING, 0) }
                        context.contentResolver.update(uri, done, null, null)
                    }
                    android.util.Log.i("SubtitleStore", "wrote $name to $rel")
                    return uri
                }
                context.contentResolver.delete(uri, null, null)
            }
        } catch (t: Throwable) {
            // DCIM/Camera 等目录会抛 "Primary directory ... not allowed"
            android.util.Log.w("SubtitleStore", "同目录写入失败($rel$name): ${t.message}")
        }

        // 兜底：App 私有目录
        return try {
            val f = privateSrtFile(context, video)
            f.writeText(content, Charsets.UTF_8)
            android.util.Log.i("SubtitleStore", "wrote $name to private: ${f.absolutePath}")
            Uri.fromFile(f)
        } catch (t: Throwable) {
            android.util.Log.e("SubtitleStore", "private write failed: ${video.name}", t)
            null
        }
    }

    private fun ensureTrailingSlash(path: String): String {
        if (path.isBlank()) return path
        return if (path.endsWith("/")) path else "$path/"
    }
}
