package com.magplay.subtitle

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes

/**
 * 检测视频容器是否自带字幕轨（TEXT track）。
 *
 * 只 MediaExtractor 打开容器读轨道 MIME，不解码音视频，单个视频通常几毫秒。
 * 这类视频本身已有可显示的软字幕（如 text/3gpp-tt），再跑 ASR 生成是浪费，
 * 批量生成时应跳过。
 *
 * 读失败（文件损坏/URI 失效）时返回 false，让它照常进入 ASR 流程——
 * 宁可多生成一个，也不要因为检测失败而漏掉视频。
 */
object SubtitleTrackDetector {

    fun hasTextTrack(context: Context, uri: Uri): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?: continue
                if (MimeTypes.getTrackType(mime) == C.TRACK_TYPE_TEXT) return true
            }
            return false
        } catch (_: Throwable) {
            return false
        } finally {
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
        }
    }
}
