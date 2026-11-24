package com.magplay.asr

import androidx.media3.common.C
import java.util.Arrays
import kotlin.math.max

/**
 * 在 ASR 后台线程把原始 PCM（任意采样率/声道/16bit float）转成 16kHz mono float。
 * 从旧的 PcmCaptureSink 迁移而来，避免在音频渲染线程做重采样。
 */
class PcmResampler {
    private val chunkSamples = 1600 // 100ms @16kHz
    private var outBuffer = FloatArray(chunkSamples)
    private var outSize = 0

    private var inputBuffer = FloatArray(16384)
    private var inputSize = 0
    private var srcPos = 0.0

    fun reset() {
        inputSize = 0
        srcPos = 0.0
        outSize = 0
    }

    /** 喂入一帧原始 PCM，满 100ms 时通过 onChunk 回调一次 16k mono 数据。 */
    fun feed(raw: RawPcm, onChunk: (FloatArray) -> Unit) {
        val mono = decodeToMono(raw) ?: return
        resampleAndEmit(raw.sampleRate, mono, onChunk)
    }

    /** 交错多声道 → 单声道 float（[-1,1]）。 */
    private fun decodeToMono(raw: RawPcm): FloatArray? {
        val bytes = raw.bytes
        val bytesPerSample = when (raw.encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return null
        }
        if (bytes.size < bytesPerSample) return null
        val totalSamples = bytes.size / bytesPerSample
        val ch = max(raw.channelCount, 1)
        val frames = totalSamples / ch
        if (frames <= 0) return null

        val out = FloatArray(frames)
        var offset = 0
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until ch) {
                val v = when (raw.encoding) {
                    C.ENCODING_PCM_16BIT -> {
                        val lo = bytes[offset].toInt() and 0xFF
                        val hi = bytes[offset + 1].toInt() and 0xFF
                        offset += 2
                        ((hi shl 8) or lo).toShort() / 32768f
                    }
                    C.ENCODING_PCM_FLOAT -> {
                        val bits = (bytes[offset].toInt() and 0xFF) or
                            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
                        offset += 4
                        java.lang.Float.intBitsToFloat(bits)
                    }
                    else -> 0f
                }
                sum += v
            }
            out[i] = sum / ch
        }
        return out
    }

    /** 线性插值重采样，满 100ms 回调一次。跨 buffer 保留未消费的尾部样本。 */
    private fun resampleAndEmit(sampleRate: Int, input: FloatArray, onChunk: (FloatArray) -> Unit) {
        if (inputSize + input.size > inputBuffer.size) {
            val newSize = max(inputBuffer.size * 2, inputSize + input.size)
            inputBuffer = Arrays.copyOf(inputBuffer, newSize)
        }
        System.arraycopy(input, 0, inputBuffer, inputSize, input.size)
        inputSize += input.size

        val ratio = sampleRate / 16000.0
        while (true) {
            val idx = srcPos.toInt()
            if (idx + 1 >= inputSize) break
            val frac = (srcPos - idx).toFloat()
            val cur = inputBuffer[idx]
            val next = inputBuffer[idx + 1]
            val value = cur + (next - cur) * frac

            outBuffer[outSize++] = value
            if (outSize >= chunkSamples) {
                onChunk(outBuffer.copyOf())
                outSize = 0
            }
            srcPos += ratio
        }

        // srcPos 是 Double，高采样率(如 48k，ratio=3)下一次推进可能跨过尾部，
        // toInt() 会大于 inputSize；若直接用作 drop，remain 变负 → inputSize 为负，
        // 下一帧 System.arraycopy 的 dstPos 为负而崩溃。必须夹在有效区间内。
        val drop = srcPos.toInt().coerceAtMost(inputSize)
        if (drop > 0) {
            val remain = inputSize - drop
            if (remain > 0) System.arraycopy(inputBuffer, drop, inputBuffer, 0, remain)
            inputSize = remain
            srcPos -= drop
        }
    }
}
