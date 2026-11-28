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
import android.util.Log
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
import com.vinny.streamcomposer.data.Timeline
import com.vinny.streamcomposer.data.fmtClock
import com.vinny.streamcomposer.data.fmtDur
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

    // --- xuất theo từng phần (mỗi phần một Transformer; lỗi thì thử lại đúng phần đó) ---
    private var job: ExportJob? = null
    private var chunks: List<ExportChunk> = emptyList()
    private var chunkIdx = 0
    private var attempt = 1
    private var doneMs = 0L            // video đã ghép xong vào file cuối (ms)
    private var chunkPct = 0           // % (0..100) của phần đang chạy
    private var chunkFile: File? = null
    private var merger: Mp4Merger? = null
    private var mergeExec: ExecutorService? = null
    private var cancelled = false
    private var ended = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancel()
            ACTION_START -> start()
        }
        return START_NOT_STICKY
    }

    private fun partsDir() = File(cacheDir, "export_parts")

    private fun start() {
        val job = ExportBus.pending
        if (job == null) { stopSelf(); return }
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notif("Đang chuẩn bị…", 0, true), type)

        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamComposer:export").apply { acquire(24L * 3600_000) }

        this.job = job
        cancelled = false
        ended = false
        totalMs = job.timeline.totalMs.coerceAtLeast(1)
        outFile = File(job.outputPath).also { it.parentFile?.mkdirs(); if (it.exists()) it.delete() }
        partsDir().deleteRecursively()
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
            chunks = ChunkPlanner.plan(job.timeline.segs)
            chunkIdx = 0
            attempt = 1
            doneMs = 0L
            chunkPct = 0
            if (chunks.size > 1) {
                merger = Mp4Merger(job.outputPath)
                mergeExec = Executors.newSingleThreadExecutor()
            }
            handler.postDelayed(poll, 1000)
            runChunk()
        } catch (t: Throwable) {
            finish("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Chạy phần [chunkIdx] (lần thử [attempt]). Một phần duy nhất thì ghi thẳng ra file cuối như trước. */
    private fun runChunk() {
        val job = job ?: return
        if (cancelled || ended) return
        val chunk = chunks[chunkIdx]
        val single = chunks.size == 1
        val target = if (single) File(job.outputPath) else File(partsDir(), "part_%03d.mp4".format(chunkIdx))
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        chunkFile = target
        chunkPct = 0
        setNote(if (single) "" else "Phần ${chunkIdx + 1}/${chunks.size}" + if (attempt > 1) " · thử lại lần $attempt/$MAX_ATTEMPTS" else "")
        try {
            val sub = job.copy(timeline = Timeline(chunk.segs, emptyList()))
            val composition = ExportEngine.buildComposition(this, sub)
            val tr = ExportEngine.buildTransformer(this, job.settings, object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) = onChunkDone()
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) =
                    onChunkError(exportException)
            })
            transformer = tr
            tr.start(composition, target.absolutePath)
        } catch (t: Throwable) {
            onChunkError(t)
        }
    }

    private fun onChunkDone() {
        if (cancelled || ended) return
        transformer = null
        chunkPct = 100
        val file = chunkFile
        val m = merger
        if (chunks.size == 1 || file == null || m == null) { finishOk(); return }
        setNote("Đang ghép phần ${chunkIdx + 1}/${chunks.size} vào file cuối…")
        val exec = mergeExec ?: return
        val part = chunkIdx
        exec.execute {
            val err = try { m.append(file.absolutePath); null } catch (t: Throwable) { t }
            file.delete()
            handler.post {
                if (cancelled || ended) return@post
                if (err != null) {
                    Log.e(TAG, "Ghép phần ${part + 1} lỗi", err)
                    failAll("Ghép phần ${part + 1}/${chunks.size} lỗi: ${describe(err)}")
                    return@post
                }
                doneMs += chunks[part].durationMs
                chunkIdx = part + 1
                attempt = 1
                chunkPct = 0
                if (chunkIdx >= chunks.size) finishOk() else runChunk()
            }
        }
    }

    private fun onChunkError(t: Throwable) {
        if (cancelled || ended) return
        transformer = null
        chunkFile?.delete()
        val code = (t as? ExportException)?.errorCode
        Log.e(TAG, "Phần ${chunkIdx + 1}/${chunks.size} lỗi (lần $attempt, mã $code)", t)
        if (attempt < MAX_ATTEMPTS) {
            val failed = attempt
            attempt++
            val waitMs = if (failed == 1) 2_000L else 6_000L
            setNote("⚠ Phần ${chunkIdx + 1}/${chunks.size} lỗi (${brief(t)}) → thử lại lần $attempt/$MAX_ATTEMPTS sau ${waitMs / 1000}s")
            handler.postDelayed({ runChunk() }, waitMs)
        } else {
            failAll("Phần ${chunkIdx + 1}/${chunks.size} lỗi $MAX_ATTEMPTS lần liên tiếp.\n${describe(t)}")
        }
    }

    /** Xong hết: đóng file cuối (ghi chỉ mục) rồi báo thành công. */
    private fun finishOk() {
        val m = merger
        val exec = mergeExec
        if (m == null || exec == null) { finish(null); return }
        setNote("Đang đóng file cuối…")
        exec.execute {
            val err = try { m.finish(); null } catch (t: Throwable) { t }
            handler.post {
                if (cancelled || ended) return@post
                if (err != null) failAll("Không đóng được file cuối: ${describe(err)}") else finish(null)
            }
        }
    }

    /** Lỗi không cứu được: đóng file cuối để giữ lại phần đã xuất xong (xem được), rồi báo lỗi. */
    private fun failAll(msg: String) {
        val kept = doneMs
        val saved = merger?.finishQuietly() ?: false
        val path = job?.outputPath
        finish(if (saved && kept > 0 && path != null) "$msg\nĐã giữ lại phần đầu dài ${fmtDur(kept)} trong $path" else msg)
    }

    private fun setNote(text: String) {
        ExportBus.state.value = ExportBus.state.value.copy(codecNote = text)
    }

    private fun brief(t: Throwable): String {
        var c: Throwable = t
        var n = 0
        while (c.cause != null && n < 6) { c = c.cause!!; n++ }
        val code = (t as? ExportException)?.errorCode
        val msg = (c.message ?: "").lineSequence().firstOrNull().orEmpty().take(60)
        return (if (code != null) "mã $code · " else "") + c.javaClass.simpleName + (if (msg.isNotEmpty()) ": $msg" else "")
    }

    private val poll = object : Runnable {
        override fun run() {
            val trk = tracker ?: return
            if (ended) return
            val now = SystemClock.elapsedRealtime()
            val tr = transformer
            var available = false
            if (tr != null) {
                available = tr.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE
                if (available) chunkPct = holder.progress.coerceIn(0, 100)
            }
            val chunkMs = chunks.getOrNull(chunkIdx)?.durationMs ?: 0L
            val pct = ((doneMs + chunkMs * chunkPct / 100.0) / totalMs * 100.0).coerceIn(0.0, 100.0)
            // 1% của phần đang chạy = (độ dài phần / tổng) % toàn bộ
            val stepPct = if (chunkMs > 0) chunkMs.toDouble() / totalMs else 1.0
            val elapsed = (now - startedAt) / 1000
            val bytes = (outFile?.length() ?: 0L) + if (chunks.size > 1) (chunkFile?.length() ?: 0L) else 0L
            val s = trk.update(now, pct, stepPct)
            val blk = trk.blocks[s.blockIndex]
            val predicted = if (s.progress > 0.01f) (bytes / s.progress).toLong() else -1L
            val speed = if (elapsed > 0) (s.progress * totalMs / 1000f) / elapsed else 0f
            ExportBus.state.value = ExportBus.state.value.copy(
                running = true, progress = s.progress, etaSec = s.etaSec, elapsedSec = elapsed,
                bytes = bytes, predictedBytes = predicted, speedX = speed,
                segIndex = s.blockIndex, segCount = trk.blocks.size, segTitle = blk.title, segKind = blk.kind,
                segPosMs = s.blockPosMs, segDurMs = blk.durationMs, segSpeedX = s.segSpeedX,
                streamClock = fmtClock(streamStartMin + (s.posMs / MIN_MS).toInt()),
            )
            if (available) {
                updateNotif(
                    "${blk.title} · ${"%.1f".format(s.progress * 100)}% · còn ${if (s.etaSec >= 0) fmtDur(s.etaSec * 1000) else "…"}",
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
        if (ended) return
        cancelled = true
        handler.removeCallbacksAndMessages(null)
        try { transformer?.cancel() } catch (_: Throwable) {}
        chunkFile?.delete()
        val m = merger
        val path = outFile
        // chạy trên cùng thread ghép → đợi lượt ghép đang dở (nếu có) xong rồi mới đóng + xoá
        val exec = mergeExec
        if (m != null && exec != null) exec.execute { m.abort(); path?.delete() } else path?.delete()
        ExportBus.state.value = ExportUi(error = "Đã huỷ")
        teardown()
    }

    private fun finish(error: String?) {
        if (ended) return
        ended = true
        handler.removeCallbacksAndMessages(null)
        val bytes = outFile?.length() ?: 0L
        if (error == null) outFile?.let { f ->
            // báo cho Thư viện/Gallery thấy file mới trong Movies
            try { android.media.MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), arrayOf("video/mp4"), null) } catch (_: Exception) {}
        }
        ExportBus.state.value = ExportBus.state.value.copy(
            running = false, done = error == null, error = error, codecNote = "",
            progress = if (error == null) 1f else ExportBus.state.value.progress,
            etaSec = 0, bytes = bytes, predictedBytes = bytes,
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID + 1, notifFinal(if (error == null) "Xuất xong 🎉" else "Lỗi: ${error.lineSequence().first().take(120)}"))
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
        mergeExec?.shutdown(); mergeExec = null   // lượt ghép/đóng đang dở vẫn chạy nốt
        merger = null
        job = null
        ExportBus.pending = null
        try { partsDir().deleteRecursively() } catch (_: Exception) {}
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
        private const val TAG = "StreamComposer"
        /** Số lần thử mỗi phần (lỗi codec nhất thời → tạo lại codec là qua). */
        private const val MAX_ATTEMPTS = 3

        fun start(ctx: Context) = androidx.core.content.ContextCompat.startForegroundService(
            ctx, Intent(ctx, ExportService::class.java).setAction(ACTION_START)
        )
        fun cancel(ctx: Context) = ctx.startService(Intent(ctx, ExportService::class.java).setAction(ACTION_CANCEL))
    }
}
