package com.magplay.subtitle

import android.content.Context
import com.magplay.asr.AsrBackend
import com.magplay.asr.AsrModels

/**
 * 识别引擎：唯一可选 FireRedASR2 AED。
 * 模型由 App 内下载器安装到 files/models/firered_aed/。
 */
object AsrSettings {
    val backend: AsrBackend = AsrBackend.FIRERED_AED

    fun backend(context: Context): AsrBackend = backend

    fun isReady(context: Context): Boolean = AsrModels.isAvailable(context, backend)
}
