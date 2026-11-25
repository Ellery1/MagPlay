package com.magplay.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class VideoItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val durationMs: Long,
    val relativePath: String,
    val dateAddedSec: Long,
)

object VideoScanner {

    private val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.RELATIVE_PATH,
        MediaStore.Video.Media.DATE_ADDED,
    )

    /** 扫描 MediaStore 索引到的公共目录视频（含 /Download/ThunderDownload/ 导入目录）。 */
    fun scan(context: Context): List<VideoItem> {
        val result = mutableListOf<VideoItem>()
        val cursor = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        ) ?: return result

        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                result += VideoItem(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                    name = c.getString(nameCol) ?: "未知文件",
                    size = c.getLong(sizeCol),
                    durationMs = c.getLong(durationCol),
                    relativePath = c.getString(pathCol) ?: "",
                    dateAddedSec = c.getLong(dateCol),
                )
            }
        }
        return result
    }

    /**
     * 按 id 取单个视频。
     *
     * 单条字幕生成不能走 [scan] 再筛（那样等于扫全量），直接带 `_ID=?` 查询，
     * 一次 cursor 就够，几十毫秒返回。
     */
    fun findById(context: Context, id: Long): VideoItem? {
        val cursor = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Video.Media._ID}=?",
            arrayOf(id.toString()),
            null,
        ) ?: return null
        cursor.use { c ->
            if (!c.moveToFirst()) return null
            return VideoItem(
                id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)),
                uri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id,
                ),
                name = c.getString(c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)) ?: "未知文件",
                size = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)),
                durationMs = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)),
                relativePath = c.getString(c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)) ?: "",
                dateAddedSec = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)),
            )
        }
    }
}
