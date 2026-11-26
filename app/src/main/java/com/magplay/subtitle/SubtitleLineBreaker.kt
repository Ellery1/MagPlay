package com.magplay.subtitle

/**
 * 把「一段识别文本 + 该语段的起止时间」切成若干条字幕。
 *
 * 为什么必须切：VAD 的上限是 20 秒，20 秒中文对白大约 80~120 字，
 * 一条字幕塞 100 个字在手机屏上根本读不完。按标点切成 15~25 字的短句才是正常字幕节奏。
 *
 * 时间分配用「字数加权」而非平均分配 —— 中文语速在同一句里相对稳定，
 * 字数比例是最便宜也足够准的近似。有词级时间戳时优先用时间戳（见 [splitWithTimestamps]）。
 */
object SubtitleLineBreaker {

    /** 一句话超过这个长度就再按逗号切。 */
    private const val MAX_CHARS = 26
    /** 切完之后单条不低于这个时长，避免闪一下就没了。 */
    private const val MIN_CUE_MS = 700L
    /** 句末标点。 */
    private const val SENTENCE_END = "。！？!?…；;\n"
    /** 句中标点（长句二次切分点）。 */
    private const val CLAUSE_END = "，,、：:"

    /**
     * @param text 已加标点的识别结果
     * @param startMs 语段起点（绝对时间）
     * @param endMs 语段终点（绝对时间）
     * @param timedTokens 词级时间戳（秒，相对语段开头）；为空则纯按字数分配
     */
    fun split(
        text: String,
        startMs: Long,
        endMs: Long,
        timedTokens: List<Pair<String, Float>> = emptyList(),
    ): List<Pair<String, LongRange>> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        val segMs = (endMs - startMs).coerceAtLeast(MIN_CUE_MS)

        if (timedTokens.size >= 2) {
            splitWithTimestamps(clean, timedTokens, startMs, endMs)?.let { return it }
        }

        val pieces = splitText(clean)
        if (pieces.size == 1) {
            return listOf(pieces[0] to (startMs until endMs))
        }

        // 字数加权分配时间
        val weights = pieces.map { weight(it) }
        val total = weights.sum().coerceAtLeast(1.0)
        val out = mutableListOf<Pair<String, LongRange>>()
        var cursor = startMs
        for (i in pieces.indices) {
            val isLast = i == pieces.lastIndex
            val span = if (isLast) {
                (endMs - cursor).coerceAtLeast(MIN_CUE_MS)
            } else {
                (segMs * weights[i] / total).toLong().coerceAtLeast(MIN_CUE_MS)
            }
            val stop = if (isLast) endMs else (cursor + span).coerceAtMost(endMs)
            if (stop <= cursor) continue
            out += pieces[i] to (cursor until stop)
            cursor = stop
        }
        return out
    }

    /** 按标点切句子；过长句按逗号二次切。 */
    fun splitText(text: String): List<String> {
        val sentences = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (SENTENCE_END.indexOf(ch) >= 0) {
                val s = sb.toString().trim()
                if (s.isNotEmpty()) sentences += s
                sb.setLength(0)
            }
        }
        val tail = sb.toString().trim()
        if (tail.isNotEmpty()) sentences += tail

        val out = mutableListOf<String>()
        for (s in sentences) {
            if (s.length <= MAX_CHARS) {
                out += s
                continue
            }
            out += splitLongClause(s)
        }
        return out.ifEmpty { listOf(text) }
    }

    private fun splitLongClause(sentence: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in sentence) {
            sb.append(ch)
            val longEnough = sb.length >= MAX_CHARS * 3 / 4
            if (longEnough && CLAUSE_END.indexOf(ch) >= 0) {
                out += sb.toString().trim()
                sb.setLength(0)
            } else if (sb.length >= MAX_CHARS) {
                // 识别结果没有标点（未装标点模型）时只能按长度硬切，切点不加任何符号：
                // 两条字幕靠时间轴分开，比塞一个逗号更干净
                out += sb.toString().trim()
                sb.setLength(0)
            }
        }
        val tail = sb.toString().trim()
        if (tail.isNotEmpty()) {
            // R5：硬切后剩下的零星尾字（如只有"风"一个字）并入上一条，
            // 避免闪现一条单字字幕——既读不完也像漏字。没有上一条时原样保留。
            if (tail.length < 4 && out.isNotEmpty()) {
                out[out.lastIndex] = out.last() + tail
            } else {
                out += tail
            }
        }
        return out.filter { it.isNotEmpty() }
    }

    /**
     * 有词级时间戳时，按「累计时长超过阈值」成组，时间直接取首词开始 / 末词结束。
     * 这比字数加权准得多，尤其是停顿多、语速变化大的对白。
     */
    private fun splitWithTimestamps(
        text: String,
        tokens: List<Pair<String, Float>>,
        startMs: Long,
        endMs: Long,
    ): List<Pair<String, LongRange>>? {
        val joined = tokens.joinToString("") { it.first }
        // 时间戳词序列与最终文本对不上（加了标点/ITN 改写）时放弃，走字数分配
        if (normalize(joined) != normalize(text)) return null

        val out = mutableListOf<Pair<String, LongRange>>()
        var buf = StringBuilder()
        var bufStart = tokens.first().second
        var i = 0
        while (i < tokens.size) {
            val (w, ts) = tokens[i]
            buf.append(w)
            val endsSentence = w.isNotEmpty() && SENTENCE_END.indexOf(w.last()) >= 0
            val tooLong = buf.length >= MAX_CHARS
            if ((endsSentence || tooLong) && buf.isNotEmpty()) {
                val nextTs = tokens.getOrNull(i + 1)?.second
                val stop = ((nextTs ?: (ts + 0.4f)) * 1000).toLong() + startMs
                out += buf.toString().trim() to
                    ((startMs + (bufStart * 1000).toLong()) until stop.coerceAtMost(endMs))
                buf = StringBuilder()
                bufStart = nextTs ?: (ts + 0.4f)
            }
            i++
        }
        if (buf.isNotEmpty()) {
            out += buf.toString().trim() to
                ((startMs + (bufStart * 1000).toLong()) until endMs)
        }
        return out.filter { it.second.last > it.second.first }
    }

    private fun normalize(s: String): String =
        s.filter { it.isLetterOrDigit() || it.code > 0x2E80 }

    private fun weight(s: String): Double =
        s.count { it.isLetterOrDigit() || it.code > 0x2E80 }.toDouble().coerceAtLeast(1.0)
}
