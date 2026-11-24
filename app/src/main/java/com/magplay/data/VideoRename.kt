package com.magplay.data

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore

/** 通过 MediaStore 重命名视频（只改 DISPLAY_NAME，文件位置与后缀不变）。 */
object VideoRename {

    /**
     * @return true 成功；false 失败（无权限 / 文件不存在 / 系统限制）。
     */
    fun rename(context: Context, item: VideoItem, newDisplayName: String): Boolean {
        if (newDisplayName.isBlank()) return false
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, newDisplayName)
            }
            val rows = context.contentResolver.update(item.uri, values, null, null)
            rows > 0
        } catch (_: Exception) {
            false
        }
    }
}
