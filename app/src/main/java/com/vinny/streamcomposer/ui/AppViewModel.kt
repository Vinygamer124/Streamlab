package com.vinny.streamcomposer.ui

import android.app.Application
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.vinny.streamcomposer.data.BreakBlock
import com.vinny.streamcomposer.data.BreakKind
import com.vinny.streamcomposer.data.ExportSettings
import com.vinny.streamcomposer.data.GameBlock
import com.vinny.streamcomposer.data.MIN_MS
import com.vinny.streamcomposer.data.MediaRef
import com.vinny.streamcomposer.data.PlanBlock
import com.vinny.streamcomposer.data.Planner
import com.vinny.streamcomposer.data.Resources
import com.vinny.streamcomposer.data.StreamTime
import com.vinny.streamcomposer.data.Timeline
import com.vinny.streamcomposer.render.ExportBus
import com.vinny.streamcomposer.render.ExportJob
import com.vinny.streamcomposer.render.ExportService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@UnstableApi
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("composer", 0)

    private val _res = MutableStateFlow(Resources())
    val res: StateFlow<Resources> = _res
    private val _time = MutableStateFlow(StreamTime())
    val time: StateFlow<StreamTime> = _time
    private val _plan = MutableStateFlow<List<PlanBlock>>(emptyList())
    val plan: StateFlow<List<PlanBlock>> = _plan
    private val _settings = MutableStateFlow(ExportSettings())
    val settings: StateFlow<ExportSettings> = _settings

    init { restore() }

    // ---------- tài nguyên ----------
    fun addGames(uris: List<Uri>) = probeAll(uris) { refs -> _res.value = _res.value.copy(games = _res.value.games + refs); save() }
    fun addReactions(uris: List<Uri>) = probeAll(uris) { refs -> _res.value = _res.value.copy(reactions = _res.value.reactions + refs); save() }
    fun setMusic(uri: Uri) = probeAll(listOf(uri)) { _res.value = _res.value.copy(music = it.firstOrNull()); save() }
    fun setFrameLunch(uri: Uri?) { keep(uri); _res.value = _res.value.copy(frameLunch = uri); save() }
    fun setFrameDinner(uri: Uri?) { keep(uri); _res.value = _res.value.copy(frameDinner = uri); save() }
    fun setSocial(uri: Uri?) { keep(uri); _res.value = _res.value.copy(socialOverlay = uri); save() }
    fun setTrim(i: Int, startMs: Long, endMs: Long) {
        val g = _res.value.games.getOrNull(i) ?: return
        if (startMs < 0 || endMs > g.durationMs || endMs - startMs < 1000) return
        _res.value = _res.value.copy(games = _res.value.games.mapIndexed { k, x -> if (k == i) x.copy(trimStartMs = startMs, trimEndMs = endMs) else x })
        save()
    }
    /** Nhân đôi để cắt thêm một đoạn khác của cùng file. */
    fun duplicateGame(i: Int) {
        val g = _res.value.games.getOrNull(i) ?: return
        _res.value = _res.value.copy(games = _res.value.games.toMutableList().also { it.add(i + 1, g) })
        save()
    }
    fun removeGame(i: Int) { _res.value = _res.value.copy(games = _res.value.games.filterIndexed { k, _ -> k != i }); save() }
    fun removeReaction(i: Int) { _res.value = _res.value.copy(reactions = _res.value.reactions.filterIndexed { k, _ -> k != i }); save() }
    fun clearMusic() { _res.value = _res.value.copy(music = null); save() }

    // ---------- lịch ----------
    fun setTime(t: StreamTime) { _time.value = t; save() }
    fun autoPlan() { _plan.value = Planner.suggest(_time.value, _res.value.reactions) }
    fun fit() { _plan.value = Planner.fitGames(_plan.value, _time.value.totalMin * MIN_MS) }

    fun changeMinutes(i: Int, deltaMin: Int) = edit {
        val b = it[i]
        val ms = (b.durationMs + deltaMin * MIN_MS).coerceAtLeast(MIN_MS)
        it[i] = when (b) { is GameBlock -> b.copy(durationMs = ms); is BreakBlock -> b.copy(durationMs = ms) }
    }

    fun toggleKind(i: Int) = edit {
        val b = it[i] as? BreakBlock ?: return@edit
        if (b.opening) return@edit
        it[i] = if (b.kind == BreakKind.COMEBACK) b.copy(kind = BreakKind.REACTION, label = "Reaction")
        else b.copy(kind = BreakKind.COMEBACK, label = "Come back soon", dinner = false)
    }

    fun toggleDinner(i: Int) = edit {
        val b = it[i] as? BreakBlock ?: return@edit
        it[i] = b.copy(dinner = !b.dinner, label = if (!b.dinner) "Dinner reaction" else "Lunch reaction")
    }

    fun removeBreak(i: Int) = edit {
        val b = it[i] as? BreakBlock ?: return@edit
        if (b.opening) return@edit
        it.removeAt(i)
        // gộp 2 game block kề nhau
        if (i in 1 until it.size && it[i - 1] is GameBlock && it[i] is GameBlock) {
            it[i - 1] = GameBlock(it[i - 1].durationMs + it[i].durationMs)
            it.removeAt(i)
        }
    }

    fun splitGame(i: Int) = edit {
        val g = it[i] as? GameBlock ?: return@edit
        if (g.durationMs < 20 * MIN_MS) return@edit
        val half = g.durationMs / 2
        it[i] = GameBlock(half)
        it.add(i + 1, BreakBlock(BreakKind.COMEBACK, 5 * MIN_MS, label = "Come back soon"))
        it.add(i + 2, GameBlock(g.durationMs - half))
    }

    private inline fun edit(block: (MutableList<PlanBlock>) -> Unit) {
        val m = _plan.value.toMutableList(); block(m); _plan.value = m
    }

    // ---------- xuất ----------
    fun updateSettings(s: ExportSettings) { _settings.value = s; save() }

    fun estimateBytes(): Long {
        val totalSec = Planner.totalMs(_plan.value) / 1000
        return ((_settings.value.videoMbps + 0.128f) * 1_000_000L / 8 * totalSec).toLong()
    }

    fun freeBytes(): Long = try {
        StatFs(outDir().absolutePath).availableBytes
    } catch (e: Exception) { -1L }

    private fun outDir(): File =
        getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: getApplication<Application>().filesDir

    fun timeline(): Timeline = Timeline.build(_plan.value, _res.value, _settings.value, _time.value.startMin)

    fun startExport() {
        val tl = timeline()
        val name = "Live_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".mp4"
        ExportBus.pending = ExportJob(tl, _res.value, _settings.value, File(outDir(), name).absolutePath, _time.value.startMin)
        ExportService.start(getApplication())
    }

    // ---------- probe ----------
    private fun keep(uri: Uri?) {
        uri ?: return
        try { getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
    }

    private fun probeAll(uris: List<Uri>, done: (List<MediaRef>) -> Unit) {
        viewModelScope.launch {
            val refs = withContext(Dispatchers.IO) { uris.mapNotNull { probe(it) } }
            done(refs)
        }
    }

    private fun probe(uri: Uri): MediaRef? {
        val ctx = getApplication<Application>()
        keep(uri)
        var name = uri.lastPathSegment ?: "file"
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0)
            }
        } catch (_: Exception) {}
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(ctx, uri)
            val d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            MediaRef(uri, name, d)
        } catch (e: Exception) { null } finally { mmr.release() }
    }

    // ---------- lưu / khôi phục ----------
    private fun refsToJson(l: List<MediaRef>) = JSONArray().also { a ->
        l.forEach { a.put(JSONObject().put("u", it.uri.toString()).put("n", it.name).put("d", it.durationMs).put("ts", it.trimStartMs).put("te", it.trimEndMs)) }
    }

    private fun jsonToRefs(s: String?): List<MediaRef> {
        if (s.isNullOrEmpty()) return emptyList()
        val a = JSONArray(s)
        return (0 until a.length()).map { val o = a.getJSONObject(it); val d = o.getLong("d"); MediaRef(Uri.parse(o.getString("u")), o.getString("n"), d, o.optLong("ts", 0L), o.optLong("te", d)) }
    }

    private fun save() {
        val r = _res.value
        prefs.edit()
            .putString("games", refsToJson(r.games).toString())
            .putString("reactions", refsToJson(r.reactions).toString())
            .putString("music", r.music?.let { refsToJson(listOf(it)).toString() })
            .putString("frameLunch", r.frameLunch?.toString())
            .putString("frameDinner", r.frameDinner?.toString())
            .putString("social", r.socialOverlay?.toString())
            .putInt("start", _time.value.startMin).putInt("end", _time.value.endMin)
            .putFloat("mbps", _settings.value.videoMbps)
            .putString("codec", _settings.value.codec.name)
            .putBoolean("frag", _settings.value.fragmentedMp4)
            .putBoolean("trans", _settings.value.transitions)
            .putBoolean("safe", _settings.value.safeMode)
            .putBoolean("gpuc", _settings.value.gpuContour)
            .apply()
    }

    private fun restore() {
        try {
            _res.value = Resources(
                games = jsonToRefs(prefs.getString("games", null)),
                reactions = jsonToRefs(prefs.getString("reactions", null)),
                music = jsonToRefs(prefs.getString("music", null)).firstOrNull(),
                frameLunch = prefs.getString("frameLunch", null)?.let(Uri::parse),
                frameDinner = prefs.getString("frameDinner", null)?.let(Uri::parse),
                socialOverlay = prefs.getString("social", null)?.let(Uri::parse),
            )
            _time.value = StreamTime(prefs.getInt("start", 20 * 60), prefs.getInt("end", 23 * 60))
            _settings.value = ExportSettings(
                codec = runCatching { com.vinny.streamcomposer.data.Codec.valueOf(prefs.getString("codec", "H265")!!) }.getOrDefault(com.vinny.streamcomposer.data.Codec.H265),
                videoMbps = prefs.getFloat("mbps", 6f),
                fragmentedMp4 = prefs.getBoolean("frag", false),
                transitions = prefs.getBoolean("trans", true),
                safeMode = prefs.getBoolean("safe", false),
                gpuContour = prefs.getBoolean("gpuc", true),
            )
        } catch (_: Exception) {}
        autoPlan()
    }
}
