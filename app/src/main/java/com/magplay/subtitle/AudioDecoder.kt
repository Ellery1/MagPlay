package com.magplay.subtitle

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * 从视频 URI 提取音轨，解码并重采样为 16kHz mono float PCM。
 *
 * 内存策略（真正的流式）：
 * - 解码的同时做「声道混合 + 线性插值降采样」，只保留 16k 一份数据。
 * - 每攒满 5 分钟（15 秒短块见下）就通过 onChunk 回调交出去，交付后立即丢弃，
 *   调用方处理完这块即可回收。全程只驻留**一块** PCM，与视频总时长无关。
 * - 长视频（1 小时以上）也不会出现 OOM。
 */
object AudioDecoder {

    private const val TAG = "AudioDecoder"
    private const val TARGET_RATE = 16000

    /** 单块时长。取 60 秒：既保证回调次数不多，又把峰值内存压到约 3.8MB。 */
    private const val CHUNK_MS = 60 * 1000L
    private const val CHUNK_SAMPLES = (TARGET_RATE * CHUNK_MS / 1000L).toInt() // 960,000 ≈ 3.8MB

    /** 一块 16k 单声道 PCM。offsetMs = 该块相对视频开头的时间偏移。 */
    data class AudioChunk(val pcm: FloatArray, val offsetMs: Long)

    /**
     * 流式解码：每攒满一块就回调一次 onChunk（在调用线程同步执行）。
     * 调用方在回调里做 VAD + 识别，处理完返回后该块内存即可被回收。
     *
     * @return 视频时长（毫秒）；失败返回 null。
     */
    fun decodeStreaming(
        context: Context,
        uri: Uri,
        onProgress: ((Int) -> Unit)? = null,
        shouldContinue: () -> Boolean = { true },
        onChunk: (AudioChunk) -> Unit,
    ): Long? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = selectAudioTrack(extractor) ?: run {
                Log.w(TAG, "no audio track found")
                return null
            }
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            val collector = StreamingCollector(srcRate, channelCount, onChunk)
            val timeoutUs = 10_000L
            var inputEos = false
            var outputEos = false
            var lastProgress = -1
            var decodedBytes = 0L

            while (!outputEos) {
                if (!shouldContinue()) {
                    Log.i(TAG, "decode cancelled by caller")
                    break
                }
                if (!inputEos) {
                    val inIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                            decodedBytes += sampleSize
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 忽略，沿用输入格式
                } else if (outIndex >= 0) {
                    val outBuffer = codec.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        collector.append(outBuffer)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputEos = true
                    }
                }

                if (onProgress != null && durationUs > 0 && decodedBytes > 0) {
                    val est = (decodedBytes * 1_000_000L / srcRate / channelCount / 2)
                        .coerceAtMost(durationUs)
                    val p = (est * 100 / durationUs).toInt().coerceIn(0, 99)
                    if (p != lastProgress) {
                        lastProgress = p
                        onProgress(p)
                    }
                }
            }

            collector.finish()
            onProgress?.invoke(100)
            Log.i(TAG, "decoded ${collector.totalSamples} samples @${TARGET_RATE}Hz, duration=${durationMs(durationUs)}ms")
            return durationMs(durationUs)
        } catch (t: Throwable) {
            Log.e(TAG, "decode failed", t)
            return null
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }
    }

    private fun durationMs(us: Long): Long = if (us > 0) us / 1000 else 0L

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    /**
     * 边解码边流式降采样：每来一帧输入样本，利用它与前一帧做线性插值，
     * 按 ratio = srcRate/TARGET_RATE 产出 16k 输出样本，攒满一块就回调交出去。
     * 峰值只保留**一块** 16k 音频。
     */
    private class StreamingCollector(
        srcRate: Int,
        channels: Int,
        private val onChunk: (AudioChunk) -> Unit,
    ) {
        private val ratio = srcRate.toDouble() / TARGET_RATE
        private var pos = 0.0          // 下一个输出样本在输入帧轴上的位置
        private var prevVal = 0f
        private var havePrev = false
        private var frameIndex = 0

        private var curChunk = FloatArray(CHUNK_SAMPLES)
        private var curCount = 0
        private var emitted = 0L
        private val ch = max(channels, 1)

        val totalSamples: Long get() = emitted

        fun append(buffer: ByteBuffer) {
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val bytesPerFrame = 2 * ch // MediaCodec PCM 16bit 输出
            val frames = bytes.size / bytesPerFrame
            var off = 0
            for (i in 0 until frames) {
                var sum = 0
                for (c in 0 until ch) {
                    val lo = bytes[off++].toInt() and 0xFF
                    val hi = bytes[off++].toInt() and 0xFF
                    sum += ((hi shl 8) or lo).toShort().toInt()
                }
                val x = (sum.toFloat() / ch) / 32768f

                // ratio>=1 时每次至多产出 1 个输出样本；guard 防极端比例下死循环
                var guard = 0
                while (pos <= frameIndex.toDouble() && guard++ < 8) {
                    val v = if (!havePrev || frameIndex == 0) {
                        x
                    } else {
                        val t = (pos - (frameIndex - 1)).toFloat().coerceIn(0f, 1f)
                        prevVal + (x - prevVal) * t
                    }
                    emit(v)
                    pos += ratio
                }
                prevVal = x
                havePrev = true
                frameIndex++
            }
        }

        private fun emit(v: Float) {
            if (curCount == curChunk.size) flushChunk()
            curChunk[curCount++] = v
            emitted++
        }

        private fun flushChunk() {
            if (curCount == 0) return
            val data = if (curCount == curChunk.size) curChunk else curChunk.copyOf(curCount)
            val offsetMs = (emitted - curCount) * 1000L / TARGET_RATE
            // 先把新缓冲准备好再回调，避免回调里持有旧引用导致无法回收
            curChunk = FloatArray(CHUNK_SAMPLES)
            curCount = 0
            onChunk(AudioChunk(data, offsetMs))
        }

        fun finish() = flushChunk()
    }
}
