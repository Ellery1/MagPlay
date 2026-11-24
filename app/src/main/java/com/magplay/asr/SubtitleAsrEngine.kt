package com.magplay.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 设备端流式 ASR 引擎（sherpa-onnx / 流式 Zipformer2 CTC 中文 int8）。
 *
 * 线程模型：
 * - 音频渲染线程：PcmCaptureSink 只做 memcpy，把 RawPcm 投入 rawQueue 后立即返回。
 * - 解码线程（独占）：从 rawQueue 取原始字节 → 重采样到 16k mono → acceptWaveform → decode。
 *
 * native 对象归属（修复 SIGSEGV）：
 * - 之前 release() 是"取消解码协程后立刻在另一个线程 release stream/recognizer"，
 *   cancel 是协作式的，解码线程可能正卡在 rec.decode(s) 的 native 调用里，
 *   此时释放 native 对象 = 野指针，直接 SIGSEGV（日志表现为 DefaultDispatch 线程崩溃）。
 * - 现在所有 native 调用只在解码线程内发生：release() 先 cancelAndJoin 等解码线程真正退出，
 *   再释放 native 对象；reset() 只置标志，由解码线程自己复位。
 * - 引擎自带作用域，不依赖调用方（Compose 的 rememberCoroutineScope 会随页面销毁被取消，
 *   那样 release 的收尾协程根本不会执行，模型就泄漏了）。
 */
class SubtitleAsrEngine(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
    private val onText: (text: String, isFinal: Boolean) -> Unit,
    private val onStatus: (status: Status) -> Unit,
) {
    enum class Status { LOADING, READY, ERROR, RELEASED }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    @Volatile
    private var stream: OnlineStream? = null

    private var decodeJob: Job? = null
    private val rawQueue = ArrayBlockingQueue<RawPcm>(256)
    private val resampler = PcmResampler()
    private val running = AtomicBoolean(false)
    private val resetRequested = AtomicBoolean(false)

    // 诊断计数
    private val rawReceived = AtomicLong(0)
    private val chunksDecoded = AtomicLong(0)

    // 文本去重：相同 partial 不重复回调，避免主线程 post 风暴
    private var lastText = ""
    private var lastFinal = false

    private fun emitText(text: String, isFinal: Boolean) {
        if (text == lastText && isFinal == lastFinal) return
        lastText = text
        lastFinal = isFinal
        onText(text, isFinal)
    }

    @Volatile
    var isReady: Boolean = false
        private set

    /** 后台加载模型，不阻塞 UI。 */
    fun load() {
        if (!running.compareAndSet(false, true)) return
        onStatus(Status.LOADING)
        decodeJob = engineScope.launch {
            try {
                val modelConfig = OnlineModelConfig(
                    zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = "models/asr/model.int8.onnx"),
                    tokens = "models/asr/tokens.txt",
                    bpeVocab = "models/asr/bbpe.model",
                    modelType = "zipformer2ctc",
                    modelingUnit = "bpe",
                    numThreads = 1, // 单线程，降低 CPU 争抢，避免拖垮音视频渲染
                )
                val config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80, dither = 0f),
                    modelConfig = modelConfig,
                    enableEndpoint = true,
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(false, 0.8f, 0f),
                        rule2 = EndpointRule(true, 0.5f, 0f),
                        rule3 = EndpointRule(false, 0f, 12f),
                    ),
                    decodingMethod = "greedy_search",
                    maxActivePaths = 1,
                )
                Log.i(TAG, "load: creating recognizer")
                val rec = OnlineRecognizer(context.assets, config)
                val s = rec.createStream("")
                recognizer = rec
                stream = s
                isReady = true
                onStatus(Status.READY)
                Log.i(TAG, "load: model ready, entering decode loop")

                var lastStatMs = System.currentTimeMillis()
                while (isActive && running.get()) {
                    if (resetRequested.compareAndSet(true, false)) {
                        rawQueue.clear()
                        resampler.reset()
                        rec.reset(s)
                        lastText = ""
                        lastFinal = false
                        onText("", true)
                    }

                    // 原始字节在这里重采样，满 100ms 得到一帧 16k mono
                    val raw = rawQueue.poll(50, TimeUnit.MILLISECONDS)
                    if (raw == null) {
                        maybeLogStat { "idle, rawQ=${rawQueue.size}" }
                        continue
                    }
                    rawReceived.incrementAndGet()
                    resampler.feed(raw) { pcm16k ->
                        // 第二参是输入采样率(16000)，不是样本数
                        s.acceptWaveform(pcm16k, 16000)
                        var decoded = 0
                        while (rec.isReady(s) && decoded < 16) {
                            rec.decode(s)
                            decoded++
                            chunksDecoded.incrementAndGet()
                        }
                        val result = rec.getResult(s)
                        emitText(result.text, false)
                        if (rec.isEndpoint(s)) {
                            emitText(rec.getResult(s).text, true)
                            rec.reset(s)
                        }
                    }
                    maybeLogStat { "raw=${rawReceived.get()} chunks=${chunksDecoded.get()} rawQ=${rawQueue.size}" }
                }
                Log.i(TAG, "decode loop exited normally")
            } catch (t: Throwable) {
                Log.e(TAG, "ASR load/decode failed", t)
                onStatus(Status.ERROR)
            } finally {
                isReady = false
            }
        }
    }

    /** 每 3 秒打印一次诊断统计，避免日志刷屏。 */
    private var lastStatAt = 0L
    private inline fun maybeLogStat(msg: () -> String) {
        val now = System.currentTimeMillis()
        if (now - lastStatAt >= 3000) {
            lastStatAt = now
            Log.d(TAG, "STAT ${msg()}")
        }
    }

    /** 音频线程调用：只入队原始字节，零计算、非阻塞。队列满则丢弃最旧。 */
    fun acceptRaw(raw: RawPcm) {
        if (!running.get()) return
        if (rawQueue.remainingCapacity() == 0) rawQueue.poll()
        rawQueue.offer(raw)
    }

    /** seek / 倍速变化时清空识别状态。只置标志，由解码线程自己复位 native 状态。 */
    fun reset() {
        rawQueue.clear()
        resetRequested.set(true)
    }

    /**
     * 释放 native 资源；调用后不可再用，需重新 new 一个引擎。
     * 必须等解码线程完全退出后再 release，否则就是"边用边释放"的野指针。
     */
    fun release() {
        running.set(false)
        isReady = false
        rawQueue.clear()
        val job = decodeJob
        decodeJob = null
        engineScope.launch {
            try {
                job?.cancelAndJoin()
            } catch (t: Throwable) {
                Log.w(TAG, "join decode job failed", t)
            }
            try {
                stream?.release()
                recognizer?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "ASR release failed", t)
            } finally {
                stream = null
                recognizer = null
                onStatus(Status.RELEASED)
                Log.i(TAG, "ASR released")
                engineScope.cancel()
            }
        }
    }

    companion object {
        private const val TAG = "SubtitleAsr"
    }
}
