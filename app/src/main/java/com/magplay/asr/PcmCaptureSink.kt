package com.magplay.asr

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/** 一帧从音轨截获的原始 PCM，连同格式信息交给 ASR 线程处理。 */
data class RawPcm(
    val bytes: ByteArray,
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: Int,
)

/**
 * 从 ExoPlayer 音频渲染线程截获原始 PCM。
 *
 * 关键：handleBuffer 运行在音频渲染线程，这里只做一次连续内存拷贝（native memcpy）
 * 后立即返回，绝不做逐样本解码/重采样/识别等重活——否则会造成音频 underrun，
 * 表现为视频卡顿甚至不播放。解码与重采样全部交给 ASR 后台线程的 PcmResampler。
 */
class PcmCaptureSink(
    private val onRawPcm: (RawPcm) -> Unit,
) : TeeAudioProcessor.AudioBufferSink {

    @Volatile
    private var enabled = false
    @Volatile
    private var sampleRate = 48000
    @Volatile
    private var channelCount = 2
    @Volatile
    private var encoding = C.ENCODING_PCM_16BIT

    /** 诊断计数：音频线程实际送出的 buffer 数，供 logcat 观察链路是否通畅。 */
    val receivedCount = AtomicLong(0)

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.sampleRate = if (sampleRateHz > 0) sampleRateHz else 48000
        this.channelCount = if (channelCount > 0) channelCount else 1
        this.encoding = encoding
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!enabled || buffer.remaining() <= 0) return
        // 音频线程唯一开销：一次连续内存拷贝，随后立即交出，不做任何浮点运算
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        receivedCount.incrementAndGet()
        onRawPcm(RawPcm(bytes, sampleRate, channelCount, encoding))
    }
}
