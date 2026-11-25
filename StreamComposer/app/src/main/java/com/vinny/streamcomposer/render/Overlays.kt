package com.vinny.streamcomposer.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Kích thước canvas xuất cố định 1080p. */
const val OUT_W = 1920
const val OUT_H = 1080

/** Cửa sổ trong suốt của khung reaction (px trên ảnh 1920x1080): x 64..1463, y 174..961. */
object ReactionWindow {
    const val LEFT = 64f
    const val TOP = 174f
    const val RIGHT = 1464f
    const val BOTTOM = 962f

    /** Thu video 1920x1080 vào cửa sổ (NDC: scale đều rồi tịnh tiến tới tâm cửa sổ). */
    fun matrix(): MatrixTransformation {
        val s = (RIGHT - LEFT) / OUT_W
        val cx = ((LEFT + RIGHT) / 2f) / OUT_W * 2f - 1f
        val cy = 1f - ((TOP + BOTTOM) / 2f) / OUT_H * 2f
        return MatrixTransformation {
            Matrix().apply { setScale(s, s); postTranslate(cx, cy) }
        }
    }
}

/**
 * Đường đồng mức (topographic contour) animated, tự vẽ bằng code – KHÔNG dùng video.
 *
 * Trường độ cao h(x,y,t) là tổng các sóng sin có tần số thời gian là BỘI NGUYÊN của 2π/loopSec,
 * nên sau đúng loopSec giây khung hình trùng khớp 100% -> loop mượt tuyệt đối.
 * Đường đồng mức = marching squares trên lưới thô; mỗi 5 đường vẽ đậm (index contour).
 */
class ContourRenderer(
    val width: Int = 960,
    val height: Int = 540,
    private val region: RectF = RectF(0f, 0f, 960f, 540f),
    private val loopSec: Float = 20f,
    private val step: Float = 0.17f,
    private val cell: Float = 6f,
) {
    private val cols = ceil(region.width() / cell).toInt()
    private val rows = ceil(region.height() / cell).toInt()
    private val field = FloatArray((cols + 1) * (rows + 1))
    private val thin = FloatBuf()
    private val thick = FloatBuf()
    private val thinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 0.9f; color = Color.argb(165, 255, 255, 255); strokeCap = Paint.Cap.ROUND
    }
    private val thickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.7f; color = Color.argb(215, 255, 255, 255); strokeCap = Paint.Cap.ROUND
    }

    fun draw(bmp: Bitmap, tSec: Float) {
        val tt = (2.0 * Math.PI * ((tSec % loopSec) / loopSec)).toFloat()
        val aspect = region.width() / region.height()
        for (j in 0..rows) {
            val ny = j.toFloat() / rows * 1.8f
            for (i in 0..cols) {
                val nx = i.toFloat() / cols * 1.8f * aspect
                val wx = nx + 0.45f * sin(1.3f * ny + tt)
                val wy = ny + 0.45f * cos(1.1f * nx - tt)
                field[j * (cols + 1) + i] =
                    sin(1.9f * wx + 0.7f * wy + tt) +
                    0.8f * sin(1.1f * wx - 1.7f * wy - 2f * tt + 1.3f) +
                    0.6f * sin(2.9f * wx + 2.3f * wy + tt + 4.1f) +
                    0.4f * sin(4.3f * wy - 3.1f * wx - tt + 2.2f)
            }
        }
        thin.clear(); thick.clear()
        for (j in 0 until rows) for (i in 0 until cols) {
            val v00 = field[j * (cols + 1) + i]
            val v10 = field[j * (cols + 1) + i + 1]
            val v01 = field[(j + 1) * (cols + 1) + i]
            val v11 = field[(j + 1) * (cols + 1) + i + 1]
            val lo = minOf(minOf(v00, v10), minOf(v01, v11))
            val hi = maxOf(maxOf(v00, v10), maxOf(v01, v11))
            val kMin = ceil(lo / step).toInt()
            val kMax = floor(hi / step).toInt()
            for (k in kMin..kMax) {
                val buf = if (Math.floorMod(k, 5) == 0) thick else thin
                cellLines(k * step, region.left + i * cell, region.top + j * cell, v00, v10, v11, v01, buf)
            }
        }
        bmp.eraseColor(Color.TRANSPARENT)
        val c = Canvas(bmp)
        c.drawLines(thin.a, 0, thin.n, thinPaint)
        c.drawLines(thick.a, 0, thick.n, thickPaint)
    }

    private fun cellLines(l: Float, x0: Float, y0: Float, v00: Float, v10: Float, v11: Float, v01: Float, o: FloatBuf) {
        var idx = 0
        if (v00 > l) idx = idx or 1
        if (v10 > l) idx = idx or 2
        if (v11 > l) idx = idx or 4
        if (v01 > l) idx = idx or 8
        if (idx == 0 || idx == 15) return
        // A = cạnh trên (00-10), B = phải (10-11), C = dưới (01-11), D = trái (00-01)
        val ax = x0 + cell * ((l - v00) / (v10 - v00)); val ay = y0
        val bx = x0 + cell; val by = y0 + cell * ((l - v10) / (v11 - v10))
        val cx = x0 + cell * ((l - v01) / (v11 - v01)); val cy = y0 + cell
        val dx = x0; val dy = y0 + cell * ((l - v00) / (v01 - v00))
        when (idx) {
            1, 14 -> o.line(dx, dy, ax, ay)
            2, 13 -> o.line(ax, ay, bx, by)
            3, 12 -> o.line(dx, dy, bx, by)
            4, 11 -> o.line(bx, by, cx, cy)
            6, 9 -> o.line(ax, ay, cx, cy)
            7, 8 -> o.line(dx, dy, cx, cy)
            5 -> { // 00 và 11 cao
                if ((v00 + v10 + v11 + v01) / 4f > l) { o.line(ax, ay, bx, by); o.line(cx, cy, dx, dy) }
                else { o.line(dx, dy, ax, ay); o.line(bx, by, cx, cy) }
            }
            10 -> { // 10 và 01 cao
                if ((v00 + v10 + v11 + v01) / 4f > l) { o.line(dx, dy, ax, ay); o.line(bx, by, cx, cy) }
                else { o.line(ax, ay, bx, by); o.line(cx, cy, dx, dy) }
            }
        }
    }
}

private class FloatBuf {
    var a = FloatArray(16384)
    var n = 0
    fun clear() { n = 0 }
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
        if (n + 4 > a.size) a = a.copyOf(a.size * 2)
        a[n++] = x1; a[n++] = y1; a[n++] = x2; a[n++] = y2
    }
}

/**
 * Overlay animated cho Media3. Trả về bitmap KHÁC instance mỗi khung (xoay vòng 3 bitmap)
 * vì Media3 chỉ upload lại texture khi instance bitmap đổi.
 */
@UnstableApi
class ContourOverlay(private val r: ContourRenderer) : BitmapOverlay() {
    private val pool = Array(3) { Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888) }
    private var idx = 0

    @Synchronized
    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        idx = (idx + 1) % pool.size
        val b = pool[idx]
        r.draw(b, presentationTimeUs / 1_000_000f)
        return b
    }

    companion object {
        /** Contour chỉ nằm trong cửa sổ khung reaction (toạ độ bitmap 960x540 = nửa toạ độ 1080p). */
        fun forReactionWindow() = ContourOverlay(
            ContourRenderer(
                region = RectF(ReactionWindow.LEFT / 2, ReactionWindow.TOP / 2, ReactionWindow.RIGHT / 2, ReactionWindow.BOTTOM / 2)
            )
        )
    }
}

object BitmapLoader {
    /** Đọc PNG từ assets hoặc Uri người dùng, ép về 1920x1080. */
    fun load(ctx: Context, uri: Uri?, assetPath: String): Bitmap {
        val raw = if (uri != null) {
            ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
        } else {
            ctx.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
        }
        return if (raw.width == OUT_W && raw.height == OUT_H) raw
        else Bitmap.createScaledBitmap(raw, OUT_W, OUT_H, true)
    }
}
