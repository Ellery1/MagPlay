package com.magplay.subtitle

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 跨进程运行状态快照。
 *
 * 字幕生成跑在 `:subtitle` 独立进程（隔离 native 段错误），主进程读不到它的静态字段，
 * 因此用一个极小 json 文件做通道：服务侧节流写（2s 一次），UI 侧 1s 读一次。
 * 写入先落 .tmp 再 rename，避免读到半截文件。
 */
object GenStatus {

    data class Snapshot(
        val running: Boolean,
        val text: String,
        val wakeLock: Boolean,
        val updatedAt: Long,
        val ageMs: Long,
    )

    private fun file(context: Context): File? = try {
        val dir = File(context.getExternalFilesDir(null), "logs")
        if (!dir.exists()) dir.mkdirs()
        File(dir, "status.json")
    } catch (_: Throwable) {
        null
    }

    /** 服务侧调用：running=批次是否在跑，text=当前任务，wakeLock=是否持锁。 */
    fun write(context: Context, running: Boolean, text: String, wakeLock: Boolean) {
        val f = file(context) ?: return
        try {
            val json = JSONObject()
                .put("running", running)
                .put("text", text)
                .put("wake", wakeLock)
                .put("at", System.currentTimeMillis())
                .toString()
            val tmp = File(f.parentFile, "status.json.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(f)) {
                // 少数 ROM 上 rename 覆盖失败，退化为直接写
                f.writeText(json)
                tmp.delete()
            }
        } catch (_: Throwable) {
        }
    }

    /** UI 侧调用：读不到或损坏时返回 null（视为"无记录"）。 */
    fun read(context: Context): Snapshot? {
        val f = file(context) ?: return null
        return try {
            if (!f.exists()) return null
            val o = JSONObject(f.readText())
            val at = o.optLong("at", 0L)
            Snapshot(
                running = o.optBoolean("running", false),
                text = o.optString("text", ""),
                wakeLock = o.optBoolean("wake", false),
                updatedAt = at,
                ageMs = if (at > 0) System.currentTimeMillis() - at else Long.MAX_VALUE,
            )
        } catch (_: Throwable) {
            null
        }
    }
}
