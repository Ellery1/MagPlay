package com.magplay.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 后台续航守卫：字幕生成长时间跑 CPU，锁屏后系统会休眠 CPU、小米还会额外冻结后台。
 * 两件事：① 服务内持 PARTIAL_WAKE_LOCK（保持 CPU 醒着）② 申请电池优化白名单。
 */
object PowerGuard {

    private const val TAG = "PowerGuard"

    /** 是否已在电池优化白名单（未加入则锁屏后很可能被冻结）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (t: Throwable) {
        Log.w(TAG, "check failed: ${t.message}")
        false
    }

    /**
     * 拉起系统的「忽略电池优化」确认框。
     * 部分 ROM（小米/华为）会拦截或改文案，失败时退化为应用详情页，由用户手动设置。
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            val direct = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(direct)
            AppLog.i(TAG, "request ignore battery optimizations")
            return
        } catch (t: Throwable) {
            AppLog.w(TAG, "direct request failed: ${t.message}")
        }
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (t: Throwable) {
            AppLog.e(TAG, "open app details failed", t)
        }
    }
}
