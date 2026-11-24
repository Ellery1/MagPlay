package com.magplay.data

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量运行日志：内存环形缓冲 + 磁盘滚动文件（外部私有目录 files/logs/app.log）。
 *
 * 存在意义：把排查能力从 adb logcat 解耦出来 —— 用户在 App 内「运行日志」页一键导出分享，
 * 微信发过来即可定位问题，不需要连电脑、不需要保持 adb 在线。
 *
 * 崩溃也会被记录：安装 UncaughtExceptionHandler，先把栈写盘再交给系统默认处理。
 */
object AppLog {

    private const val TAG = "MagPlayLog"
    private const val MAX_LINES = 3000
    private const val MAX_FILE_BYTES = 512 * 1024L

    private val buffer = ArrayDeque<String>()
    private val lock = Any()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var installed = false

    @Volatile
    private var logFile: File? = null

    /** 首次调用时安装崩溃处理器并写一条启动横幅。可重复调用。 */
    fun init(context: Context) {
        if (installed) return
        installed = true
        synchronized(lock) {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(base, "logs")
            if (!dir.exists()) dir.mkdirs()
            logFile = File(dir, "app.log")

            val def = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try {
                    write("CRASH", "线程 ${t.name} 抛出 ${e.javaClass.name}: ${e.message}")
                    e.stackTrace.take(40).forEach { write("CRASH", "    at $it") }
                } catch (_: Throwable) {
                }
                def?.uncaughtException(t, e)
            }
            write(
                "INIT",
                "启动 ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})" +
                    " pid=${android.os.Process.myPid()} 日志目录=${dir.absolutePath}",
            )
        }
    }

    fun i(tag: String, msg: String) = write(tag, msg)
    fun w(tag: String, msg: String) = write(tag, msg)

    fun e(tag: String, msg: String, t: Throwable? = null) {
        write(tag, msg)
        if (t != null) {
            write(tag, "  ! ${t.javaClass.simpleName}: ${t.message}")
            t.stackTrace.take(12).forEach { write(tag, "    at $it") }
        }
    }

    private fun write(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [${Thread.currentThread().name}] $tag: $msg"
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
            try {
                val f = logFile ?: return
                if (f.exists() && f.length() > MAX_FILE_BYTES) {
                    val old = File(f.parentFile, "app.old.log")
                    if (old.exists()) old.delete()
                    f.renameTo(old)
                }
                f.appendText(line + "\n")
            } catch (_: Throwable) {
            }
        }
        Log.println(Log.INFO, TAG, line)
    }

    /**
     * 日志快照：磁盘历史尾部 + 当前进程内存缓冲。
     *
     * 只取内存缓冲的话，App 每次重启后日志页几乎是空的（历史都在 app.log 里），
     * 用户会误以为"没日志"。合并时按行去重：缓冲里的行通常已落盘，取磁盘尾部后
     * 只补上「落盘失败/尚未 flush」的那几行，避免重复。
     */
    fun snapshot(): String = synchronized(lock) {
        val mem = buffer.toList()
        val f = logFile
        if (f == null || !f.exists()) return mem.joinToString("\n")
        val fromFile = try {
            f.readLines()
        } catch (_: Throwable) {
            emptyList()
        }
        if (fromFile.isEmpty()) return mem.joinToString("\n")
        val memSet = mem.toHashSet()
        val old = fromFile.filterNot { it in memSet }.takeLast(1500)
        (old + mem).joinToString("\n")
    }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        try {
            logFile?.writeText("")
            File(logFile?.parentFile, "app.old.log").takeIf { it.exists() }?.delete()
        } catch (_: Throwable) {
        }
        write("LOG", "日志已清空")
    }

    /** 导出用全文：内存快照 + 磁盘历史尾部 + 离线字幕子进程日志尾部。 */
    fun exportText(): String = buildString {
        appendLine("===== MagPlay 运行日志 =====")
        appendLine("导出时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
        appendLine()
        appendLine("----- 当前进程（内存缓冲 ${synchronized(lock) { buffer.size }} 行） -----")
        appendLine(snapshot())
        appendLine()
        appendLine("----- 磁盘历史 app.log 尾部 -----")
        appendLine(tailOf("app.log", 600))
        appendLine()
        appendLine("----- 离线字幕子进程 gen.log 尾部 -----")
        appendLine(tailOf("gen.log", 300))
    }

    private fun tailOf(name: String, lines: Int): String {
        val f = File(logFile?.parentFile, name)
        if (!f.exists()) return "（无 $name）"
        return try {
            f.readLines().takeLast(lines).joinToString("\n")
        } catch (t: Throwable) {
            "（读取 $name 失败：${t.message}）"
        }
    }
}
