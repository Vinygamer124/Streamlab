package com.vinny.streamcomposer.render

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.vinny.streamcomposer.MainActivity
import com.vinny.streamcomposer.R
import com.vinny.streamcomposer.data.MIN_MS
import com.vinny.streamcomposer.data.fmtClock
import com.vinny.streamcomposer.data.fmtDur
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

data class ExportUi(
    val running: Boolean = false,
    val progress: Float = 0f,        // 0..1
    val etaSec: Long = -1,
    val elapsedSec: Long = 0,
    val bytes: Long = 0,
    val predictedBytes: Long = -1,
    val speedX: Float = 0f,          // tốc độ so với thời gian thực (x)
    val done: Boolean = false,
    val error: String? = null,
    val outputPath: String? = null,
    val codecNote: String = "",
    // --- đang render tới đâu ---
    val segIndex: Int = -1,
    val segCount: Int = 0,
    val segTitle: String = "",
    val segKind: SegKind? = null,
    val segPosMs: Long = 0,
    val segDurMs: Long = 0,
    val segSpeedX: Float = 0f,       // tốc độ của riêng loại đoạn đang chạy (× thời gian thực)
    val streamClock: String = "",    // giờ trên đồng hồ live tương ứng vị trí đang render
    val warmup: Boolean = false,     // đang tua/khởi động nguồn của đoạn mới
    val etaApprox: Boolean = false,  // ETA còn ước lượng thô
    val preview: Bitmap? = null,
    val previewCaption: String = "",
)

object ExportBus {
    val state = MutableStateFlow(ExportUi())
    /** Bản đồ các khối của timeline (để vẽ thanh phân đoạn). */
    val layout = MutableStateFlow<List<SegInfo>>(emptyList())
    @Volatile var pending: ExportJob? = null
    /** UI bật khi màn Xuất đang mở – tránh tốn CPU lấy khung hình xem trước lúc không ai nhìn. */
    @Volatile var wantPreview = false
}

@UnstableApi
class ExportService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var wake: PowerManager.WakeLock? = null
    private var tracker: ExportTracker? = null
    private var grabber: PreviewGrabber? = null
    private var streamStartMin = 20 * 60
    private var lastPreviewAt = 0L
    private var lastPreviewBlock = -1
    private var startedAt = 0L
    private var totalMs = 1L
    private var outFile: File? = null
    private val holder = ProgressHolder()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancel()
            ACTION_START -> start()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        val job = ExportBus.pending
        if (job == null) { stopSelf(); return }
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notif("Đang chuẩn bị…", 0, true), type)

        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamComposer:export").apply { acquire(24L * 3600_000) }

        totalMs = job.timeline.totalMs.coerceAtLeast(1)
        outFile = File(job.outputPath).also { it.parentFile?.mkdirs(); if (it.exists()) it.delete() }
        startedAt = SystemClock.elapsedRealtime()
        val infos = SegInfos.build(job.timeline.segs, job.resources.games)
        tracker = ExportTracker(infos, totalMs, startedAt)
        grabber = PreviewGrabber(this, job.timeline.segs)
        streamStartMin = job.streamStartMin
        lastPreviewAt = 0L
        lastPreviewBlock = -1
        ExportBus.layout.value = infos
        ExportBus.state.value = ExportUi(running = true, outputPath = job.outputPath, segCount = infos.size)

        try {
            val composition = ExportEngine.buildComposition(this, job)
            transformer = ExportEngine.buildTransformer(this, job.settings, object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) = finish(null)
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) =
                    finish(describe(exportException))
            })
            transformer!!.start(composition, job.outputPath)
            handler.postDelayed(poll, 1000)
        } catch (t: Throwable) {
            finish("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val tr = transformer ?: return
            val trk = tracker ?: return
            val now = SystemClock.elapsedRealtime()
            val state = tr.getProgress(holder)
            val bytes = outFile?.length() ?: 0L
            val elapsed = (now - startedAt) / 1000
            val available = state == Transformer.PROGRESS_STATE_AVAILABLE
            // holder.progress chỉ là số nguyên 0..100 → tracker nội suy thành tiến độ mịn + biết đang ở khối nào
            val s = trk.update(now, if (available) holder.progress else 0, bytes)
            val blk = trk.blocks[s.blockIndex]
            val predicted = s.predictedBytes
            val speed = if (elapsed > 0) (s.progress * totalMs / 1000f) / elapsed else 0f
            ExportBus.state.value = ExportBus.state.value.copy(
                running = true, progress = s.progress, etaSec = s.etaSec, elapsedSec = elapsed,
                bytes = bytes, predictedBytes = predicted, speedX = speed,
                segIndex = s.blockIndex, segCount = trk.blocks.size, segTitle = blk.title, segKind = blk.kind,
                segPosMs = s.blockPosMs, segDurMs = blk.durationMs, segSpeedX = s.segSpeedX, warmup = s.warmup, etaApprox = s.etaApprox,
                streamClock = fmtClock(streamStartMin + (s.posMs / MIN_MS).toInt()),
            )
            if (available) {
                updateNotif(
                    "${blk.title}${if (s.warmup) " (đang chuẩn bị đoạn)" else ""} · ${"%.1f".format(s.progress * 100)}% · còn ${if (s.etaSec >= 0) (if (s.etaApprox) "~" else "") + fmtDur(s.etaSec * 1000) else "…"}",
                    (s.progress * 100).toInt(),
                )
            }
            if (ExportBus.wantPreview && (now - lastPreviewAt >= 3000L || s.blockIndex != lastPreviewBlock)) {
                lastPreviewAt = now
                lastPreviewBlock = s.blockIndex
                val caption = "${blk.title} · ${fmtDur(s.blockPosMs)} / ${fmtDur(blk.durationMs)}"
                grabber?.request(s.posMs) { bmp ->
                    ExportBus.state.value = ExportBus.state.value.copy(preview = bmp, previewCaption = caption)
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun cancel() {
        handler.removeCallbacks(poll)
        transformer?.cancel()
        outFile?.delete()
        ExportBus.state.value = ExportUi(error = "Đã huỷ")
        teardown()
    }

    private fun finish(error: String?) {
        handler.removeCallbacks(poll)
        val bytes = outFile?.length() ?: 0L
        if (error == null) outFile?.let { f ->
            // báo cho Thư viện/Gallery thấy file mới trong Movies
            try { android.media.MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), arrayOf("video/mp4"), null) } catch (_: Exception) {}
        }
        ExportBus.state.value = ExportBus.state.value.copy(
            running = false, done = error == null, error = error,
            progress = if (error == null) 1f else ExportBus.state.value.progress,
            etaSec = 0, bytes = bytes, predictedBytes = bytes,
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID + 1, notifFinal(if (error == null) "Xuất xong 🎉" else "Lỗi: $error"))
        teardown()
    }

    private fun describe(t: Throwable): String {
        val sb = StringBuilder()
        var c: Throwable? = t
        var n = 0
        while (c != null && n < 5) {
            sb.append(c.javaClass.simpleName).append(": ").append(c.message).append("\n")
            c.stackTrace.firstOrNull()?.let { sb.append("  at ").append(it).append("\n") }
            c = c.cause; n++
        }
        return sb.toString().trim()
    }

    private fun teardown() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        transformer = null
        grabber?.release(); grabber = null
        tracker = null
        ExportBus.pending = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---------- notification ----------
    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Xuất video", NotificationManager.IMPORTANCE_LOW))
    }

    private fun contentIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notif(text: String, pct: Int, indeterminate: Boolean = false): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ExportService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Stream Composer đang xuất")
            .setContentText(text)
            .setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, pct, indeterminate)
            .setContentIntent(contentIntent())
            .addAction(0, "Huỷ", cancel)
            .build()
    }

    private fun updateNotif(text: String, pct: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif(text, pct))
    }

    private fun notifFinal(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Stream Composer").setContentText(text)
            .setContentIntent(contentIntent()).setAutoCancel(true).build()

    companion object {
        const val ACTION_START = "com.vinny.streamcomposer.START"
        const val ACTION_CANCEL = "com.vinny.streamcomposer.CANCEL"
        private const val CHANNEL = "export"
        private const val NOTIF_ID = 42

        fun start(ctx: Context) = androidx.core.content.ContextCompat.startForegroundService(
            ctx, Intent(ctx, ExportService::class.java).setAction(ACTION_START)
        )
        fun cancel(ctx: Context) = ctx.startService(Intent(ctx, ExportService::class.java).setAction(ACTION_CANCEL))
    }
}
