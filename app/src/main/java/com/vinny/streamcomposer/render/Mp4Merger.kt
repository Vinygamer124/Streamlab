package com.vinny.streamcomposer.render

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Nối lần lượt các phần MP4 (do Transformer xuất) vào MỘT file cuối, KHÔNG mã hoá lại (chỉ chép mẫu đã nén).
 * Gọi [append] sau mỗi phần xong rồi xoá phần đó → ổ đĩa chỉ cần thêm chỗ cho ~1 phần (vài trăm MB), không nhân đôi.
 *
 * Mọi phần dùng cùng encoder + cấu hình nên video/âm thanh khớp định dạng; nếu không khớp thì báo lỗi rõ ràng
 * thay vì ghép ra file hỏng. Phải gọi từ MỘT thread (không thread-safe).
 */
class Mp4Merger(private val outPath: String) {
    private var muxer: MediaMuxer? = null
    private var started = false
    private var vOut = -1
    private var aOut = -1
    private var hasAudio = false
    private var vMime = ""
    private var vW = 0
    private var vH = 0
    private var aRate = 0
    private var aCh = 0
    private var offsetUs = 0L
    private var lastAudioUs = -1L
    private var samples = 0L
    private val buf: ByteBuffer = ByteBuffer.allocateDirect(BUF_SIZE)
    private val info = MediaCodec.BufferInfo()

    /** Tổng thời lượng đã ghép (ms). */
    val mergedMs: Long get() = offsetUs / 1000L

    /** Chép toàn bộ mẫu của [partPath] vào cuối file cuối. */
    fun append(partPath: String) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(partPath)
            var vIn = -1
            var aIn = -1
            var vFmt: MediaFormat? = null
            var aFmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && vIn < 0) { vIn = i; vFmt = f }
                else if (mime.startsWith("audio/") && aIn < 0) { aIn = i; aFmt = f }
            }
            if (vIn < 0 || vFmt == null) throw IllegalStateException("Phần xuất không có video")

            if (muxer == null) openMuxer(vFmt, aFmt) else checkSame(vFmt, aFmt)
            val m = muxer!!

            ex.selectTrack(vIn)
            if (aIn >= 0) ex.selectTrack(aIn)

            var lastVideoUs = 0L
            // một lượt đọc cả hai track → mẫu ra theo đúng thứ tự thời gian, muxer không phải đệm lệch
            while (true) {
                val tr = ex.sampleTrackIndex
                if (tr < 0) break
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val t = ex.sampleTime
                var flags = 0
                if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                if (tr == vIn) {
                    info.set(0, size, t + offsetUs, flags)
                    m.writeSampleData(vOut, buf, info)
                    if (t > lastVideoUs) lastVideoUs = t
                } else if (aOut >= 0) {
                    var pts = t + offsetUs
                    // âm thanh phải tăng nghiêm ngặt: đầu phần sau có thể chồng vài ms lên cuối phần trước
                    if (pts <= lastAudioUs) pts = lastAudioUs + 1
                    lastAudioUs = pts
                    info.set(0, size, pts, flags)
                    m.writeSampleData(aOut, buf, info)
                }
                samples++
                ex.advance()
            }

            val trackDur = if (vFmt.containsKey(MediaFormat.KEY_DURATION)) vFmt.getLong(MediaFormat.KEY_DURATION) else 0L
            // độ dài phần = độ dài track video (nếu thiếu thì lấy mẫu cuối + 1 khung 30 fps)
            offsetUs += maxOf(trackDur, lastVideoUs + FRAME_US)
        } finally {
            try { ex.release() } catch (_: Exception) {}
        }
    }

    /** Đóng file cuối (ghi chỉ mục moov). Sau lệnh này file xem được. */
    fun finish() {
        val m = muxer ?: return
        muxer = null
        try {
            if (started) m.stop()
        } finally {
            try { m.release() } catch (_: Exception) {}
        }
    }

    /** Đóng file cuối cố gắng giữ phần đã ghép. @return true nếu file có nội dung xem được. */
    fun finishQuietly(): Boolean {
        val had = started && samples > 0
        return try { finish(); had } catch (_: Throwable) { false }
    }

    /** Bỏ dở: đóng và xoá file cuối. */
    fun abort() {
        try { finish() } catch (_: Throwable) {}
        try { File(outPath).delete() } catch (_: Throwable) {}
    }

    private fun openMuxer(v: MediaFormat, a: MediaFormat?) {
        val m = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer = m
        vMime = v.getString(MediaFormat.KEY_MIME) ?: ""
        vW = v.getInteger(MediaFormat.KEY_WIDTH)
        vH = v.getInteger(MediaFormat.KEY_HEIGHT)
        vOut = m.addTrack(v)
        hasAudio = a != null
        if (a != null) {
            aRate = a.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            aCh = a.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            aOut = m.addTrack(a)
        }
        m.start()
        started = true
    }

    private fun checkSame(v: MediaFormat, a: MediaFormat?) {
        val mime = v.getString(MediaFormat.KEY_MIME) ?: ""
        if (mime != vMime || v.getInteger(MediaFormat.KEY_WIDTH) != vW || v.getInteger(MediaFormat.KEY_HEIGHT) != vH) {
            throw IllegalStateException("Video phần này khác định dạng phần đầu ($mime ${v.getInteger(MediaFormat.KEY_WIDTH)}x${v.getInteger(MediaFormat.KEY_HEIGHT)})")
        }
        if ((a != null) != hasAudio) throw IllegalStateException("Phần này ${if (a == null) "thiếu" else "có thêm"} âm thanh so với phần đầu")
        if (a != null) {
            val r = a.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val c = a.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (r != aRate || c != aCh) throw IllegalStateException("Âm thanh phần này $r Hz/${c}ch, phần đầu $aRate Hz/${aCh}ch")
        }
    }

    private companion object {
        const val BUF_SIZE = 8 * 1024 * 1024
        const val FRAME_US = 33_333L
    }
}
