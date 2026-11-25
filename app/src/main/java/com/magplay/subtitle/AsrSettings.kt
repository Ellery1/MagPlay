package com.magplay.subtitle

import android.content.Context
import com.magplay.asr.AsrBackend
import com.magplay.asr.AsrModels

/**
 * 识别引擎选择。默认取「已就绪里最强的一个」：
 * FireRedASR2-CTC → FireRedASR2-AED → Qwen3-ASR → Paraformer。
 */
object AsrSettings {

    private const val PREF = "asr_prefs"
    private const val KEY_BACKEND = "backend"

    /** 准确率优先级（越前越优先）。 */
    // 实测（同一部片两个 120s 片段，见 FEATURE_LEDGER 6.15）：
    // AED 两段都最好，速度 4.1~5.7s/120s，作为默认；
    // CTC 略快但会漏字/颠倒语序；Qwen3 有错别字，留作备选。
    private val PRIORITY = listOf(
        AsrBackend.FIRERED_AED,
        AsrBackend.FIRERED_CTC,
        AsrBackend.QWEN3,
        AsrBackend.PARAFORMER,
    )

    fun autoBest(context: Context): AsrBackend =
        PRIORITY.firstOrNull { AsrModels.isAvailable(context, it) } ?: AsrBackend.PARAFORMER

    /** 用户显式选过且模型仍在 → 用它；否则用自动最优。 */
    fun backend(context: Context): AsrBackend {
        val saved = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_BACKEND, null)
        val picked = saved?.let { AsrBackend.fromId(it) } ?: return autoBest(context)
        return if (AsrModels.isAvailable(context, picked)) picked else autoBest(context)
    }

    fun setBackend(context: Context, backend: AsrBackend) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_BACKEND, backend.id).apply()
        // 换引擎后把「试过但失败」的记录清掉：强模型可能认得出来弱模型认不出的台词
        GenAttempts.clear(context)
    }
}
