package com.magplay.subtitle

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.magplay.asr.AsrBackend
import com.magplay.asr.AsrModelConfigFactory
import com.magplay.asr.NullAssets
import com.magplay.asr.AsrModels

/**
 * 离线字幕生成器：VAD 按静音切句 → 指定后端逐句识别 → 加标点 → 按语意切条 → SRT。
 * 全程设备端，无网络。
 *
 * 三个可变点（都为了准确率）：
 * 1. **后端可换**：Paraformer / FireRedASR2-CTC / FireRedASR2-AED / Qwen3-ASR，模型从
 *    `files/models/<id>/` 读，换模型不用重装 APK。
 * 2. **标点模型**：ct-punc 给无标点输出补标点，同时被 [SubtitleLineBreaker] 当作切句依据。
 * 3. **音频规整**：80Hz 高通去低频噪声 + 逐段 RMS 归一化。
 *
 * 时间轴正确性（历史坑，勿改）：
 * - 每个音频块**新建 Vad 实例**：seg.start 是实例生命周期累计样本数，复用会导致时间轴翻倍。
 * - 段起点不得早于上一条结束，终点不得越过块尾。
 */
class OfflineSubtitleGenerator(
    private val context: Context,
    val backend: AsrBackend = AsrBackend.PARAFORMER,
) {

    data class Progress(val stage: String, val percent: Int)

    private var vadConfig: VadModelConfig? = null
    private var recognizer: OfflineRecognizer? = null
    private var highPass: AudioPreprocess.HighPass? = null

    /** 最近一次 prepare 失败的原因，供 UI / 日志显示。 */
    var lastError: String? = null
        private set

    /** 加载模型（较重，整批只调用一次）。失败返回 false 并写 lastError。 */
    fun prepare(): Boolean {
        if (recognizer != null) return true
        lastError = null
        val model = try {
            AsrModelConfigFactory.modelConfig(context, backend, AsrModelConfigFactory.BATCH_THREADS)
        } catch (t: Throwable) {
            lastError = "模型文件缺失：${t.message}"
            Log.e(TAG, "build model config failed", t)
            return false
        }
        // 段太长会让 AED/LLM 类模型丢内容，20s 是准确率与效率的平衡点
        vadConfig = AsrModelConfigFactory.vadConfig(maxSpeechSeconds = 20f)
        return try {
            val recConfig = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80, dither = 0f),
                modelConfig = model,
                decodingMethod = "greedy_search",
            )
            // 模型在文件系统上时必须传 null AssetManager，否则 sherpa-onnx 直接 exit 掉进程
            val am = if (AsrModels.onFileSystem(context, backend)) NullAssets.get() else context.assets
            recognizer = OfflineRecognizer(am, recConfig)
            highPass = AudioPreprocess.HighPass()
            Log.i(TAG, "models prepared: ${backend.id}")
            true
        } catch (t: Throwable) {
            lastError = "模型加载失败：${t.message}"
            Log.e(TAG, "load recognizer failed", t)
            recognizer = null
            false
        }
    }

    fun isPrepared(): Boolean = recognizer != null

    /** 释放模型。 */
    fun close() {
        try { recognizer?.release() } catch (_: Throwable) {}
        recognizer = null
        vadConfig = null
        highPass = null
        Log.i(TAG, "models released (${backend.id})")
    }

    /**
     * 识别一个音频块。须先调用 prepare()。
     * @param chunk 16kHz mono float 音频块（会被原地高通滤波）
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

        // 低频去噪：整块连续处理，状态跨块保留（见 HighPass 注释）
        highPass?.process(pcm)

        // 每块新建：seg.start 从 0 起算，杜绝跨块累计
        val vad = Vad(context.assets, cfg)
        try {
            val window = 512
            var offset = 0
            var segIndex = 0
            var lastEndInChunk = 0L
            val win = FloatArray(window)

            fun processSegment(seg: com.k2fsa.sherpa.onnx.SpeechSegment) {
                val raw = seg.samples
                if (raw.isEmpty()) return
                val relStart = (seg.start / 16L).coerceIn(0L, totalMs)
                val speechMs = (raw.size / 16L).coerceAtLeast(400L)
                val startInChunk = maxOf(relStart, lastEndInChunk)
                val endInChunk = (startInChunk + speechMs).coerceAtMost(totalMs)
                if (endInChunk <= startInChunk) return

                val stream = rec.createStream()
                try {
                    val samples = AudioPreprocess.normalize(raw)
                    stream.acceptWaveform(samples, 16000)
                    rec.decode(stream)
                    val result = rec.getResult(stream)
                    val rawText = result.text.trim()
                    if (rawText.isEmpty()) return
                    val text = rawText
                    lastEndInChunk = endInChunk
                    segIndex++

                    val absStart = baseMs + startInChunk
                    val absEnd = baseMs + endInChunk
                    val timed = timedTokens(result.tokens, result.timestamps)
                    val parts = SubtitleLineBreaker.split(text, absStart, absEnd, timed)
                    parts.forEach { (t, range) ->
                        cues += SrtCue(
                            index = 0, // 统一在最后重编号
                            startMs = range.first,
                            endMs = range.last,
                            text = t,
                        )
                    }
                    val pct = (endInChunk * 100 / totalMs.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                    onProgress(Progress("识别中 $segIndex 句", pct))
                    Log.d(TAG, "seg $segIndex [$absStart-$absEnd] -> $text")
                } finally {
                    stream.release()
                }
            }

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
            return cues.mapIndexed { i, c -> c.copy(index = i + 1) }
        } finally {
            try { vad.release() } catch (_: Throwable) {}
        }
    }

    /** 组装词级时间戳。token 与 timestamp 对不上（长度不一致）时返回空。 */
    private fun timedTokens(tokens: Array<String>?, ts: FloatArray?): List<Pair<String, Float>> {
        if (tokens == null || ts == null) return emptyList()
        if (tokens.isEmpty() || tokens.size != ts.size) return emptyList()
        return tokens.zip(ts.toList())
    }

    companion object {
        private const val TAG = "OfflineSubtitle"

        /** 批量生成是独占场景，多给几个线程换速度。 */
        private const val THREADS = 4
    }
}
