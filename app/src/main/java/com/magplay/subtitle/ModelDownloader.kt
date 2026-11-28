package com.magplay.subtitle

import android.content.Context
import com.magplay.asr.AsrBackend
import com.magplay.asr.AsrModels
import com.magplay.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * R4：从自建静态服务下载离线字幕模型到 App 内部私有目录。
 *
 * 逐文件串行：写 `<name>.part` → 校验 size + SHA256 → rename 成正式名。
 * 失败/取消只清当前文件的 .part，已装好的文件不受影响。
 */
object ModelDownloader {

    /** 服务器根路径；固定公网 IP，明文 HTTP 仅在网络安全配置中对该 IP 放通。 */
    const val BASE_URL = "http://47.103.215.190:8080/magplay/firered_aed"

    data class ManifestFile(val name: String, val size: Long, val sha256: String)
    data class Manifest(val version: Int, val files: List<ManifestFile>)

    sealed interface State {
        data object Idle : State
        data class Downloading(
            val fileName: String,
            val fileIndex: Int,
            val fileCount: Int,
            val percent: Int,
            /** 整包已下载字节数 / 整包总字节数（含已完成的文件），用于显示"320.5 / 1200 MB" */
            val doneBytes: Long = 0L,
            val totalBytes: Long = 0L,
        ) : State
        data class Installed(val version: Int) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    @Volatile
    private var cancelled = false

    private const val CONNECT_MS = 20_000
    private const val READ_MS = 30_000
    private const val BUF = 64 * 1024

    fun cancel() { cancelled = true }

    /** 是否已安装（三个文件齐且非空）。 */
    fun isInstalled(context: Context): Boolean =
        AsrModels.isAvailable(context, AsrBackend.FIRERED_AED)

    suspend fun install(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isInstalled(context)) {
            _state.value = State.Installed(1)
            return@withContext true
        }
        cancelled = false
        try {
            val manifest = fetchManifest()
            val dir = AsrModels.dirOf(context, AsrBackend.FIRERED_AED).apply { mkdirs() }
            val totalBytes = manifest.files.sumOf { it.size }
            var baseDone = 0L
            manifest.files.forEachIndexed { index, f ->
                if (cancelled) throw InterruptedException("已取消")
                val dest = File(dir, f.name)
                if (dest.isFile && dest.length() == f.size && sha256(dest) == f.sha256) {
                    baseDone += f.size
                    return@forEachIndexed
                }
                downloadOne(f, dest, index, manifest.files.size, baseDone, totalBytes)
                baseDone += f.size
            }
            _state.value = State.Installed(manifest.version)
            AppLog.i("ModelDl", "模型安装完成 v${manifest.version}")
            true
        } catch (e: InterruptedException) {
            _state.value = State.Idle
            AppLog.i("ModelDl", "下载已取消")
            false
        } catch (t: Throwable) {
            _state.value = State.Failed(t.message ?: "下载失败")
            AppLog.e("ModelDl", "下载失败", t)
            false
        }
    }

    private fun fetchManifest(): Manifest {
        val text = URL("$BASE_URL/manifest.json").readTextCompat()
        // 极简 JSON 解析：只取本服务生成的固定格式，不引第三方库
        val version = Regex("\"version\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 1
        val files = mutableListOf<ManifestFile>()
        val re = Regex(
            "\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"size\"\\s*:\\s*(\\d+)\\s*,\\s*\"sha256\"\\s*:\\s*\"([0-9a-fA-F]+)\"",
        )
        re.findAll(text).forEach { m ->
            files += ManifestFile(m.groupValues[1], m.groupValues[2].toLong(), m.groupValues[3])
        }
        if (files.isEmpty()) error("模型清单为空或格式错误")
        return Manifest(version, files)
    }

    private fun downloadOne(
        f: ManifestFile,
        dest: File,
        index: Int,
        total: Int,
        baseDone: Long,
        totalBytes: Long,
    ) {
        val part = File(dest.parentFile, "${dest.name}.part")
        var urlConnection: HttpURLConnection? = null
        try {
            urlConnection = (URL("$BASE_URL/${f.name}").openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                connect()
            }
            if (urlConnection.responseCode != HttpURLConnection.HTTP_OK) {
                error("HTTP ${urlConnection.responseCode}：${f.name}")
            }
            urlConnection.inputStream.use { input ->
                RandomAccessFile(part, "rw").use { out ->
                    out.setLength(0L)
                    val buf = ByteArray(BUF)
                    var read: Int
                    var done = 0L
                    var lastPct = -1
                    while (input.read(buf).also { read = it } != -1) {
                        if (cancelled) throw InterruptedException("已取消")
                        out.write(buf, 0, read)
                        done += read
                        val pct = (done * 100f / f.size).roundToInt().coerceAtMost(100)
                        if (pct != lastPct) {
                            lastPct = pct
                            _state.value = State.Downloading(
                                fileName = f.name,
                                fileIndex = index + 1,
                                fileCount = total,
                                percent = pct,
                                doneBytes = baseDone + done,
                                totalBytes = totalBytes,
                            )
                        }
                    }
                }
            }
            if (part.length() != f.size) error("${f.name} 大小不符：${part.length()} != ${f.size}")
            val actual = sha256(part)
            if (!actual.equals(f.sha256, ignoreCase = true)) {
                error("${f.name} SHA256 校验失败")
            }
            if (!part.renameTo(dest)) error("${f.name} 安装失败（无法重命名）")
        } finally {
            urlConnection?.disconnect()
            if (!dest.isFile) part.delete()
        }
    }

    private fun URL.readTextCompat(): String =
        (openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
        }.inputStream.bufferedReader().use { it.readText() }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUF)
            var read: Int
            while (input.read(buf).also { read = it } != -1) md.update(buf, 0, read)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
