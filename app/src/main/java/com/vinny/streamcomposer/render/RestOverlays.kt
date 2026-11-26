package com.vinny.streamcomposer.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextureOverlay
import com.google.common.collect.ImmutableList
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Chuyển cảnh "mở menu GTA 6 → PS5 → Rest mode" dựng bằng lớp phủ:
 *
 *   nền đen (rest_a.mp4, chỉ mang âm thanh giao diện đã lọc)
 *   └ [1] khung game cuối làm mờ (nền)
 *   └ [2] khung game nét, mờ dần 0,3–0,6 s (hiệu ứng game đóng băng rồi nhoè)
 *   └ [3] "plate": UI GTA/PS5 có kênh alpha (assets/rest/ (file .webp)) – đã tách sạch khỏi video tham chiếu
 *   └ [4] đồng hồ PS5 vẽ bằng code, giờ khớp giờ live
 */
class RestMeta(ctx: Context) {
    val fps: Int
    val map: IntArray
    val clockAlpha: FloatArray
    val clockRight: Float
    val clockBaseline: Float
    val clockSize: Float
    val clockTrackingEm: Float
    val clockColor: Int
    val fontAsset: String

    init {
        val j = JSONObject(ctx.assets.open("rest/meta.json").bufferedReader().use { it.readText() })
        fps = j.getInt("fps")
        val m = j.getJSONArray("map"); map = IntArray(m.length()) { m.getInt(it) }
        val c = j.getJSONArray("clockAlpha"); clockAlpha = FloatArray(c.length()) { c.getDouble(it).toFloat() }
        val ck = j.getJSONObject("clock")
        clockRight = ck.getDouble("right").toFloat()
        clockBaseline = ck.getDouble("baseline").toFloat()
        clockSize = ck.getDouble("textSize").toFloat()
        clockTrackingEm = ck.getDouble("trackingEm").toFloat()
        val col = ck.getJSONArray("color")
        clockColor = Color.rgb(col.getInt(0), col.getInt(1), col.getInt(2))
        fontAsset = ck.getString("fontAsset")
    }

    fun frameAt(tUs: Long): Int = min(map.size - 1, max(0, (tUs * fps / 1_000_000L).toInt()))
}

/** Khung game cuối trước khi dừng: bản nét + bản mờ (lazy – chỉ tạo khi export tới đoạn này). */
class FrozenFrame(private val ctx: Context, private val src: Uri?, private val posMs: Long) {
    val sharp: Bitmap by lazy { load().first }
    val blurred: Bitmap by lazy { load().second }
    private var cache: Pair<Bitmap, Bitmap>? = null

    @Synchronized
    private fun load(): Pair<Bitmap, Bitmap> {
        cache?.let { return it }
        val raw = try {
            if (src == null) null else {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, src)
                    r.getFrameAtTime(posMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: r.getFrameAtTime(posMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally { r.release() }
            }
        } catch (_: Exception) { null }
        val res = if (raw == null) {
            val black = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.BLACK) }
            black to black
        } else {
            val sharp = fill(raw, OUT_W, OUT_H, 0, 0)
            sharp to makeBlur(raw)
        }
        cache = res
        return res
    }

    /** Phóng đầy khung 16:9, cắt phần thừa ở giữa (giống Presentation LAYOUT_SCALE_TO_FIT_WITH_CROP). */
    private fun fill(src: Bitmap, w: Int, h: Int, cropTop: Int, cropBottom: Int): Bitmap {
        val sh = src.height - cropTop - cropBottom
        val s = max(w.toFloat() / src.width, h.toFloat() / sh)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val m = Matrix()
        m.postTranslate(0f, -cropTop.toFloat())
        m.postScale(s, s)
        m.postTranslate((w - src.width * s) / 2f, (h - sh * s) / 2f)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Cắt viền đen letterbox → thu nhỏ 480×270 → box blur 3 lượt (≈ Gaussian σ9) → vignette tối viền. */
    private fun makeBlur(raw: Bitmap): Bitmap {
        val probe = Bitmap.createScaledBitmap(raw, 64, 64, true)
        val rows = IntArray(64) { y ->
            var s = 0
            for (x in 0 until 64) { val p = probe.getPixel(x, y); s += (Color.red(p) + Color.green(p) + Color.blue(p)) / 3 }
            s / 64
        }
        var top = 0; var bottom = 0
        while (top < 20 && rows[top] < 6) top++
        while (bottom < 20 && rows[63 - bottom] < 6) bottom++
        val cropTop = (top * raw.height / 64.0).toInt(); val cropBottom = (bottom * raw.height / 64.0).toInt()
        val small = fill(raw, 480, 270, cropTop, cropBottom)
        val w = 480; val h = 270
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        boxBlur(px, w, h, 9); boxBlur(px, w, h, 9); boxBlur(px, w, h, 9)
        for (y in 0 until h) for (x in 0 until w) {
            val nx = (x - w / 2f) / (w / 2f); val ny = (y - h / 2f) / (h / 2f)
            val r = sqrt(nx * nx + ny * ny)
            val v = (1.15f - 0.45f * r * r * 1f).coerceIn(0.35f, 1f)
            val p = px[y * w + x]
            px[y * w + x] = Color.rgb((Color.red(p) * v).toInt(), (Color.green(p) * v).toInt(), (Color.blue(p) * v).toInt())
        }
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.setPixels(px, 0, w, 0, 0, w, h)
        return Bitmap.createScaledBitmap(b, OUT_W, OUT_H, true)
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        for (pass in 0..1) {
            val src = if (pass == 0) px else tmp
            val dst = if (pass == 0) tmp else px
            val outer = if (pass == 0) h else w
            val inner = if (pass == 0) w else h
            for (o in 0 until outer) {
                var sr = 0; var sg = 0; var sb = 0
                fun at(i: Int): Int = if (pass == 0) src[o * w + i.coerceIn(0, inner - 1)] else src[i.coerceIn(0, inner - 1) * w + o]
                for (i in -r..r) { val p = at(i); sr += Color.red(p); sg += Color.green(p); sb += Color.blue(p) }
                val n = 2 * r + 1
                for (i in 0 until inner) {
                    val out = Color.rgb(sr / n, sg / n, sb / n)
                    if (pass == 0) dst[o * w + i] = out else dst[i * w + o] = out
                    val a = at(i + r + 1); val b = at(i - r)
                    sr += Color.red(a) - Color.red(b); sg += Color.green(a) - Color.green(b); sb += Color.blue(a) - Color.blue(b)
                }
            }
        }
    }
}

/**
 * Media3 đưa cho overlay `presentationTimeUs` theo THỜI GIAN CỦA CẢ COMPOSITION (đã cộng độ dời của các đoạn trước),
 * không phải thời gian từ đầu đoạn. Với Rest mode nằm giữa stream, t luôn lớn hơn 4,6 s nên `frameAt()` bị kẹt ở
 * khung cuối -> người xem chỉ thấy tấm "Putting your P…" mờ thay vì menu GTA → PS5 → Rest.
 * SegClock đổi về thời gian tương đối (0 = khung đầu của đoạn), chạy đúng cả khi Media3 đã cho thời gian tương đối.
 */
class SegClock(private val offsetUs: Long) {
    private var base = Long.MIN_VALUE

    @Synchronized
    fun rel(tUs: Long): Long {
        // đoạn Rest luôn nằm sau mở màn >= 3 phút nên offset >> 4,6 s: phân biệt được hai kiểu thời gian
        if (offsetUs > 1_000_000L && tUs < offsetUs - 1_000_000L) return tUs.coerceAtLeast(0L)
        if (base == Long.MIN_VALUE) base = tUs
        return (tUs - base).coerceAtLeast(0L)
    }
}

private val BLANK: Bitmap by lazy { Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888) }

/** Bitmap tạo lười, tuỳ chọn alpha theo thời gian; trả bitmap trong suốt khi hết hiệu lực. */
@UnstableApi
class LazyOverlay(
    private val clock: SegClock,
    private val bitmap: () -> Bitmap,
    private val alpha: (Long) -> Float,
    private val activeUntilUs: Long = Long.MAX_VALUE,
) : BitmapOverlay() {
    override fun getBitmap(presentationTimeUs: Long): Bitmap =
        if (clock.rel(presentationTimeUs) > activeUntilUs) BLANK else bitmap()

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        OverlaySettings.Builder().setAlphaScale(alpha(clock.rel(presentationTimeUs)).coerceIn(0f, 1f)).build()
}

/** UI GTA/PS5 (plate WebP có alpha, premultiplied khi Android giải mã) – chọn khung theo thời gian. */
@UnstableApi
class PlateOverlay(private val ctx: Context, private val meta: RestMeta, private val clock: SegClock) : BitmapOverlay() {
    private var lastIdx = Int.MIN_VALUE
    private var lastBmp: Bitmap = BLANK

    @Synchronized
    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val idx = meta.map[meta.frameAt(clock.rel(presentationTimeUs))]
        if (idx == lastIdx) return lastBmp
        lastBmp = if (idx < 0) BLANK else try {
            val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            ctx.assets.open("rest/p%03d.webp".format(idx)).use { BitmapFactory.decodeStream(it, null, o) } ?: BLANK
        } catch (_: Exception) { BLANK }
        lastIdx = idx
        return lastBmp
    }
}

object PS5Clock {
    /** 12 giờ kiểu PS5: "9:00 PM", "12:42 PM". */
    fun format(minOfDay: Int): String {
        val m = Math.floorMod(minOfDay, 1440)
        val h = m / 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "%d:%02d %s".format(h12, m % 60, if (h < 12) "AM" else "PM")
    }
}

@UnstableApi
object RestOverlays {
    /** Danh sách overlay (dưới → trên) cho phần đầu chuyển cảnh. */
    fun build(ctx: Context, seg: com.vinny.streamcomposer.data.RestSeg): ImmutableList<TextureOverlay> {
        val meta = RestMeta(ctx)
        val clock = SegClock(seg.offsetMs * 1000L)
        val frozen = FrozenFrame(ctx, seg.frozenSrc, seg.frozenPosMs)

        // game nét → mờ dần (0,3–0,6 s)
        val sharpAlpha = { t: Long ->
            val s = t / 1_000_000f
            1f - ((s - 0.3f) / 0.3f).coerceIn(0f, 1f)
        }
        val text = PS5Clock.format(seg.clockMin)
        val clockBmp by lazy { drawClock(ctx, meta, text) }
        val clockAlpha = { t: Long -> meta.clockAlpha[meta.frameAt(t)] } // t đã là thời gian tương đối (LazyOverlay quy đổi)

        return ImmutableList.of<TextureOverlay>(
            LazyOverlay(clock, { frozen.blurred }, { 1f }),
            LazyOverlay(clock, { frozen.sharp }, sharpAlpha, activeUntilUs = 700_000L),
            PlateOverlay(ctx, meta, clock),
            LazyOverlay(clock, { clockBmp }, clockAlpha),
        )
    }

    private fun drawClock(ctx: Context, meta: RestMeta, text: String): Bitmap {
        val bmp = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = try { Typeface.createFromAsset(ctx.assets, meta.fontAsset) } catch (_: Exception) { Typeface.SANS_SERIF }
            textSize = meta.clockSize
            letterSpacing = meta.clockTrackingEm
            color = meta.clockColor
            textAlign = Paint.Align.RIGHT
        }
        Canvas(bmp).drawText(text, meta.clockRight + 1f, meta.clockBaseline.toFloat() + 1f, p)
        return bmp
    }
}
