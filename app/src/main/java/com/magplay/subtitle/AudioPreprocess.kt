package com.magplay.subtitle

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 识别前的音频规整。
 *
 * 这类片源（网络下载、多段拼接、带 BGM）三个共性问题会让 ASR 明显掉点：
 * 1. 低频隆隆声 / 空调风噪 —— 80Hz 一阶高通滤掉，几乎不损伤人声；
 * 2. 整体音量偏低或忽大忽小 —— 按段做 RMS 归一化到目标电平，避免模型在小音量段乱猜；
 * 3. 直流偏置 —— 高通顺带解决。
 *
 * 归一化是**按语段**做的（不是按整块），所以不会把静音段放得很大。
 */
object AudioPreprocess {

    private const val HIGH_PASS_HZ = 80.0
    private const val SAMPLE_RATE = 16000.0

    /** 目标 RMS 电平（约 -20 dBFS）。 */
    private const val TARGET_RMS = 0.1f
    /** 增益上下限，防止把噪声放大到失真，也防止把爆音压成蚊音。 */
    private const val MIN_GAIN = 0.5f
    private const val MAX_GAIN = 6.0f

    /**
     * 一阶高通滤波器。**有状态**：必须整段连续调用，不能每块新建，
     * 否则块边界会出现咔哒声（对识别影响不大，但会污染 VAD 的能量判断）。
     */
    class HighPass {
        private var prevIn = 0f
        private var prevOut = 0f
        private val alpha: Float =
            (1.0 / (1.0 + 2 * Math.PI * HIGH_PASS_HZ / SAMPLE_RATE)).toFloat()

        fun process(pcm: FloatArray) {
            for (i in pcm.indices) {
                val x = pcm[i]
                val y = alpha * (prevOut + x - prevIn)
                prevIn = x
                prevOut = y
                pcm[i] = y
            }
        }
    }

    /** 按段归一化音量。返回新数组，不改原数组（原数组还要留给其他用途时更安全）。 */
    fun normalize(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        var sum = 0.0
        for (v in samples) sum += v.toDouble() * v
        val rms = sqrt(sum / samples.size).toFloat()
        // 近乎全静音：不动，交给 VAD 判定
        if (rms < 1e-5f) return samples
        val gain = (TARGET_RMS / rms).coerceIn(MIN_GAIN, MAX_GAIN)
        if (abs(gain - 1f) < 0.02f) return samples
        val out = FloatArray(samples.size)
        for (i in samples.indices) out[i] = (samples[i] * gain).coerceIn(-1f, 1f)
        return out
    }

    /** 峰值 dBFS，仅用于日志诊断。 */
    fun rmsDb(samples: FloatArray): Double {
        if (samples.isEmpty()) return -99.0
        var sum = 0.0
        for (v in samples) sum += v.toDouble() * v
        val rms = sqrt(sum / samples.size)
        if (rms <= 1e-9) return -99.0
        return 20 * log10(rms)
    }
}
