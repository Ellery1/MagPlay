package com.magplay.subtitle

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

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

    // ---- 跨进程存活锁 ----
    // status.json 的 running 字段在进程被强杀时会残留，导致 UI 误判"仍在跑"、
    // 拦住用户 3 分钟。文件锁由内核持有：进程一死锁必然释放，是唯一可靠的存活信号。
    // 锁文件放 filesDir（应用私有、两进程共享），外部存储可能被卸载或清理。

    private var heldChannel: FileChannel? = null
    private var heldLock: FileLock? = null

    private fun lockFile(context: Context): File = File(context.filesDir, "subtitle_gen.lock")

    /** 服务进程调用：持有独占锁直到进程死亡或主动 release。 */
    @Synchronized
    fun acquireRunLock(context: Context) {
        releaseRunLock()
        try {
            val ch = RandomAccessFile(lockFile(context), "rw").channel
            val lock = ch.tryLock()
            if (lock == null) {
                ch.close()
            } else {
                heldChannel = ch
                heldLock = lock
            }
        } catch (_: Throwable) {
        }
    }

    /** 服务进程调用：正常结束时释放，让 UI 立刻知道任务已停。 */
    @Synchronized
    fun releaseRunLock() {
        try { heldLock?.release() } catch (_: Throwable) {}
        try { heldChannel?.close() } catch (_: Throwable) {}
        heldLock = null
        heldChannel = null
    }

    /**
     * UI 进程调用：锁被占用说明服务进程真的还活着。
     * 拿不到/出错一律当"空闲"放行——真正防重复在服务侧 job.isActive，
     * 这里保守放行只会多一次被服务忽略的请求，不会放行两个并发批次。
     */
    @Synchronized
    fun isRunLocked(context: Context): Boolean {
        return try {
            val ch = RandomAccessFile(lockFile(context), "rw").channel
            val lock = ch.tryLock()
            if (lock == null) {
                true
            } else {
                try { lock.release() } catch (_: Throwable) {}
                try { ch.close() } catch (_: Throwable) {}
                false
            }
        } catch (_: Throwable) {
            false
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
