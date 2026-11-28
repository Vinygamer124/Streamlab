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
                    Clips.CRASH_HOLD -> SegInfo(SegKind.CRASH, "GTA crash: màn Report Problem (nền loop)", s.durationMs)
                    Clips.STARTING_SOON -> SegInfo(SegKind.COMEBACK, "Starting soon", s.durationMs)
                    Clips.BE_RIGHT_BACK -> SegInfo(SegKind.COMEBACK, "Be right back", s.durationMs)
                    Clips.THANKS -> SegInfo(SegKind.COMEBACK, "Thanks for watching", s.durationMs)
                    else -> SegInfo(SegKind.COMEBACK, "Come back soon", s.durationMs)
                }
            }
            val last = out.lastOrNull()
            if (last != null && last.kind == info.kind && last.title == info.title &&
                (info.kind == SegKind.COMEBACK || info.kind == SegKind.CRASH)) {
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
 *  - ETA = cộng thời gian còn lại của TỪNG khối theo tốc độ riêng của loại đó (loại chưa đo được thì ước lượng thô).
 */
class ExportTracker(val blocks: List<SegInfo>, private val totalMs: Long, startNowMs: Long) {
    private val starts = LongArray(blocks.size).also { a ->
        var acc = 0L
        blocks.forEachIndexed { i, b -> a[i] = acc; acc += b.durationMs }
    }
    private val rate = DoubleArray(SegKind.values().size) // ms thời gian thật / ms video, 0 = chưa đo
    private var lastP = 0.0
    private var lastIntAt = startNowMs
    private val t0 = startNowMs
    private var fineMax = 0f

    data class Snap(
        val progress: Float,
        val posMs: Long,
        val blockIndex: Int,
        val blockPosMs: Long,
        val etaSec: Long,
        val segSpeedX: Float,
    )

    fun blockAt(posMs: Long): Int {
        var i = 0
        while (i + 1 < starts.size && starts[i + 1] <= posMs) i++
        return i
    }

    private fun estRate(kind: SegKind, now: Long, pct: Double): Double {
        val known = rate[kind.ordinal]
        if (known > 0.0) return known
        // chưa đo loại này: lấy tốc độ Game (nếu có) hoặc tốc độ trung bình toàn quá trình, reaction ước lượng nặng hơn
        val doneMs = pct / 100.0 * totalMs
        val base = if (rate[SegKind.GAME.ordinal] > 0.0) rate[SegKind.GAME.ordinal]
        else if (doneMs > 0) (now - t0) / doneMs else 0.0
        return base * (if (kind == SegKind.REACTION) 3.0 else 1.0)
    }

    /**
     * @param pctRaw   tiến độ TOÀN BỘ (0..100). Khi xuất theo từng phần thì đây là số thực ghép từ % của phần đang chạy.
     * @param stepPct  độ mịn danh nghĩa của nguồn tiến độ (đơn vị %, toàn bộ). 1.0 = số nguyên 0..100 như Media3 trả về;
     *                 xuất theo phần thì mỗi 1% của phần = (độ dài phần / tổng) %.
     */
    fun update(now: Long, pctRaw: Double, stepPct: Double = 1.0): Snap {
        val step = stepPct.coerceIn(0.001, 1.0)
        val p = pctRaw.coerceIn(lastP, 100.0)
        if (p > lastP + 1e-9) {
            val mediaDt = (p - lastP) / 100.0 * totalMs
            val wallDt = (now - lastIntAt).toDouble()
            if (mediaDt > 0 && wallDt > 0) {
                val mid = ((lastP + p) / 2.0 / 100.0 * totalMs).toLong()
                val k = blocks[blockAt(mid)].kind.ordinal
                val r = wallDt / mediaDt
                rate[k] = if (rate[k] == 0.0) r else 0.6 * rate[k] + 0.4 * r
            }
            lastP = p
            lastIntAt = now
        }
        val curKind = blocks[blockAt((p / 100.0 * totalMs).toLong())].kind
        val perStepWall = estRate(curKind, now, p) * totalMs * step / 100.0
        val frac = if (perStepWall > 0) ((now - lastIntAt) / perStepWall).coerceIn(0.0, 0.95) else 0.0
        var fine = ((p + frac * step) / 100.0).toFloat().coerceAtMost(0.999f)
        if (fine < fineMax) fine = fineMax else fineMax = fine

        val pos = (fine * totalMs).toLong()
        val bi = blockAt(pos)
        var wall = 0.0
        for (j in bi until blocks.size) {
            val end = starts[j] + blocks[j].durationMs
            val remain = (end - maxOf(pos, starts[j])).coerceAtLeast(0L)
            wall += remain * estRate(blocks[j].kind, now, p)
        }
        val eta = if (wall > 0.0) (wall / 1000.0).toLong() else -1L
        val r = rate[blocks[bi].kind.ordinal]
        return Snap(
            progress = fine, posMs = pos, blockIndex = bi,
            blockPosMs = (pos - starts[bi]).coerceAtLeast(0L),
            etaSec = eta, segSpeedX = if (r > 0.0) (1.0 / r).toFloat() else 0f,
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
            is AssetSeg -> {
                val cap = if (s.file == Clips.CRASH_HOLD) Clips.CRASH_HOLD_LOOP_MS - 100L else 9_900L
                frame(null, s.file, (s.startMs + off).coerceAtMost(cap))
            }
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
