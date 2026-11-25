package com.magplay.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * 模型配置的唯一出处（只服务离线批量生成）。
 * 播放页的实时字幕已按需求移除，这里不再保留实时专用参数。
 */
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
            AsrModels.path(context, backend, rel) ?: error("模型文件缺失：$rel")

        return when (backend) {
            AsrBackend.PARAFORMER -> OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(model = p("model.int8.onnx")),
                tokens = p("tokens.txt"),
                numThreads = threads,
                modelType = "paraformer",
            )

            AsrBackend.FIRERED_CTC -> OfflineModelConfig(
                fireRedAsrCtc = OfflineFireRedAsrCtcModelConfig(model = p("model.int8.onnx")),
                tokens = p("tokens.txt"),
                numThreads = threads,
                modelType = "fire_red_asr_ctc",
            )

            AsrBackend.FIRERED_AED -> OfflineModelConfig(
                fireRedAsr = OfflineFireRedAsrModelConfig(
                    encoder = p("encoder.int8.onnx"),
                    decoder = p("decoder.int8.onnx"),
                ),
                tokens = p("tokens.txt"),
                numThreads = threads,
                modelType = "fire_red_asr",
            )

            AsrBackend.QWEN3 -> OfflineModelConfig(
                qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = p("conv_frontend.onnx"),
                    encoder = p("encoder.int8.onnx"),
                    decoder = p("decoder.int8.onnx"),
                    tokenizer = File(AsrModels.dirOf(context, backend), "tokenizer").absolutePath,
                    maxTotalLen = 2048,
                    maxNewTokens = 512,
                    temperature = 0.0f,
                    topP = 1.0f,
                    seed = 42,
                    hotwords = "",
                ),
                tokens = "",
                numThreads = threads,
                modelType = "qwen3_asr",
            )
        }
    }

    fun vadConfig(maxSpeechSeconds: Float = 20f): VadModelConfig = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = VAD_ASSET,
            threshold = 0.5f,
            minSilenceDuration = 0.40f,
            minSpeechDuration = 0.25f,
            windowSize = 512,
            maxSpeechDuration = maxSpeechSeconds,
        ),
        sampleRate = 16000,
        numThreads = 1,
    )
}
