package com.magplay.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.collection.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 视频缩略图加载器：用 MediaStore.loadThumbnail 取系统生成的缩略图，
 * 内存 LRU 缓存避免滚动时重复解码。加载在 IO 线程完成，不阻塞 UI。
 */
object ThumbnailLoader {
    private const val THUMB_W = 160
    private const val THUMB_H = 160
    private val cache = LruCache<Long, ImageBitmap>(200)

    /** 同步取缓存（Compose 重组时立即拿已加载的图）。 */
    fun cached(id: Long): ImageBitmap? = cache.get(id)

    /** 后台加载缩略图并写入缓存，返回是否成功。 */
    suspend fun load(resolver: ContentResolver, item: VideoItem): Boolean = withContext(Dispatchers.IO) {
        cached(item.id)?.let { return@withContext true }
        try {
            val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.loadThumbnail(item.uri, Size(THUMB_W, THUMB_H), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    resolver, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null,
                )
            }
            if (bitmap != null) {
                cache.put(item.id, bitmap.asImageBitmap())
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }
}
