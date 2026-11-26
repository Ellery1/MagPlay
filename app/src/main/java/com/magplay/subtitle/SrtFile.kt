package com.magplay.subtitle

/**
 * 一条 SRT 字幕。startMs / endMs 为相对视频开头的毫秒偏移。
 */
data class SrtCue(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** 直通识别时的 5s 窗口序号；仅用于合并分组，普通 VAD 条目为 -1。 */
    val windowId: Int = -1,
)

/**
 * SRT 解析 / 生成工具。纯字符串处理，无 Android 依赖，便于单测。
 */
object SrtFile {

    /** 将 SRT 文本解析为字幕列表；容错空行、CRLF、序号缺失。 */
    fun parse(content: String): List<SrtCue> {
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.split(Regex("\\n\\s*\\n"))
        val result = mutableListOf<SrtCue>()
        var autoIndex = 1
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue

            var idx = 0
            // 首行若是纯数字则视为序号
            if (lines[0].toIntOrNull() != null) {
                idx = 1
            }
            if (idx >= lines.size) continue
            val timeLine = lines[idx]
            val arrow = timeLine.indexOf("-->")
            if (arrow < 0) continue
            val startMs = parseTimestamp(timeLine.substring(0, arrow).trim()) ?: continue
            val endMs = parseTimestamp(timeLine.substring(arrow + 3).trim()) ?: continue
            val textLines = lines.drop(idx + 1)
            if (textLines.isEmpty()) continue
            result += SrtCue(
                index = autoIndex++,
                startMs = startMs,
                endMs = endMs,
                text = textLines.joinToString("\n"),
            )
        }
        return result
    }

    /** 解析 `00:00:01,234` 或 `00:00:01.234` 为毫秒。 */
    private fun parseTimestamp(s: String): Long? {
        // 有些 SRT 在时间后带定位信息 "X1:..."，先截取时间主体
        val main = s.substringBefore(" ")
        val hms = main.replace(',', '.')
        val parts = hms.split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val secPart = parts[2]
        val dot = secPart.indexOf('.')
        val sec: Long
        val ms: Long
        if (dot >= 0) {
            sec = secPart.substring(0, dot).toLongOrNull() ?: return null
            var frac = secPart.substring(dot + 1)
            frac = (frac + "000").substring(0, 3)
            ms = frac.toLongOrNull() ?: 0
        } else {
            sec = secPart.toLongOrNull() ?: return null
            ms = 0
        }
        return ((h * 60 + m) * 60 + sec) * 1000 + ms
    }

    /**
     * 洗净字幕：丢掉起止倒挂/越界条目，按时间排序并强制单调不重叠。
     * 识别中途崩溃或 VAD 偏移错乱时，会产出 start > end、时间超出片长的脏条目，
     * 直接喂给播放器会出现"字幕乱跳/永不命中"。写盘前与读取时都要过一遍。
     */
    fun sanitize(cues: List<SrtCue>, durationMs: Long): List<SrtCue> {
        if (cues.isEmpty()) return emptyList()
        val max = if (durationMs > 0) durationMs else Long.MAX_VALUE
        val out = ArrayList<SrtCue>(cues.size)
        var cursor = 0L
        for (c in cues.sortedBy { it.startMs }) {
            val s = c.startMs.coerceIn(0L, max)
            val e = c.endMs.coerceIn(0L, max)
            if (e <= s) continue
            val s2 = maxOf(s, cursor)
            if (s2 >= e) continue
            out += c.copy(index = out.size + 1, startMs = s2, endMs = e)
            cursor = e
        }
        return out
    }

    /**
     * 判定一份字幕是否可信可用。
     *
     * 三道关：
     * 1) 脏条目占比 —— 起止倒挂或起点超出片长，超过 20% 判为坏文件（VAD 偏移累加的典型症状）；
     * 2) 覆盖度 —— 末条结束时间需覆盖到片长 40% 以上（短视频放宽到 5 条即可）；
     * 3) 洗完后不能为空。
     *
     * 不合格的字幕文件一律视为"没有字幕"：播放页不显示并提示重新生成，生成时重新生成覆盖。
     */
    fun isUsable(cues: List<SrtCue>, durationMs: Long): Boolean {
        if (cues.isEmpty()) return false
        val dirty = cues.count { it.startMs >= it.endMs || (durationMs > 0 && it.startMs > durationMs) }
        if (dirty * 5 > cues.size) return false
        val clean = sanitize(cues, durationMs)
        if (clean.isEmpty()) return false
        if (durationMs <= 0) return true
        val lastEnd = clean.last().endMs
        if (lastEnd >= durationMs * 4 / 10) return true
        // 台词极少的视频（≤4 条，典型是相机/微信小视频、监控片段）：
        // 样本太少，覆盖度不足不能当坏文件判据；时间轴不越界就认为可用，
        // 否则这类视频永远过不了校验 → 每次全量生成都被重新解码。
        if (clean.size <= 4 && lastEnd <= durationMs) return true
        return clean.size >= 5 && durationMs < 120_000
    }

    /** 生成标准 SRT 文本。 */
    fun serialize(cues: List<SrtCue>): String {
        val sb = StringBuilder()
        cues.forEachIndexed { i, cue ->
            sb.append(i + 1).append('\n')
            sb.append(formatTimestamp(cue.startMs)).append(" --> ")
                .append(formatTimestamp(cue.endMs)).append('\n')
            sb.append(cue.text).append("\n\n")
        }
        return sb.toString()
    }

    private fun formatTimestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val h = safe / 3_600_000
        val m = (safe % 3_600_000) / 60_000
        val s = (safe % 60_000) / 1000
        val milli = safe % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, milli)
    }

    /** 按播放位置二分查找当前应显示的字幕；无匹配返回 null。 */
    fun cueAt(cues: List<SrtCue>, positionMs: Long): SrtCue? {
        if (cues.isEmpty()) return null
        var lo = 0
        var hi = cues.lastIndex
        var candidate: SrtCue? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cue = cues[mid]
            if (cue.startMs <= positionMs) {
                candidate = cue
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return candidate?.takeIf { positionMs <= it.endMs }
    }
}
