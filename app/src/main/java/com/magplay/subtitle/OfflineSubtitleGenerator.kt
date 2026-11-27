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
    val backend: AsrBackend = AsrBackend.FIRERED_AED,
    /** R5 诊断：把每段的 VAD/ASR 结果写到文件日志，便于事后定位漏句发生在哪一层。 */
    private val onDiagnostic: (String) -> Unit = {},
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
        // R5：阈值 0.5 在高噪声老电影上漏掉低音量短句（用户样本 15:20/21:20/45:10 整句缺失）。
        // 降到 0.3 召回；AED 对误检的噪音段多返回空文本，由 emptyAsr 统计兜底观察。
        vadConfig = AsrModelConfigFactory.vadConfig(
            maxSpeechSeconds = 20f,
            threshold = 0.3f,
        )
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
            // R5 诊断计数：VAD 检出的段数 vs 真正出字幕的段数，差值即"疑似漏句"
            var vadSegs = 0
            var emptyAsr = 0
            var clipped = 0
            val win = FloatArray(window)

            fun processSegment(seg: com.k2fsa.sherpa.onnx.SpeechSegment) {
                val raw = seg.samples
                if (raw.isEmpty()) return
                vadSegs++
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
                    if (rawText.isEmpty()) {
                        emptyAsr++
                        onDiagnostic("R5 empty-asr chunk@${baseMs} rel=$relStart len=${speechMs}ms " +
                            "rawSamples=${raw.size} startIn=$startInChunk")
                        return
                    }
                    val text = rawText
                    if (speechMs >= 20_000L) clipped++
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
                    // R5 细粒度：每个段的绝对时间与文本，便于核对用户给的漏句时间点
                    onDiagnostic("R5 seg [$absStart-$absEnd] ${text.take(40)}")
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
            // 整块直通：低信噪比台词 VAD 检不出（15:20 整段 0 段），但 AED 整段直接送能识别。
            // 与 VAD 结果按时间轴合并，重叠处直通优先（诊断证明直通文本更完整）。
            val vadCueCount = cues.size
            val directCues = directDecode(pcm, baseMs, totalMs, cues, isActive, onProgress)
            val merged = mergeCues(cues, directCues)
            cues.clear()
            cues.addAll(merged)
            onDiagnostic(
                "R5 chunk@${baseMs} vadSegs=$vadSegs emptyAsr=$emptyAsr clipped≥20s=$clipped " +
                    "vadCues=$vadCueCount directCues=${directCues.size} merged=${cues.size}",
            )
            onProgress(Progress("块完成", 100))
            return cues.sortedBy { it.startMs }.mapIndexed { i, c -> c.copy(index = i + 1) }
        } finally {
            try { vad.release() } catch (_: Throwable) {}
        }
    }

    /**
     * 整块直通识别：把 60s 块切成 [DIRECT_WINDOW_MS] 秒大窗口，每个窗口整段送 AED，
     * 不经过 VAD。诊断已证明这是召回低信噪比台词的关键：15:20/21:20/40:10 三处
     * VAD 漏掉的台词，整段直通全部识别成功（实测三处样本均命中）。
     *
     * 相邻窗口留 [DIRECT_OVERLAP_MS] 重叠，避免句子被窗口边界切断；
     * 严格过滤 [isNoiseText] 掉 <sil>、重复语气词等噪声。
     */
    private fun directDecode(
        pcm: FloatArray,
        baseMs: Long,
        totalMs: Long,
        vadCues: List<SrtCue>,
        isActive: () -> Boolean,
        onProgress: (Progress) -> Unit,
    ): List<SrtCue> {
        val rec = recognizer ?: return emptyList()
        val out = mutableListOf<SrtCue>()
        // pcm 已经被 highPass 原地处理过（见 generate 开头），这里直接用
        var w = 0L
        var winIdx = 0
        var skipped = 0
        val step = DIRECT_WINDOW_MS - DIRECT_OVERLAP_MS
        while (w < totalMs) {
            if (!isActive()) break
            val we = minOf(w + DIRECT_WINDOW_MS, totalMs)
            val from = (w * 16).toInt().coerceAtLeast(0)
            val to = (we * 16).toInt().coerceAtMost(pcm.size)
            if (to - from >= 3200) {
                // 无损提速：窗口内若有 ≥2 个 VAD 段，mergeCues 会无条件丢弃该窗口的
                // 直通文本（VAD 已细分，直通整段文本无法可靠对应）。既然结果必被丢弃，
                // 提前跳过 AED 推理，字幕输出逐字不变。判定口径与 mergeCues 完全一致：
                // 用同一份 vad cues、同一个 800ms 重叠阈值。
                val absW = baseMs + w
                val absWe = baseMs + we
                val covering = vadCues.count { v ->
                    minOf(absWe, v.endMs) - maxOf(absW, v.startMs) >= MERGE_MIN_OVERLAP_MS
                }
                if (covering >= 2) {
                    skipped++
                    winIdx++
                    w += step
                    continue
                }
                val seg = pcm.copyOfRange(from, to)
                val samples = AudioPreprocess.normalize(seg, maxGain = DIRECT_MAX_GAIN)
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(samples, 16000)
                    rec.decode(stream)
                    val result = rec.getResult(stream)
                    val raw = result.text.trim()
                    if (raw.isNotEmpty() && !isNoiseText(raw)) {
                        val clean = cleanDirectText(raw)
                        if (clean.isNotEmpty()) {
                            val parts = SubtitleLineBreaker.split(
                                clean, baseMs + w, baseMs + we,
                                timedTokens(result.tokens, result.timestamps),
                            )
                            parts.forEach { (t, range) ->
                                out += SrtCue(
                                    index = 0, startMs = range.first, endMs = range.last, text = t,
                                    windowId = winIdx,
                                )
                            }
                            onDiagnostic("R5 direct [$winIdx ${baseMs + w}-${baseMs + we}] $clean")
                        }
                    } else {
                        onDiagnostic("R5 direct [$winIdx ${baseMs + w}-${baseMs + we}] dropped")
                    }
                } finally {
                    stream.release()
                }
            }
            winIdx++
            w += step
            val pct = (w * 100 / totalMs.coerceAtLeast(1)).toInt().coerceIn(0, 100)
            onProgress(Progress("直通补漏 $pct%", pct))
        }
        if (skipped > 0) onDiagnostic("R5 direct-skip chunk@$baseMs skipped=$skipped (mergeCues 必丢弃)")
        return out
    }

    /** 严格过滤：丢掉 <sil>/<unk>/纯语气词/重复单字刷屏，避免直通产生垃圾字幕。 */
    private fun isNoiseText(text: String): Boolean {
        val t = text.replace(Regex("<[^>]*>"), "").trim()
        if (t.isEmpty()) return true
        if (t.count { it in '\u4e00'..'\u9fff' } < 2) return true
        // 单一字符重复 ≥4 次（如"哎哎哎哎哎"刷屏）
        var run = 1
        for (i in 1 until t.length) {
            run = if (t[i] == t[i - 1]) run + 1 else 1
            if (run >= 4) return true
        }
        // 去掉语气词后没内容 = 纯噪声
        val stripped = t.filter { it !in "啊哎呀哦噢嗯呃呵哈嘿嘛呢吧" }
        return stripped.count { it in '\u4e00'..'\u9fff' } < 2
    }

    /** 去掉直通文本里的标签和连续重复的语气字，保留可读台词。 */
    private fun cleanDirectText(text: String): String {
        var s = text.replace(Regex("<[^>]*>"), "").trim()
        // 连续相同字压到 2 个（"哎哎哎哎"→"哎哎"），比全丢更自然
        val sb = StringBuilder()
        var prev: Char? = null
        var run = 0
        for (c in s) {
            if (c == prev) run++ else run = 1
            if (run <= 2) sb.append(c)
            prev = c
        }
        s = sb.toString().trim()
        return s
    }

    /**
     * 合并 VAD 主结果与 5s 直通结果。**时间轴精度优先**：VAD 段的时间戳来自静音边界，
     * 是唯一可靠的时间源，一律不动；直通只用来补内容，不引入新的时间估计。
     *
     * AED 无词级时间戳，直通 5s 窗口内的文本只能按字数均摊，时间误差 ±2.5s，
     * 因此凡是有 VAD 段时间可用的地方，都用 VAD 时间，绝不用窗口时间覆盖。
     *
     * 逐窗口处理（直通条目携带窗口范围）：
     * - 窗口内恰好 1 个 VAD 段：直通文本更长（含 VAD 漏掉的内容，如 21:20 前半句）
     *   则替换该段文本、保留 VAD 时间；否则保持 VAD 原样。
     * - 窗口内 ≥2 个 VAD 段：VAD 已细分，直通整段文本无法可靠对应，丢弃直通。
     * - 窗口内 0 个 VAD 段（盲区，如 15:20）：VAD 无时间可用，才用直通条目 +
     *   窗口时间（±2.5s，这是无从得知精确时间的唯一退路）。
     */
    private fun mergeCues(
        vad: List<SrtCue>,
        direct: List<SrtCue>,
    ): List<SrtCue> {
        if (direct.isEmpty()) return vad.sortedBy { it.startMs }
        // 直通条目按生成时的 5s 窗口分组（同一窗口被切成多条时，文本拼起来）
        val windows = direct.groupBy { it.windowId }
        val textReplacement = HashMap<Int, String>()  // vad index -> 更完整的文本
        val blindInserts = mutableListOf<SrtCue>()     // VAD 盲区补入

        for ((_, entries) in windows) {
            val first = entries.first()
            val winStart = first.startMs
            val winEnd = entries.maxOf { it.endMs }
            val mergedText = entries.sortedBy { it.startMs }.joinToString("") { it.text }
            if (mergedText.isEmpty()) continue

            // 找与窗口重叠的 VAD 段
            val inWindow = vad.indices.filter { i ->
                val v = vad[i]
                val a = maxOf(v.startMs, winStart)
                val b = minOf(v.endMs, winEnd)
                b - a >= MERGE_MIN_OVERLAP_MS
            }
            when {
                inWindow.size == 1 -> {
                    val v = vad[inWindow[0]]
                    // 直通文本含更多内容才替换（VAD 漏了半句）；时间保持 VAD 的
                    if (mergedText.length > v.text.length + 1) {
                        textReplacement[inWindow[0]] = mergedText
                    }
                }
                inWindow.isEmpty() -> {
                    // 盲区：无 VAD 时间可用，用窗口时间 + 均摊文本
                    val span = (winEnd - winStart).coerceAtLeast(700L)
                    blindInserts += SrtCue(0, winStart, winStart + span, mergedText)
                }
                // inWindow.size >= 2: VAD 已细分，丢弃直通，避免文本错配
            }
        }

        val result = mutableListOf<SrtCue>()
        for (i in vad.indices) {
            val v = vad[i]
            result += textReplacement[i]?.let { SrtCue(0, v.startMs, v.endMs, it) } ?: v
        }
        result.addAll(blindInserts)
        return result.sortedBy { it.startMs }
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

        /**
         * 整块直通：5s 窗口。AED 不返回词级时间戳（ts=0），窗口越大、台词时间越不准
         * （20s 窗口误差可达 ±10s）。5s 窗口把误差压到 ±2.5s，且诊断证明目标台词
         * 各自完整落在单个 5s 窗口内（15:20→5-10s、21:20→10-15s、40:10→0-15s）。
         */
        private const val DIRECT_WINDOW_MS = 5_000L
        /** 直通窗口步长 = 窗口长度，无重叠，避免同一台词在相邻窗口重复识别。 */
        private const val DIRECT_OVERLAP_MS = 0L
        /** 整块直通：增益上限 20 倍，把 RMS≈0.005 的弱人声抬到模型目标电平。 */
        private const val DIRECT_MAX_GAIN = 20f
        /** 合并：直通窗口与 VAD 段重叠超过此值，视为同一处台词。 */
        private const val MERGE_MIN_OVERLAP_MS = 800L
    }
}
