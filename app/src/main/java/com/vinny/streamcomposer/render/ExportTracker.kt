package com.vinny.streamcomposer.render

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.vinny.streamcomposer.data.AssetSeg
import com.vinny.streamcomposer.data.Clips
import com.vinny.streamcomposer.data.CrashSeg
import com.vinny.streamcomposer.data.FreezeSeg
import com.vinny.streamcomposer.data.GameSeg
import com.vinny.streamcomposer.data.MediaRef
import com.vinny.streamcomposer.data.ReactionSeg
import com.vinny.streamcomposer.data.RestSeg
import com.vinny.streamcomposer.data.Seg

/** Loại đoạn – mỗi loại có tốc độ render khác nhau nên ETA tính riêng theo loại. */
enum class SegKind { GAME, REACTION, REST, CONNECT, COMEBACK, CRASH }

/** Một "khối" hiển thị cho người dùng (các đoạn loop 10 s liền nhau được gộp thành một khối). */
data class SegInfo(val kind: SegKind, val title: String, val durationMs: Long)

object SegInfos {
    fun build(segs: List<Seg>, games: List<MediaRef>): List<SegInfo> {
        val names = games.associate { it.uri to it.name }
        val out = ArrayList<SegInfo>()
        for (s in segs) {
            val info = when (s) {
                is GameSeg -> SegInfo(SegKind.GAME, "Gameplay · ${names[s.src] ?: "video game"}", s.durationMs)
                is ReactionSeg -> SegInfo(SegKind.REACTION, if (s.dinner) "Dinner reaction" else "Reaction", s.durationMs)
                is RestSeg -> SegInfo(SegKind.REST, "Chuyển cảnh: menu GTA 6 → PS5 → Rest mode", s.durationMs)
                is FreezeSeg -> SegInfo(SegKind.CRASH, "GTA crash: game đóng băng", s.durationMs)
                is CrashSeg -> SegInfo(SegKind.CRASH, "GTA crash: lỗi PS5", s.durationMs)
                is AssetSeg -> when (s.file) {
                    Clips.REST_B -> SegInfo(SegKind.REST, "Chuyển cảnh: Putting your PS5 into rest mode…", s.durationMs)
                    Clips.CONNECTING -> SegInfo(SegKind.CONNECT, "PS5 connecting…", s.durationMs)
                    Clips.CONNECTED -> SegInfo(SegKind.CONNECT, "PS5 connected", s.durationMs)
                    else -> SegInfo(SegKind.COMEBACK, "Come back soon", s.durationMs)
                }
            }
            val last = out.lastOrNull()
            if (last != null && last.kind == info.kind && last.title == info.title && info.kind == SegKind.COMEBACK) {
                out[out.size - 1] = last.copy(durationMs = last.durationMs + info.durationMs)
            } else out += info
        }
        return out
    }
}

/**
 * Media3 `ProgressHolder.progress` là SỐ NGUYÊN 0..100 → với live 3 giờ, 1% = ~108 s video; đoạn reaction render chậm
 * nên con số đứng yên rất lâu. Tracker này:
 *  - nội suy tiến độ mịn (0,1%) giữa hai lần số nguyên nhảy, dựa trên tốc độ đo được của đúng loại đoạn đang chạy;
 *  - cho biết đang ở khối nào (Game / Reaction / Rest mode / …) và vị trí trong khối;
 *  - ETA = cộng thời gian còn lại của TỪNG khối theo tốc độ riêng của loại đó (loại chưa đo được thì ước lượng thô, đánh dấu `etaApprox`);
 *  - nhận biết pha KHỞI ĐỘNG đoạn (`warmup`): khi vào một khối mới (nhất là Game cắt giữa video dài) bộ giải mã phải tua về keyframe
 *    gần nhất rồi giải mã bỏ các khung trước điểm cắt → encoder chưa có khung để ghi, tốc độ tụt rất thấp (vd 0,24×), file gần như không
 *    lớn thêm, rồi tự nhanh lại. Đây KHÔNG phải tốc độ thật của đoạn, nên: không đưa vào ước lượng tốc độ, không nội suy tiến độ tiếp,
 *    và UI báo "đang chuẩn bị đoạn" thay vì in con số 0,24×.
 */
class ExportTracker(val blocks: List<SegInfo>, private val totalMs: Long, startNowMs: Long) {
    private val starts = LongArray(blocks.size).also { a ->
        var acc = 0L
        blocks.forEachIndexed { i, b -> a[i] = acc; acc += b.durationMs }
    }
    private val nKinds = SegKind.values().size
    private val rate = DoubleArray(nKinds)     // ms thời gian thật / ms video, 0 = chưa đo
    private val byteRate = DoubleArray(nKinds) // byte / ms video, 0 = chưa đo
    private var lastInt = 0
    private var lastIntAt = startNowMs
    private var lastIntBytes = 0L
    private val t0 = startNowMs
    private var fineMax = 0f
    private var curBlock = 0
    private var skipNextStep = true            // bước % đầu tiên sau khi vào khối mới chứa thời gian khởi động → bỏ qua
    private var enteredAt = startNowMs

    data class Snap(
        val progress: Float,
        val posMs: Long,
        val blockIndex: Int,
        val blockPosMs: Long,
        val etaSec: Long,
        val segSpeedX: Float,
        val warmup: Boolean = false,          // đang tua/khởi động nguồn của đoạn mới, chưa có tốc độ thật
        val etaApprox: Boolean = false,       // ETA có chứa loại đoạn chưa đo được
        val predictedBytes: Long = -1L,
    )

    fun blockAt(posMs: Long): Int {
        var i = 0
        while (i + 1 < starts.size && starts[i + 1] <= posMs) i++
        return i
    }

    private fun estRate(kind: SegKind, now: Long, pInt: Int): Double {
        val known = rate[kind.ordinal]
        if (known > 0.0) return known
        // chưa đo loại này: lấy tốc độ Game (nếu có) hoặc tốc độ trung bình toàn quá trình, reaction ước lượng nặng hơn
        val doneMs = pInt / 100.0 * totalMs
        val base = if (rate[SegKind.GAME.ordinal] > 0.0) rate[SegKind.GAME.ordinal]
        else if (doneMs > 0) (now - t0) / doneMs else 0.0
        return base * (if (kind == SegKind.REACTION) 3.0 else 1.0)
    }

    fun update(now: Long, pIntRaw: Int, bytes: Long = 0L): Snap {
        val p = pIntRaw.coerceIn(lastInt, 100)
        val pBlock = blockAt((p / 100.0 * totalMs).toLong())
        if (pBlock != curBlock) { curBlock = pBlock; skipNextStep = true; enteredAt = now }
        if (p > lastInt) {
            val mediaDt = (p - lastInt) / 100.0 * totalMs
            val wallDt = (now - lastIntAt).toDouble()
            val startBlock = blockAt((lastInt / 100.0 * totalMs).toLong())
            val endBlock = blockAt((p / 100.0 * totalMs).toLong())
            // chỉ đo khi bước % nằm gọn trong MỘT khối và không phải bước đầu sau chuyển khối (có thời gian khởi động)
            if (mediaDt > 0 && wallDt > 0 && startBlock == endBlock && !skipNextStep) {
                val k = blocks[endBlock].kind.ordinal
                val r = wallDt / mediaDt
                rate[k] = if (rate[k] == 0.0) r else 0.6 * rate[k] + 0.4 * r
                val br = (bytes - lastIntBytes).coerceAtLeast(0L) / mediaDt
                byteRate[k] = if (byteRate[k] == 0.0) br else 0.6 * byteRate[k] + 0.4 * br
            }
            if (startBlock != endBlock) skipNextStep = true else skipNextStep = false
            lastInt = p
            lastIntAt = now
            lastIntBytes = bytes
        }
        val curKind = blocks[blockAt((p / 100.0 * totalMs).toLong())].kind
        val known = rate[curKind.ordinal] > 0.0
        val perPctWall = estRate(curKind, now, p) * totalMs / 100.0
        val sinceInt = (now - lastIntAt).toDouble()
        // Khởi động đoạn: vừa vào khối mới, chưa có số liệu thật, và số % đứng yên quá lâu so với mức thường gặp
        val warmup = skipNextStep && sinceInt > maxOf(6_000.0, if (known) 3.0 * perPctWall else 0.0)
        val frac = when {
            warmup -> 0.0                                  // không chạy trước thực tế khi đang tua nguồn
            perPctWall > 0 -> (sinceInt / perPctWall).coerceIn(0.0, 0.95)
            else -> 0.0
        }
        var fine = ((p + frac) / 100.0).toFloat().coerceAtMost(0.999f)
        if (fine < fineMax) fine = fineMax else fineMax = fine

        val pos = (fine * totalMs).toLong()
        val bi = blockAt(pos)
        var wall = 0.0
        var approx = false
        var bytesLeft = 0.0
        var bytesApprox = false
        for (j in bi until blocks.size) {
            val end = starts[j] + blocks[j].durationMs
            val remain = (end - maxOf(pos, starts[j])).coerceAtLeast(0L)
            val kd = blocks[j].kind.ordinal
            if (rate[kd] == 0.0 && remain > 0) approx = true
            wall += remain * estRate(blocks[j].kind, now, p)
            if (byteRate[kd] > 0.0) bytesLeft += remain * byteRate[kd] else if (remain > 0) bytesApprox = true
        }
        val eta = if (wall > 0.0) (wall / 1000.0).toLong() else -1L
        val r = rate[blocks[bi].kind.ordinal]
        return Snap(
            progress = fine, posMs = pos, blockIndex = bi,
            blockPosMs = (pos - starts[bi]).coerceAtLeast(0L),
            etaSec = eta, segSpeedX = if (r > 0.0 && !warmup) (1.0 / r).toFloat() else 0f,
            warmup = warmup, etaApprox = approx,
            // dung lượng cuối chỉ dự đoán khi mọi khối còn lại đã có tốc độ ghi byte thật của loại đó (Come back soon nhẹ ≠ Game nặng)
            predictedBytes = if (!bytesApprox && bytes > 0) bytes + bytesLeft.toLong() else -1L,
        )
    }
}

/**
 * Lấy khung hình NGUỒN đang được xử lý (không phải khung đã ghép overlay – Media3 không cho đọc khung đã render)
 * để người dùng thấy đang ở đâu. Chạy ở thread riêng, mỗi lần một yêu cầu, và chỉ khi màn Xuất đang mở.
 * Khung lấy theo keyframe gần nhất nên có thể lệch vài giây so với vị trí thật.
 */
class PreviewGrabber(private val ctx: Context, private val segs: List<Seg>) {
    private val starts = LongArray(segs.size).also { a ->
        var acc = 0L
        segs.forEachIndexed { i, s -> a[i] = acc; acc += s.durationMs }
    }
    private val thread = HandlerThread("preview-grab").also { it.start() }
    private val bg = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false
    private val meta by lazy { RestMeta(ctx) }

    fun request(posMs: Long, done: (Bitmap) -> Unit) {
        if (busy) return
        busy = true
        bg.post {
            val bmp = try { grab(posMs) } catch (_: Throwable) { null }
            busy = false
            if (bmp != null) main.post { done(bmp) }
        }
    }

    fun release() { thread.quitSafely() }

    private fun grab(posMs: Long): Bitmap? {
        if (segs.isEmpty()) return null
        var i = 0
        while (i + 1 < segs.size && starts[i + 1] <= posMs) i++
        val s = segs[i]
        val off = (posMs - starts[i]).coerceAtLeast(0L)
        return when (s) {
            is GameSeg -> frame(s.src, null, s.startMs + off)
            is ReactionSeg -> frame(s.src, null, off)
            is AssetSeg -> frame(null, s.file, off.coerceAtMost(9_900L))
            is RestSeg -> restPlate(off)
            is FreezeSeg -> frame(s.src, null, (s.posMs - 40L).coerceAtLeast(0L))
            is CrashSeg -> frame(null, Clips.CRASH, off.coerceAtMost(Clips.CRASH_MS - 100L))
        }
    }

    private fun frame(uri: Uri?, asset: String?, tMs: Long): Bitmap? {
        val r = MediaMetadataRetriever()
        var afd: AssetFileDescriptor? = null
        try {
            if (asset != null) {
                afd = ctx.assets.openFd("clips/$asset")
                r.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            } else r.setDataSource(ctx, uri!!)
            val tUs = tMs * 1000L
            return if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, PW, PH)
            } else {
                r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?.let { Bitmap.createScaledBitmap(it, PW, PH, true) }
            }
        } finally {
            try { r.release() } catch (_: Exception) {}
            try { afd?.close() } catch (_: Exception) {}
        }
    }

    /** Đoạn Rest mode: nền xanh tối + đúng tấm UI (plate) đang tới thời điểm đó. */
    private fun restPlate(offMs: Long): Bitmap {
        val out = Bitmap.createBitmap(PW, PH, Bitmap.Config.ARGB_8888)
        out.eraseColor(0xFF0B1220.toInt())
        val idx = meta.map[meta.frameAt(offMs * 1000L)]
        if (idx >= 0) {
            try {
                val bm = ctx.assets.open("rest/p%03d.webp".format(idx)).use { BitmapFactory.decodeStream(it) }
                if (bm != null) Canvas(out).drawBitmap(bm, null, Rect(0, 0, PW, PH), Paint(Paint.FILTER_BITMAP_FLAG))
            } catch (_: Exception) {}
        }
        return out
    }

    private companion object { const val PW = 480; const val PH = 270 }
}
