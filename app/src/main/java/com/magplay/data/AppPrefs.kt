package com.magplay.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 用户偏好：主题、字幕字号、字幕开关。
 *
 * 用 Compose 的 mutableState 承载，设置页改完立即生效，不用重启页面；
 * 同时落一份到 SharedPreferences，下次启动还在。
 */
object AppPrefs {

    private const val FILE = "magplay_prefs"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_SUB_SCALE = "subtitle_scale"
    private const val KEY_SUB_ON = "subtitle_on"

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    @Volatile
    private var sp: SharedPreferences? = null

    /** 0 跟随系统 / 1 浅色 / 2 深色 */
    var themeMode by mutableIntStateOf(THEME_SYSTEM)
        private set

    /** 字幕字号倍数：0.85 小 / 1.0 中 / 1.25 大 */
    var subtitleScale by mutableFloatStateOf(1f)
        private set

    /** 上次看视频时字幕是开还是关，下次进播放页沿用 */
    var subtitleOn by mutableStateOf(true)
        private set

    fun init(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = p
        themeMode = p.getInt(KEY_THEME, THEME_SYSTEM)
        subtitleScale = p.getFloat(KEY_SUB_SCALE, 1f)
        subtitleOn = p.getBoolean(KEY_SUB_ON, true)
    }

    fun updateThemeMode(mode: Int) {
        themeMode = mode
        sp?.edit()?.putInt(KEY_THEME, mode)?.apply()
    }

    fun updateSubtitleScale(value: Float) {
        subtitleScale = value
        sp?.edit()?.putFloat(KEY_SUB_SCALE, value)?.apply()
    }

    fun updateSubtitleOn(value: Boolean) {
        subtitleOn = value
        sp?.edit()?.putBoolean(KEY_SUB_ON, value)?.apply()
    }
}
