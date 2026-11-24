package com.magplay.subtitle

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * 离线字幕生成器：VAD 按静音切句 → Paraformer（非流式中文大模型）逐句识别 → SRT。
 * 全程设备端，无网络。模型从 assets 加载。
 *
 * 内存优化（修复批量生成 OOM）：
 * - 识别模型在 prepare() 中加载一次，整批视频复用；close() 统一释放。
 * - 音频由 AudioDecoder 分块交付，逐块识别，块内时间戳加 offsetMs 还原到全片时间轴。
 *
 * 时间轴正确性（修复长视频时间戳翻倍/倒挂）：
 * - 每个音频块**新建一个 Vad 实例**，用完即 release。
 *   原因：Vad 的 SpeechSegment.start 是"自实例创建以来的累计样本数"，Reset() 不保证
 *   复位该计数；复用实例处理第 k 块时 start 已累计到 k 块开头，再叠加 baseMs 就翻倍，
 *   27 分钟视频会跑出 54 分钟，且 endMs 被块尾夹断后出现 start > end 的倒挂条目。
 *   每块新建实例后，seg.start 必然从 0 起算，与块内偏移语义一致。
 * - 兜底：块内再保证起点单调、终点不早于起点，异常条目直接丢弃。
 */
class OfflineSubtitleGenerator(private val context: Context) {

    data class Progress(val stage: String, val percent: Int)

    private var vadConfig: VadModelConfig? = null
    private var recognizer: OfflineRecognizer? = null

    /** 加载模型（较重，整批只调用一次）。线程安全由调用方保证。 */
    fun prepare() {
        if (recognizer != null) return
        vadConfig = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "models/asr/silero_vad_v5.onnx",
                threshold = 0.5f,
                minSilenceDuration = 0.35f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 25f,
            ),
            sampleRate = 16000,
            numThreads = 1,
        )

        val modelConfig = OfflineModelConfig(
            paraformer = OfflineParaformerModelConfig(model = "models/asr/paraformer/model.int8.onnx"),
            tokens = "models/asr/paraformer/tokens.txt",
            numThreads = 2,
            modelType = "paraformer",
        )
        val recConfig = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )
        recognizer = OfflineRecognizer(context.assets, recConfig)
        Log.i(TAG, "models prepared (paraformer); vad is created per chunk")
    }

    fun isPrepared(): Boolean = recognizer != null

    /** 释放模型。 */
    fun close() {
        try { recognizer?.release() } catch (_: Throwable) {}
        recognizer = null
        vadConfig = null
        Log.i(TAG, "models released")
    }

    /**
     * 识别一个音频块。须先调用 prepare()。
     * 非挂起：解码回调线程里直接调用，避免为每块切线程。
     * @param chunk 16kHz mono float 音频块
     * @param isActive 取消检查（默认一直继续）
     * @return 带时间戳（已含 offsetMs）的字幕条目
     */
    fun generate(
        chunk: AudioDecoder.AudioChunk,
        isActive: () -> Boolean = { true },
        onProgress: (Progress) -> Unit = {},
    ): List<SrtCue> {
        val rec = recognizer ?: throw IllegalStateException("generator not prepared")
        val cfg = vadConfig ?: throw IllegalStateException("generator not prepared")
        val pcm = chunk.pcm
        val totalMs = pcm.size / 16L
        val baseMs = chunk.offsetMs
        val cues = mutableListOf<SrtCue>()

        // 每块新建：seg.start 从 0 起算，杜绝跨块累计
        val vad = Vad(context.assets, cfg)
        try {
            val window = 512
            var offset = 0
            var segIndex = 0
            var lastEndInChunk = 0L
            val win = FloatArray(window)

            fun processSegment(seg: com.k2fsa.sherpa.onnx.SpeechSegment) {
                val segSamples = seg.samples
                if (segSamples.isEmpty()) return
                val relStart = (seg.start / 16L).coerceIn(0L, totalMs)
                val speechMs = (segSamples.size / 16L).coerceAtLeast(400L)
                // 起点不得早于上一条结束（VAD 偶尔吐重叠语段），终点不得越过块尾
                val startInChunk = maxOf(relStart, lastEndInChunk)
                val endInChunk = (startInChunk + speechMs).coerceAtMost(totalMs)
                if (endInChunk <= startInChunk) return
                // 注意：必须用无参 createStream()。
                // sherpa-onnx 的 createStream(hotwords) 重载在基类里是兜底实现，
                // 非 transducer 模型（paraformer）调用会直接 SHERPA_ONNX_EXIT(-1) 杀进程。
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(segSamples, 16000)
                    rec.decode(stream)
                    val text = rec.getResult(stream).text.trim()
                    if (text.isEmpty()) return
                    lastEndInChunk = endInChunk
                    segIndex++
                    cues += SrtCue(
                        index = cues.size + 1,
                        startMs = baseMs + startInChunk,
                        endMs = baseMs + endInChunk,
                        text = text,
                    )
                    val pct = (endInChunk * 100 / totalMs.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                    onProgress(Progress("识别中 $segIndex 句", pct))
                    Log.d(TAG, "seg $segIndex [${baseMs + startInChunk}-${baseMs + endInChunk}ms] -> $text")
                } finally {
                    stream.release()
                }
            }

            // sherpa VAD 要求每次 acceptWaveform 严格喂 window_size(512) 个样本。
            // 复用同一个 512 缓冲，避免每 32ms 分配一次数组（长视频上百万次分配会拖慢并抖动内存）。
            while (offset < pcm.size) {
                if (!isActive()) break
                if (offset + window <= pcm.size) {
                    System.arraycopy(pcm, offset, win, 0, window)
                    offset += window
                } else {
                    val rest = pcm.size - offset
                    java.util.Arrays.fill(win, 0f)
                    System.arraycopy(pcm, offset, win, 0, rest)
                    offset = pcm.size
                }
                vad.acceptWaveform(win)
                while (!vad.empty()) {
                    if (!isActive()) break
                    val seg = vad.front()
                    processSegment(seg)
                    vad.pop()
                }
            }
            vad.flush()
            while (!vad.empty() && isActive()) {
                val seg = vad.front()
                processSegment(seg)
                vad.pop()
            }
            onProgress(Progress("块完成", 100))
            return cues
        } finally {
            try { vad.release() } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val TAG = "OfflineSubtitle"
    }
}
