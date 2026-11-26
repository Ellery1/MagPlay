package com.magplay.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.magplay.subtitle.GenAttempts
import com.magplay.subtitle.SubtitleStore
import java.io.File

/** 文件操作结果。 */
sealed interface FileOpResult {
    data class Ok(val message: String) : FileOpResult

    /** 视频是别的 App 创建的，系统要求用户确认一次写权限。 */
    data class NeedPermission(val uris: List<Uri>) : FileOpResult

    data class Failed(val message: String) : FileOpResult
}

/** 目录批量操作结果（R1）。 */
sealed interface DirOpResult {
    data class Ok(val count: Int) : DirOpResult
    data class NeedPermission(val uris: List<Uri>) : DirOpResult
    data class Failed(val message: String, val okCount: Int, val failCount: Int) : DirOpResult
}

/**
 * 视频文件的移动与删除（连同同名字幕）。
 *
 * Android 11+ 对「非本应用创建的媒体文件」不开放写操作，直接 update 会抛
 * RecoverableSecurityException。这里先试着直接改，失败就返回需要授权的 Uri 列表，
 * 由 UI 拉起系统授权弹窗（MediaStore.createWriteRequest / createDeleteRequest）。
 * 若用户在系统设置里给了「所有文件访问权限」（MANAGE_EXTERNAL_STORAGE），则不会再弹窗。
 */
object VideoFiles {

    /**
     * R1：重命名目录 —— 把 [dirPath] 及其子目录下所有视频移动到「父目录/新名」下。
     * 逐条复用 [move]（字幕跟随、引擎记录同步、重名自动加序号）。
     */
    fun renameDir(
        context: Context,
        allVideos: List<VideoItem>,
        dirPath: String,
        newName: String,
    ): DirOpResult {
        val name = newName.trim().trim('/')
        if (name.isEmpty() || name.contains('/')) {
            return DirOpResult.Failed("名称不能为空，也不能包含 /", 0, 0)
        }
        val old = dirPath.trim('/')
        if (old.isEmpty()) return DirOpResult.Failed("根目录不能重命名", 0, 0)
        val parent = old.substringBeforeLast('/', "")
        val newPrefix = if (parent.isEmpty()) name else "$parent/$name"

        val affected = allVideos.filter {
            val p = it.relativePath.trim('/')
            p == old || p.startsWith("$old/")
        }
        if (affected.isEmpty()) return DirOpResult.Ok(0)

        val uris = mutableListOf<Uri>()
        var ok = 0
        var fail = 0
        for (v in affected) {
            val rest = v.relativePath.trim('/').removePrefix(old).trimStart('/')
            val target = if (rest.isEmpty()) newPrefix else "$newPrefix/$rest"
            when (val r = move(context, v, target)) {
                is FileOpResult.Ok -> ok++
                is FileOpResult.NeedPermission -> uris.addAll(r.uris)
                is FileOpResult.Failed -> fail++
            }
        }
        return when {
            uris.isNotEmpty() -> DirOpResult.NeedPermission(uris)
            fail == 0 -> DirOpResult.Ok(ok)
            else -> DirOpResult.Failed("$fail 个文件失败（成功 $ok 个）", ok, fail)
        }
    }

    /** R1：删除目录 —— 删除 [dirPath] 及其子目录下所有视频（含同名 .srt）。 */
    fun deleteDir(
        context: Context,
        allVideos: List<VideoItem>,
        dirPath: String,
    ): DirOpResult {
        val old = dirPath.trim('/')
        if (old.isEmpty()) return DirOpResult.Failed("根目录不能删除", 0, 0)
        val affected = allVideos.filter {
            val p = it.relativePath.trim('/')
            p == old || p.startsWith("$old/")
        }
        if (affected.isEmpty()) return DirOpResult.Ok(0)

        val uris = mutableListOf<Uri>()
        var ok = 0
        var fail = 0
        for (v in affected) {
            when (val r = delete(context, v)) {
                is FileOpResult.Ok -> ok++
                is FileOpResult.NeedPermission -> uris.addAll(r.uris)
                is FileOpResult.Failed -> fail++
            }
        }
        return when {
            uris.isNotEmpty() -> DirOpResult.NeedPermission(uris)
            fail == 0 -> DirOpResult.Ok(ok)
            else -> DirOpResult.Failed("$fail 个文件失败（成功 $ok 个）", ok, fail)
        }
    }

    /** 移动视频到目标目录（RELATIVE_PATH 形式，如 Download/ThunderDownload/）。 */
    fun move(context: Context, item: VideoItem, targetDir: String): FileOpResult {
        val target = normalize(targetDir)
        if (target == normalize(item.relativePath)) {
            return FileOpResult.Failed("已在该目录，无需移动")
        }
        val srtUri = SubtitleStore.findSrtUri(context, item)
        val finalName = uniqueName(context, item.name, target)

        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, target)
                if (finalName != item.name) put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
            }
            val rows = context.contentResolver.update(item.uri, values, null, null)
            if (rows <= 0) return FileOpResult.Failed("系统拒绝移动（文件可能已被删除）")

            // 系统可能在我们改名之外再自动加序号（目标目录里已有同名条目），
            // 必须以回读到的真实文件名为准，否则视频和字幕名对不上，播放时找不到字幕。
            val actualName = readDisplayName(context, item.uri) ?: finalName
            moveSrt(context, item, srtUri, target, actualName)
            GenAttempts.forget(context, item)
            // 引擎记录跟着搬，避免换了名字就把已生成的字幕当"没做过"
            SubtitleStore.rekeyEngine(context, item, target, actualName)
            val dirName = target.trimEnd('/').substringAfterLast('/')
            FileOpResult.Ok(
                if (actualName != item.name) "已移动到 $dirName（重名，改名为 $actualName）"
                else "已移动到 $dirName",
            )
        } catch (se: SecurityException) {
            FileOpResult.NeedPermission(
                buildList {
                    add(item.uri)
                    if (srtUri?.scheme == "content") add(srtUri)
                },
            )
        } catch (t: Throwable) {
            FileOpResult.Failed("移动失败：${t.message ?: t::class.java.simpleName}")
        }
    }

    /** 物理删除视频，并删掉同名 .srt（公共目录或私有兜底目录）。 */
    fun delete(context: Context, item: VideoItem): FileOpResult {
        SubtitleStore.forgetEngine(context, item)
        val srtUri = SubtitleStore.findSrtUri(context, item)
        return try {
            val rows = context.contentResolver.delete(item.uri, null, null)
            if (rows <= 0) return FileOpResult.Failed("系统未删除（文件可能已不存在）")

            try {
                if (srtUri != null) {
                    if (srtUri.scheme == "file") srtUri.path?.let { File(it).delete() }
                    else context.contentResolver.delete(srtUri, null, null)
                }
                // 私有兜底目录里的同名字幕也要清掉（按哈希命名，与公共目录不同）
                SubtitleStore.privateSrtFileOrNull(context, item)?.delete()
            } catch (_: Throwable) {
            }
            GenAttempts.forget(context, item)
            FileOpResult.Ok(if (srtUri != null) "已删除视频和字幕" else "已删除视频")
        } catch (se: SecurityException) {
            FileOpResult.NeedPermission(
                buildList {
                    add(item.uri)
                    if (srtUri?.scheme == "content") add(srtUri)
                },
            )
        } catch (t: Throwable) {
            FileOpResult.Failed("删除失败：${t.message ?: t::class.java.simpleName}")
        }
    }

    // ---- 内部 ----

    private fun readDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Throwable) {
        null
    }

    /** 字幕跟随视频：公共目录里的改 RELATIVE_PATH，私有目录按新键位重命名。 */
    private fun moveSrt(
        context: Context,
        oldVideo: VideoItem,
        srtUri: Uri?,
        targetDir: String,
        newVideoName: String,
    ) {
        if (srtUri == null) return
        try {
            val newSrtName = SubtitleStore.srtName(newVideoName)
            if (srtUri.scheme == "content") {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, targetDir)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, newSrtName)
                }
                context.contentResolver.update(srtUri, values, null, null)
            } else {
                SubtitleStore.relocatePrivateSrt(context, oldVideo, targetDir, newVideoName)
            }
        } catch (_: Throwable) {
            // 字幕搬不过去不影响视频本身，下次生成会补一份
        }
    }

    private fun normalize(path: String): String {
        val p = path.trim().trimStart('/')
        if (p.isEmpty()) return ""
        return if (p.endsWith("/")) p else "$p/"
    }

    /** 目标目录里重名时自动加序号，避免覆盖别人的同名文件。 */
    private fun uniqueName(context: Context, name: String, targetDir: String): String {
        if (!nameExists(context, name, targetDir)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 2..99) {
            val candidate = "$base ($i)$ext"
            if (!nameExists(context, candidate, targetDir)) return candidate
        }
        return "$base-${System.currentTimeMillis()}$ext"
    }

    private fun nameExists(context: Context, name: String, targetDir: String): Boolean {
        val uri = MediaStore.Files.getContentUri("external")
        val sel = "${MediaStore.Files.FileColumns.DISPLAY_NAME}=? AND " +
            "${MediaStore.Files.FileColumns.RELATIVE_PATH}=?"
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Files.FileColumns._ID),
                sel,
                arrayOf(name, targetDir),
                null,
            )?.use { it.count > 0 } ?: false
        } catch (_: Throwable) {
            false
        }
    }
}
