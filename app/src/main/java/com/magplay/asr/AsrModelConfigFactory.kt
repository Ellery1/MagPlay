package com.magplay.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.VadModelConfig

/** 模型配置的唯一出处（只服务离线批量生成）。唯一引擎：FireRedASR2 AED。 */
object AsrModelConfigFactory {

    const val VAD_ASSET = "models/asr/silero_vad_v5.onnx"

    /** 批量生成：4 线程换速度（独占场景）。 */
    const val BATCH_THREADS = 4

    fun recognizerConfig(
        context: Context,
        backend: AsrBackend,
        threads: Int,
        decodingMethod: String = "greedy_search",
    ): OfflineRecognizerConfig = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80, dither = 0f),
        modelConfig = modelConfig(context, backend, threads),
        decodingMethod = decodingMethod,
    )

    fun modelConfig(context: Context, backend: AsrBackend, threads: Int): OfflineModelConfig {
        fun p(rel: String): String =
            AsrModels.path(context, backend, rel) ?: error("模型文件缺失：$rel（请先下载离线字幕模型）")

        return when (backend) {
            AsrBackend.FIRERED_AED -> OfflineModelConfig(
                fireRedAsr = OfflineFireRedAsrModelConfig(
                    encoder = p("encoder.int8.onnx"),
                    decoder = p("decoder.int8.onnx"),
                ),
                tokens = p("tokens.txt"),
                numThreads = threads,
                modelType = "fire_red_asr",
            )
        }
    }

    /**
     * VAD 切句参数。阈值/最短语音段可微调以治漏句（见 R5），但 maxSpeechSeconds
     * 不得超过 20s：AED 在长段上会丢内容。
     */
    fun vadConfig(
        maxSpeechSeconds: Float = 20f,
        threshold: Float = 0.5f,
        minSpeechDuration: Float = 0.10f,
        minSilenceDuration: Float = 0.15f,
    ): VadModelConfig = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = VAD_ASSET,
            threshold = threshold,
            minSilenceDuration = minSilenceDuration,
            minSpeechDuration = minSpeechDuration,
            windowSize = 512,
            maxSpeechDuration = maxSpeechSeconds,
        ),
        sampleRate = 16000,
        numThreads = 1,
    )
}
