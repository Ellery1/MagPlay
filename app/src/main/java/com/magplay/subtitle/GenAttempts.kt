package com.magplay.subtitle

import android.content.Context
import com.magplay.data.VideoItem
import org.json.JSONObject
import java.io.File

/**
 * 记录「已经试过但拿不到可用字幕」的视频，避免每次全量生成都重新解码同一批文件。
 *
 * 背景：无对白的短视频（相机/微信小视频）跑完 ASR 没有任何句子，不写字幕文件；
 * 台词极少的视频写出的字幕条数太少、覆盖不到片长 40%，校验判为不可用。
 * 两种情况都会让这批视频每次都被列为「待生成」，白烧几小时 CPU 且永远跑不完。
 *
 * 键里带 size + duration：文件被重新下载/替换后自动失效，会重新尝试。
 */
object GenAttempts {

    private const val FILE_NAME = "gen_attempts.json"

    /** 确定性结果：同样的音频再跑一次结果一样，第一次就跳过。 */
    private val DETERMINISTIC = setOf("nospeech", "noaudio")

    /** 非确定性结果（解码报错、写盘失败、字幕过短）允许再试一次。 */
    private const val MAX_ATTEMPTS = 2

    private val lock = Any()

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun read(context: Context): MutableMap<String, JSONObject> {
        val f = file(context)
        if (!f.exists()) return mutableMapOf()
        return try {
            val root = JSONObject(f.readText(Charsets.UTF_8))
            val out = mutableMapOf<String, JSONObject>()
            root.keys().forEach { k ->
                (root.opt(k) as? JSONObject)?.let { out[k] = it }
            }
            out
        } catch (_: Throwable) {
            mutableMapOf()
        }
    }

    private fun write(context: Context, data: Map<String, JSONObject>) {
        try {
            val root = JSONObject()
            data.forEach { (k, v) -> root.put(k, v) }
            val f = file(context)
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (_: Throwable) {
        }
    }

    fun key(video: VideoItem): String =
        "${video.relativePath}|${video.name}|${video.size}|${video.durationMs}"

    /**
     * 该视频是否应跳过。
     * @return 跳过原因（nospeech/noaudio/short/invalid/writefail），null 表示需要生成。
     */
    fun shouldSkip(context: Context, video: VideoItem): String? = synchronized(lock) {
        val rec = read(context)[key(video)] ?: return null
        val result = rec.optString("r")
        val n = rec.optInt("n", 0)
        val skip = if (result in DETERMINISTIC) n >= 1 else n >= MAX_ATTEMPTS
        if (skip) result else null
    }

    /** 记录一次失败（累积次数）。 */
    fun record(context: Context, video: VideoItem, result: String) = synchronized(lock) {
        val data = read(context)
        val k = key(video)
        val old = data[k]
        val n = (old?.optInt("n", 0) ?: 0) + 1
        data[k] = JSONObject()
            .put("r", result)
            .put("n", n)
            .put("t", System.currentTimeMillis())
        write(context, data)
    }

    /** 成功后清掉记录（该视频已有可用字幕）。 */
    fun forget(context: Context, video: VideoItem) = synchronized(lock) {
        val data = read(context)
        if (data.remove(key(video)) != null) write(context, data)
    }

    /** 重新生成前清空全部记录。 */
    fun clear(context: Context) = synchronized(lock) {
        file(context).delete()
    }

    /** 供 UI 提示：这批视频里有多少个已被标记跳过。 */
    fun skippedCount(context: Context, videos: List<VideoItem>): Int = synchronized(lock) {
        val data = read(context)
        videos.count { v ->
            val rec = data[key(v)] ?: return@count false
            val result = rec.optString("r")
            val n = rec.optInt("n", 0)
            if (result in DETERMINISTIC) n >= 1 else n >= MAX_ATTEMPTS
        }
    }
}
