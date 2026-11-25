package com.magplay.bench

import android.content.Context
import android.net.Uri
import android.util.Log
import com.magplay.asr.AsrBackend
import com.magplay.subtitle.AudioDecoder
import com.magplay.subtitle.OfflineSubtitleGenerator
import com.magplay.subtitle.SrtCue
import com.magplay.subtitle.SrtFile
import java.io.File

/**
 * 模型对比测试：同一段音频，逐个后端跑一遍，写出 SRT 与耗时数据。
 *
 * 目的不是「算个分数」，而是让人能直接读同一句话在不同模型下的差别 ——
 * 论文里的 CER 是在干净数据集上测的，片库里是带 BGM、多人、方言混杂的真实音轨，
 * 只有在自己的素材上并排看，才知道换哪个模型值。
 */
object BenchRunner {

    data class Result(
        val backend: AsrBackend,
        val loadMs: Long,
        val runMs: Long,
        val audioSec: Double,
        val cueCount: Int,
        val charCount: Int,
        val error: String? = null,
    ) {
        val rtf: Double get() = if (audioSec > 0) runMs / 1000.0 / audioSec else 0.0
    }

    fun outDir(context: Context): File =
        File(context.getExternalFilesDir(null), "bench").apply { mkdirs() }

    /**
     * @param path 视频绝对路径
     * @param seconds 只测前 N 秒（控制耗时）
     * @param backends 要对比的后端
     * @param onProgress 进度回调（写通知用）
     */
    suspend fun run(
        context: Context,
        path: String,
        seconds: Int,
        backends: List<AsrBackend>,
        startSec: Int = 0,
        onProgress: (String) -> Unit = {},
    ): List<Result> {
        val out = outDir(context)
        out.listFiles()?.forEach { it.delete() }

        onProgress("解码 ${startSec}s 起的 ${seconds}s 音频…")
        val pcm = decodeHead(context, path, startSec, seconds)
            ?: return listOf(
                Result(backends.first(), 0, 0, 0.0, 0, 0, "音频解码失败：$path")
            )
        val audioSec = pcm.size / 16000.0
        Log.i(TAG, "decoded ${pcm.size} samples = ${audioSec}s")

        val results = mutableListOf<Result>()
        for (backend in backends) {
            onProgress("${backend.label} 识别中…")
            val gen = OfflineSubtitleGenerator(context, backend)
            val t0 = System.currentTimeMillis()
            val prepared = gen.prepare()
            val loadMs = System.currentTimeMillis() - t0
            if (!prepared) {
                results += Result(backend, loadMs, 0, audioSec, 0, 0, gen.lastError ?: "加载失败")
                gen.close()
                continue
            }
            val cues = mutableListOf<SrtCue>()
            val t1 = System.currentTimeMillis()
            try {
                var offset = 0
                while (offset < pcm.size) {
                    val end = minOf(offset + 60 * 16000, pcm.size)
                    val slice = pcm.copyOfRange(offset, end)
                    val chunk = AudioDecoder.AudioChunk(slice, offset * 1000L / 16000)
                    cues += gen.generate(chunk)
                    offset = end
                }
            } catch (t: Throwable) {
                Log.e(TAG, "bench ${backend.id} failed", t)
                results += Result(
                    backend, loadMs, System.currentTimeMillis() - t1, audioSec, 0, 0,
                    "识别异常：${t.message}",
                )
                gen.close()
                continue
            }
            val runMs = System.currentTimeMillis() - t1
            gen.close()

            val renumbered = cues.mapIndexed { i, c -> c.copy(index = i + 1) }
            File(out, "${backend.id}.srt").writeText(SrtFile.serialize(renumbered))
            results += Result(
                backend, loadMs, runMs, audioSec,
                renumbered.size, renumbered.sumOf { it.text.length },
            )
        }

        File(out, "report.md").writeText(render(results, path, audioSec))
        return results
    }

    private fun render(results: List<Result>, path: String, audioSec: Double): String = buildString {
        appendLine("# 模型对比")
        appendLine()
        appendLine("素材：`${File(path).name}`  取前 ${audioSec.toInt()} 秒")
        appendLine()
        appendLine("| 模型 | 加载 | 识别耗时 | RTF | 字幕条数 | 字数 | 备注 |")
        appendLine("|---|---|---|---|---|---|---|")
        for (r in results) {
            appendLine(
                "| ${r.backend.label} | ${r.loadMs / 1000.0} s | ${r.runMs / 1000.0} s | " +
                    "%.2f | %d | %d | %s |".format(r.rtf, r.cueCount, r.charCount, r.error ?: "")
            )
        }
    }

    /**
     * 取 [startSec, startSec+seconds) 的音频。
     * 解码总是从 0 开始（AudioDecoder 不支持 seek），前面的块直接丢弃 —— 音频解码很快，
     * 取一部片子中间 90 秒也就多花几秒。
     */
    private fun decodeHead(context: Context, path: String, startSec: Int, seconds: Int): FloatArray? {
        val limit = seconds * 16000
        val fromSample = startSec * 16000L
        val buf = ArrayList<FloatArray>()
        var total = 0
        var seen = 0L
        val duration = AudioDecoder.decodeStreaming(
            context = context,
            uri = Uri.fromFile(File(path)),
            shouldContinue = { total < limit },
        ) { chunk ->
            var data = chunk.pcm
            var chunkStart = seen
            seen += data.size
            if (chunkStart + data.size <= fromSample) return@decodeStreaming
            if (chunkStart < fromSample) {
                val cut = (fromSample - chunkStart).toInt()
                data = data.copyOfRange(cut, data.size)
                chunkStart = fromSample
            }
            if (total < limit) {
                val room = limit - total
                val take = if (data.size <= room) data else data.copyOf(room)
                buf += take
                total += take.size
            }
        }
        if (duration == null || total == 0) return null
        val out = FloatArray(total)
        var pos = 0
        for (a in buf) {
            System.arraycopy(a, 0, out, pos, a.size)
            pos += a.size
        }
        return out
    }

    private const val TAG = "AsrBench"
}
