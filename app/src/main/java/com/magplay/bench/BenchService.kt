package com.magplay.bench

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.magplay.asr.AsrBackend
import com.magplay.asr.AsrModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 模型对比测试的承载服务。
 *
 * 为什么用前台服务而不是在 Activity 里跑：一次对比要连续几分钟，界面上滑走就可能被冻结；
 * 这里用独立进程 `:bench`，即使 native 层崩了也不影响主界面和字幕生成。
 *
 * 触发方式（无需界面交互）：
 *   adb shell am start-foreground-service -n com.magplay/.bench.BenchService \
 *     --es path /sdcard/Movies/xxx.mp4 --ei sec 90 --es models firered_ctc,qwen3
 */
class BenchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("准备中…"))
        // 屏幕一关 MIUI 会把进程冻住（日志：Sending oneway calls to frozen process），
        // 和字幕生成踩的是同一个坑，这里同样必须持唤醒锁。
        acquireWakeLock()
        val path = intent?.getStringExtra(EXTRA_PATH)
        if (path.isNullOrBlank() || !File(path).isFile) {
            notify("素材不存在：$path")
            stopSelf()
            return START_NOT_STICKY
        }
        val seconds = intent.getIntExtra(EXTRA_SEC, 90)
        val startSec = intent.getIntExtra(EXTRA_START, 0)
        val ids = intent.getStringExtra(EXTRA_MODELS)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val backends = (ids ?: AsrModels.available(this).map { it.id })
            .map { AsrBackend.fromId(it) }
            .filter { AsrModels.isAvailable(this, it) }
            .ifEmpty { listOf(AsrBackend.PARAFORMER) }

        Log.i(TAG, "bench start: $path sec=$seconds models=${backends.map { it.id }}")
        scope.launch {
            try {
                val results = BenchRunner.run(this@BenchService, path, seconds, backends, startSec) { msg ->
                    notify(msg)
                }
                val summary = results.joinToString("\n") { r ->
                    "${r.backend.id}: load=${r.loadMs}ms run=${r.runMs}ms rtf=%.2f cues=${r.cueCount} chars=${r.charCount} ${r.error ?: ""}"
                        .format(r.rtf)
                }
                Log.i(TAG, "bench done\n$summary")
                notify("对比完成（${results.size} 个模型）")
            } catch (t: Throwable) {
                Log.e(TAG, "bench failed", t)
                notify("对比失败：${t.message}")
            } finally {
                releaseWakeLock()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MagPlay:bench").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L)
        }
        Log.i(TAG, "wakelock acquired")
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {}
        wakeLock = null
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "模型对比", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("MagPlay 模型对比")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    private fun notify(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "BenchService"
        private const val CHANNEL = "bench"
        private const val NOTIF_ID = 4211
        const val EXTRA_PATH = "path"
        const val EXTRA_SEC = "sec"
        const val EXTRA_MODELS = "models"
        const val EXTRA_START = "start"
    }
}
