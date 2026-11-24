package com.vinny.streamcomposer.render

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
)

object ExportBus {
    val state = MutableStateFlow(ExportUi())
    @Volatile var pending: ExportJob? = null
}

/** ETA dựa trên tốc độ trong cửa sổ 90 giây gần nhất, trộn với tốc độ trung bình toàn quá trình. */
class EtaEstimator {
    private val win = ArrayDeque<Pair<Long, Float>>()
    private var t0 = 0L
    fun reset() { win.clear(); t0 = 0L }
    fun add(nowMs: Long, p: Float) {
        if (t0 == 0L) t0 = nowMs
        win.addLast(nowMs to p)
        while (win.size > 2 && nowMs - win.first().first > 90_000) win.removeFirst()
    }
    fun etaSec(): Long {
        if (win.size < 2) return -1
        val (ta, pa) = win.first(); val (tb, pb) = win.last()
        val recent = if (tb > ta) (pb - pa) / (tb - ta) else 0f
        val overall = if (tb > t0) pb / (tb - t0) else 0f
        val v = if (recent > 0f && overall > 0f) 0.6f * recent + 0.4f * overall else maxOf(recent, overall)
        return if (v <= 0f) -1 else (((1f - pb) / v) / 1000f).toLong()
    }
}

@UnstableApi
class ExportService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var wake: PowerManager.WakeLock? = null
    private val eta = EtaEstimator()
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
        eta.reset()
        startedAt = SystemClock.elapsedRealtime()
        ExportBus.state.value = ExportUi(running = true, outputPath = job.outputPath)

        try {
            val composition = ExportEngine.buildComposition(this, job)
            transformer = ExportEngine.buildTransformer(this, job.settings, object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) = finish(null)
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) =
                    finish("${exportException.errorCodeName}: ${exportException.message}")
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
            val now = SystemClock.elapsedRealtime()
            val state = tr.getProgress(holder)
            val bytes = outFile?.length() ?: 0L
            val elapsed = (now - startedAt) / 1000
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                val p = (holder.progress / 100f).coerceIn(0f, 1f)
                eta.add(now, p)
                val predicted = if (p > 0.01f) (bytes / p).toLong() else -1L
                val speed = if (elapsed > 0) (p * totalMs / 1000f) / elapsed else 0f
                val e = eta.etaSec()
                ExportBus.state.value = ExportBus.state.value.copy(
                    running = true, progress = p, etaSec = e, elapsedSec = elapsed,
                    bytes = bytes, predictedBytes = predicted, speedX = speed,
                )
                updateNotif("${(p * 100).toInt()}% · còn ${if (e >= 0) fmtDur(e * 1000) else "…"}", (p * 100).toInt())
            } else {
                ExportBus.state.value = ExportBus.state.value.copy(elapsedSec = elapsed, bytes = bytes)
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
        ExportBus.state.value = ExportBus.state.value.copy(
            running = false, done = error == null, error = error,
            progress = if (error == null) 1f else ExportBus.state.value.progress,
            etaSec = 0, bytes = bytes, predictedBytes = bytes,
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID + 1, notifFinal(if (error == null) "Xuất xong 🎉" else "Lỗi: $error"))
        teardown()
    }

    private fun teardown() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        transformer = null
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
