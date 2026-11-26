package com.magplay.data

import android.content.Context

/**
 * 视频播放进度（R8 断点续播）。
 *
 * key = MediaStore `_ID`：重命名 / 移动目录后 ID 不变，进度自动保留；
 * 文件被删掉后由 [prune] 清理。存储用 SharedPreferences，避免为本功能引数据库。
 */
object PlaybackProgress {
    private const val PREF = "play_progress"
    private const val SEP = "|"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(context: Context, id: Long, durationMs: Long): Long {
        val raw = prefs(context).getString(id.toString(), null) ?: return 0L
        val parts = raw.split(SEP)
        val pos = parts.getOrNull(0)?.toLongOrNull() ?: return 0L
        val dur = parts.getOrNull(1)?.toLongOrNull() ?: return 0L
        // 片长变了（视频被替换）或异常值 → 从头播
        if (pos <= 0L || dur <= 0L) return 0L
        if (durationMs > 0 && (pos >= durationMs || kotlin.math.abs(dur - durationMs) > 5000L)) return 0L
        return pos
    }

    fun save(context: Context, id: Long, positionMs: Long, durationMs: Long) {
        if (positionMs <= 0L) return
        prefs(context).edit()
            .putString(id.toString(), "$positionMs$SEP$durationMs")
            .apply()
    }

    fun clear(context: Context, id: Long) {
        prefs(context).edit().remove(id.toString()).apply()
    }

    /** 扫描后清理已不存在 / 已播完的视频记录。 */
    fun prune(context: Context, aliveIds: Set<Long>) {
        val p = prefs(context)
        val doomed = p.all.keys.filter { it.toLongOrNull() !in aliveIds }
        if (doomed.isEmpty()) return
        val editor = p.edit()
        doomed.forEach { editor.remove(it) }
        editor.apply()
    }
}
