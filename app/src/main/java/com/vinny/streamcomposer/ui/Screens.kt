@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.vinny.streamcomposer.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.vinny.streamcomposer.data.BreakBlock
import com.vinny.streamcomposer.data.BreakKind
import com.vinny.streamcomposer.data.Codec
import com.vinny.streamcomposer.data.GameBlock
import com.vinny.streamcomposer.data.BreakScreen
import com.vinny.streamcomposer.data.MIN_MS
import com.vinny.streamcomposer.data.MediaRef
import com.vinny.streamcomposer.data.OpeningScreen
import com.vinny.streamcomposer.data.PlanBlock
import com.vinny.streamcomposer.data.Planner
import com.vinny.streamcomposer.data.THANKS_MAX_SEC
import com.vinny.streamcomposer.data.THANKS_MIN_SEC
import com.vinny.streamcomposer.data.fmtClock
import com.vinny.streamcomposer.data.fmtDur
import com.vinny.streamcomposer.data.title
import com.vinny.streamcomposer.render.ExportBus
import com.vinny.streamcomposer.render.ExportService
import com.vinny.streamcomposer.render.SegInfo
import com.vinny.streamcomposer.render.SegKind
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private enum class Tab(val label: String, val glyph: String) {
    RES("Tài nguyên", "📁"), PLAN("Lịch live", "🗓"), EXPORT("Xuất", "⬆")
}

@UnstableApi
@Composable
fun MainApp(vm: AppViewModel) {
    var tab by remember { mutableStateOf(Tab.RES) }
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Stream Composer", style = MaterialTheme.typography.titleLarge)
                    Hint("GTA 6 · Vinny Gamer Official")
                }
            })
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tab = t },
                        icon = { Text(t.glyph) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tab) {
                Tab.RES -> ResourcesScreen(vm)
                Tab.PLAN -> ScheduleScreen(vm)
                Tab.EXPORT -> ExportScreen(vm)
            }
        }
    }
}

// ============================ TÀI NGUYÊN ============================

@UnstableApi
@Composable
private fun ResourcesScreen(vm: AppViewModel) {
    val res by vm.res.collectAsStateWithLifecycle()
    val pickGames = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addGames(it) }
    val pickReact = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addReactions(it) }
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::setMusic) }
    val pickLunch = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.setFrameLunch(it) }
    val pickDinner = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.setFrameDinner(it) }
    val pickSocial = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.setSocial(it) }

    Section("1 · Video game") {
        Hint("Có thể rất dài (vài tiếng), chọn nhiều file – app nối theo thứ tự.")
        res.games.forEachIndexed { i, g -> GameRow(i, g, vm) }
        FilledTonalButton(onClick = { pickGames.launch(arrayOf("video/*")) }) { Text("+ Thêm video game") }
        if (res.games.isNotEmpty()) Hint("Tổng sau khi cắt: ${fmtDur(res.games.sumOf { it.playMs })}")
    }
    Section("2 · Video reaction") {
        Hint("Được đặt vào khung Lunch/Dinner + phủ đường đồng mức animated chống bản quyền.")
        res.reactions.forEachIndexed { i, g -> FileRow(g, onRemove = { vm.removeReaction(i) }) }
        FilledTonalButton(onClick = { pickReact.launch(arrayOf("video/*")) }) { Text("+ Thêm video reaction") }
    }
    Section("3 · Nhạc màn chờ") {
        Hint("Tự lặp cho vừa độ dài mỗi màn chờ (Starting soon / Be right back / Come back soon / Thanks for watching).")
        res.music?.let { FileRow(it, onRemove = { vm.clearMusic() }) }
        FilledTonalButton(onClick = { pickMusic.launch(arrayOf("audio/*")) }) {
            Text(if (res.music == null) "Chọn nhạc" else "Đổi nhạc")
        }
    }
    Section("4 · Ảnh phủ & khung") {
        Hint("PNG 1920×1080 trong suốt. Không chọn = dùng bản có sẵn trong app.")
        AssetRow("3 social (phủ toàn bộ stream)", res.socialOverlay, { pickSocial.launch(arrayOf("image/png")) }, { vm.setSocial(null) })
        AssetRow("Khung Lunch (cửa sổ trong suốt x64–1463, y174–961)", res.frameLunch, { pickLunch.launch(arrayOf("image/png")) }, { vm.setFrameLunch(null) })
        AssetRow("Khung Dinner (tuỳ chọn)", res.frameDinner, { pickDinner.launch(arrayOf("image/png")) }, { vm.setFrameDinner(null) })
    }
}

private fun parseHms(s: String): Long? {
    val p = s.trim().split(":")
    if (p.isEmpty() || p.size > 3) return null
    var sec = 0L
    for (x in p) sec = sec * 60 + (x.toLongOrNull()?.takeIf { it >= 0 } ?: return null)
    return sec * 1000
}

private fun fmtHms(ms: Long): String {
    val t = ms / 1000
    return "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
}

@Composable
private fun RemoveButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
        Text("Xoá")
    }
}

/** Dòng video game có nút ✂ để cắt đoạn (từ … đến …) và nhân đôi để lấy thêm đoạn khác của cùng file. */
@UnstableApi
@Composable
private fun GameRow(i: Int, g: MediaRef, vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    var s by remember(i, g.durationMs) { mutableStateOf(fmtHms(g.trimStartMs)) }
    var e by remember(i, g.durationMs) { mutableStateOf(fmtHms(g.trimEndMs)) }
    fun commit() {
        val a = parseHms(s) ?: return
        val b = parseHms(e) ?: return
        vm.setTrim(i, a, b)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(g.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Hint("Dùng ${fmtDur(g.playMs)} / ${fmtDur(g.durationMs)}  (${fmtHms(g.trimStartMs)} → ${fmtHms(g.trimEndMs)})")
            }
            TextButton(onClick = { open = !open }) { Text("✂ Cắt") }
            RemoveButton { vm.removeGame(i) }
        }
        if (open) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeField("Từ (h:mm:ss)", s, Modifier.weight(1f)) { s = it; commit() }
                TimeField("Đến (h:mm:ss)", e, Modifier.weight(1f)) { e = it; commit() }
            }
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { vm.duplicateGame(i) }, label = { Text("Nhân đôi: cắt thêm đoạn khác") })
                AssistChip(onClick = {
                    s = fmtHms(0); e = fmtHms(g.durationMs); vm.setTrim(i, 0, g.durationMs)
                }, label = { Text("Cả file") })
            }
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun FileRow(m: MediaRef, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(m.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Hint(fmtDur(m.durationMs))
        }
        RemoveButton(onRemove)
    }
}

@Composable
private fun AssetRow(title: String, uri: Uri?, onPick: () -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Hint(if (uri == null) "Mặc định" else "Tuỳ chỉnh", color = if (uri == null) Color.Unspecified else MaterialTheme.colorScheme.tertiary)
            OutlinedButton(onClick = onPick) { Text("Chọn PNG") }
            if (uri != null) TextButton(onClick = onReset) { Text("Đặt lại") }
        }
    }
}

// ============================ LỊCH LIVE ============================

private fun parseClock(s: String): Int? {
    val p = s.trim().split(":")
    if (p.size != 2) return null
    val h = p[0].toIntOrNull() ?: return null
    val m = p[1].toIntOrNull() ?: return null
    return if (h in 0..23 && m in 0..59) h * 60 + m else null
}

@UnstableApi
@Composable
private fun ScheduleScreen(vm: AppViewModel) {
    val time by vm.time.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    var startTxt by remember(time.startMin) { mutableStateOf(fmtClock(time.startMin)) }
    var endTxt by remember(time.endMin) { mutableStateOf(fmtClock(time.endMin)) }

    Section("Giờ phát live") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeField("Từ (HH:mm)", startTxt, Modifier.weight(1f)) { startTxt = it; parseClock(it)?.let { m -> vm.setTime(time.copy(startMin = m)) } }
            TimeField("Đến (HH:mm)", endTxt, Modifier.weight(1f)) { endTxt = it; parseClock(it)?.let { m -> vm.setTime(time.copy(endMin = m)) } }
        }
        Hint(
            "Độ dài live: ${time.totalMin / 60}h${"%02d".format(time.totalMin % 60)} (nếu giờ đến ≤ giờ từ → qua nửa đêm)",
            color = MaterialTheme.colorScheme.primary,
        )
        Hint(Planner.explain(time))
        Button(onClick = { vm.autoPlan() }, modifier = Modifier.fillMaxWidth()) { Text("Gợi ý lịch tự động") }
    }

    PlanSummary(plan, time.startMin)

    val planMs = Planner.totalMs(plan)
    val targetMs = time.totalMin * MIN_MS
    val diffMin = ((planMs - targetMs) / MIN_MS).toInt()
    Section("Timeline") {
        Hint(
            "Plan ${fmtDur(planMs)} / live ${fmtDur(targetMs)}",
            color = if (diffMin == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
        )
        if (diffMin != 0) {
            FilledTonalButton(onClick = { vm.fit() }) { Text("Co game cho khớp (${if (diffMin > 0) "+" else ""}$diffMin phút)") }
        }
        var clock = time.startMin
        plan.forEachIndexed { i, b ->
            BlockRow(i, b, clock, vm)
            clock += (b.durationMs / MIN_MS).toInt()
        }
    }
}

@Composable
private fun blockColor(b: PlanBlock): Color = when {
    b is GameBlock -> MaterialTheme.colorScheme.primary
    (b as BreakBlock).kind == BreakKind.REACTION -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.secondary
}

/** Tóm tắt rõ ràng: mỗi break là gì, lúc mấy giờ, bao nhiêu phút + tổng theo từng loại. */
@Composable
private fun PlanSummary(plan: List<PlanBlock>, startMin: Int) {
    val breaks = plan.filterIsInstance<BreakBlock>()
    Section("Tóm tắt break") {
        var clock = startMin
        plan.forEach { b ->
            if (b is BreakBlock) {
                val c = blockColor(b)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Dot(c)
                    Text(fmtClock(clock), color = c, style = MaterialTheme.typography.titleSmall)
                    Text(b.title(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("${b.durationMs / MIN_MS} phút", color = c, style = MaterialTheme.typography.titleSmall)
                }
            }
            clock += (b.durationMs / MIN_MS).toInt()
        }
        HorizontalDivider()
        fun line(name: String, pred: (BreakBlock) -> Boolean): String {
            val l = breaks.filter(pred)
            return if (l.isEmpty()) "$name: không có" else "$name: ${l.size} lần · ${l.sumOf { it.durationMs } / MIN_MS} phút"
        }
        listOf(
            line("Màn chờ (mở màn + nghỉ)") { it.kind == BreakKind.COMEBACK },
            line("Lunch reaction") { it.title() == "Lunch reaction" },
            line("Dinner reaction") { it.title() == "Dinner reaction" },
            line("Reaction (thường)") { it.title() == "Reaction" },
        ).forEach { Hint(it) }
        Hint("Game: ${Planner.gameMs(plan) / MIN_MS} phút", color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TimeField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
    )
}

@UnstableApi
@Composable
private fun BlockRow(i: Int, b: PlanBlock, clockMin: Int, vm: AppViewModel) {
    val isGame = b is GameBlock
    val accent = blockColor(b)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(fmtClock(clockMin), color = accent, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (isGame) "Gameplay" else (b as BreakBlock).title(),
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                )
                Text("${b.durationMs / MIN_MS}′", color = accent, style = MaterialTheme.typography.titleMedium)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { vm.changeMinutes(i, -5) }, label = { Text("−5") })
                AssistChip(onClick = { vm.changeMinutes(i, -1) }, label = { Text("−1") })
                AssistChip(onClick = { vm.changeMinutes(i, 1) }, label = { Text("+1") })
                AssistChip(onClick = { vm.changeMinutes(i, 5) }, label = { Text("+5") })
                if (b is GameBlock) {
                    AssistChip(onClick = { vm.splitGame(i) }, label = { Text("Chèn break") })
                } else if (b is BreakBlock && !b.opening) {
                    AssistChip(
                        onClick = { vm.toggleKind(i) },
                        label = { Text(if (b.kind == BreakKind.COMEBACK) "→ Reaction" else "→ Màn chờ") },
                    )
                    if (b.kind == BreakKind.REACTION) {
                        AssistChip(onClick = { vm.toggleDinner(i) }, label = { Text(if (b.dinner) "Dinner" else "Lunch") })
                    }
                    AssistChip(onClick = { vm.removeBreak(i) }, label = { Text("Xoá") })
                }
            }
        }
    }
}

// ============================ XUẤT ============================

@UnstableApi
@Composable
private fun ExportScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val st by vm.settings.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val res by vm.res.collectAsStateWithLifecycle()
    val ui by ExportBus.state.collectAsStateWithLifecycle()
    val layout by ExportBus.layout.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        ExportBus.wantPreview = true
        onDispose { ExportBus.wantPreview = false }
    }
    val tl = remember(plan, res, st) { vm.timeline() }
    var moviesOk by remember { mutableStateOf(vm.canWritePublicMovies()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { moviesOk = vm.canWritePublicMovies() }
    val storagePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { moviesOk = vm.canWritePublicMovies() }
    val estimate = vm.estimateBytes()
    val free = vm.freeBytes()
    val cs = MaterialTheme.colorScheme

    Section("Cấu hình xuất") {
        Hint("Codec")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Codec.entries.forEach { c ->
                FilterChip(selected = c == st.codec, onClick = { vm.updateSettings(st.copy(codec = c)) }, label = { Text(c.label) })
            }
        }
        Hint("Bitrate video: ${"%.1f".format(st.videoMbps)} Mbps (1080p30)")
        Slider(
            value = st.videoMbps, onValueChange = { vm.updateSettings(st.copy(videoMbps = Math.round(it * 2f) / 2f)) },
            valueRange = 2f..20f,
        )
        ToggleRow("Chuyển cảnh menu GTA 6 → rest mode → PS5 connected", st.transitions) { vm.updateSettings(st.copy(transitions = it)) }
        Hint("Màn mở màn stream")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpeningScreen.entries.forEach { o ->
                FilterChip(selected = o == st.openingScreen, onClick = { vm.updateSettings(st.copy(openingScreen = o)) }, label = { Text(o.label) })
            }
        }
        Hint("Màn nghỉ giữa stream (break Come back)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BreakScreen.entries.forEach { o ->
                FilterChip(selected = o == st.breakScreen, onClick = { vm.updateSettings(st.copy(breakScreen = o)) }, label = { Text(o.label) })
            }
        }
        ToggleRow("Kết thúc stream bằng màn Thanks for watching (lấy bớt từ cuối đoạn game nên tổng thời lượng không đổi)", st.thanksEnding) { vm.updateSettings(st.copy(thanksEnding = it)) }
        if (st.thanksEnding) {
            Hint(
                if (st.gtaCrashEnding) "Đang bật GTA crash bên dưới → màn Thanks for watching bị bỏ qua (chỉ chọn một kiểu kết thúc)."
                else "Độ dài màn Thanks for watching: ${st.thanksSec} giây"
            )
            Slider(
                value = st.thanksSec.toFloat(),
                onValueChange = { vm.updateSettings(st.copy(thanksSec = (it / 10f).roundToInt() * 10)) },
                valueRange = THANKS_MIN_SEC.toFloat()..THANKS_MAX_SEC.toFloat(), steps = (THANKS_MAX_SEC - THANKS_MIN_SEC) / 10 - 1,
            )
        }
        ToggleRow("Kết thúc stream bằng GTA crash (đóng băng → lỗi PS5, dừng ở màn Report Problem) – tuỳ chọn", st.gtaCrashEnding) { vm.updateSettings(st.copy(gtaCrashEnding = it)) }
        if (st.gtaCrashEnding) {
            Hint("Kéo dài màn lỗi PS5 (nền loop): ${st.crashHoldMin} phút – lấy bớt từ cuối đoạn game nên tổng thời lượng không đổi")
            Slider(
                value = st.crashHoldMin.toFloat(),
                onValueChange = { vm.updateSettings(st.copy(crashHoldMin = it.roundToInt())) },
                valueRange = 0f..10f, steps = 9,
            )
        }
        ToggleRow("Contour reaction bằng GPU (shader) – nhanh; tắt nếu lỗi/hiển thị lạ", st.gpuContour) { vm.updateSettings(st.copy(gpuContour = it)) }
        ToggleRow("Chế độ an toàn (tắt contour + social, dùng để dò lỗi)", st.safeMode) { vm.updateSettings(st.copy(safeMode = it)) }
        HorizontalDivider()
        Hint(
            "Ước lượng trước khi xuất: ${fmtBytes(estimate)} · ổ trống: ${if (free >= 0) fmtBytes(free) else "?"}",
            color = if (free in 0 until estimate) cs.error else cs.primary,
        )
        if (moviesOk) Hint("Xuất từng phần ~6 phút rồi ghép thẳng vào Movies/StreamComposer (thư viện máy); phần nào lỗi thì tự thử lại đúng phần đó. Chỉ cần dư chỗ cho thêm ~1 phần (vài trăm MB).")
        else {
            Hint("⚠ Chưa có quyền ghi vào thư mục Movies – đang lưu trong data của app (khó tìm).", color = cs.primary)
            OutlinedButton(onClick = {
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    } catch (e: Exception) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }, modifier = Modifier.fillMaxWidth()) { Text("Cấp quyền lưu vào Movies") }
        }
    }

    if (tl.warnings.isNotEmpty() || res.games.isEmpty()) {
        Section("Cảnh báo") {
            if (res.games.isEmpty()) Hint("⚠ Chưa chọn video game.", color = cs.error)
            tl.warnings.forEach { Hint("⚠ $it", color = cs.primary) }
        }
    }

    Section(if (ui.running) "Đang xuất…" else if (ui.done) "Xong!" else "Sẵn sàng") {
        LinearProgressIndicator(progress = { ui.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        if (layout.isNotEmpty() && (ui.running || ui.done)) {
            SegmentMap(layout, if (ui.done) 1f else ui.progress)
        }
        Text(
            "${"%.1f".format(ui.progress * 100)}%  ·  đã chạy ${fmtDur(ui.elapsedSec * 1000)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (ui.running && ui.codecNote.isNotBlank()) {
            Hint(ui.codecNote, color = if (ui.codecNote.startsWith("⚠")) cs.error else cs.primary)
        }
        if (ui.running && ui.segKind != null) {
            Hint("▶ Đang render: ${ui.segTitle}", color = cs.primary)
            Text(
                "Khối ${ui.segIndex + 1}/${ui.segCount}  ·  ${fmtDur(ui.segPosMs)} / ${fmtDur(ui.segDurMs)}  ·  giờ live ${ui.streamClock}",
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(
                progress = { if (ui.segDurMs > 0) (ui.segPosMs.toFloat() / ui.segDurMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            if (ui.segSpeedX > 0f) Hint("Tốc độ riêng của đoạn này: ${"%.2f".format(ui.segSpeedX)}× thời gian thực")
            ui.preview?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(cs.surfaceContainerLowest),
                )
                Hint("Khung nguồn đang xử lý: ${ui.previewCaption}")
            }
        }
        if (ui.running) {
            val etaTxt = if (ui.etaSec >= 0) "còn ${fmtDur(ui.etaSec * 1000)} · xong lúc " +
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + ui.etaSec * 1000)) else "đang đo…"
            Hint("ETA: $etaTxt", color = cs.primary)
            Hint("Tốc độ: ${"%.2f".format(ui.speedX)}× thời gian thực")
            Hint(
                "File hiện tại: ${fmtBytes(ui.bytes)}  →  dự đoán cuối: ${if (ui.predictedBytes > 0) fmtBytes(ui.predictedBytes) else "…"}",
                color = cs.primary,
            )
        }
        ui.error?.let { Hint("✕ $it", color = cs.error) }
        if (ui.done) {
            Hint("File: ${ui.outputPath}  (${fmtBytes(ui.bytes)})", color = cs.tertiary)
            FilledTonalButton(onClick = {
                val f = File(ui.outputPath ?: return@FilledTonalButton)
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                ctx.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Mở video"
                ))
            }) { Text("Mở / chia sẻ video") }
        }
        if (ui.running) {
            OutlinedButton(onClick = { ExportService.cancel(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Huỷ xuất") }
        } else {
            Button(
                onClick = { vm.startExport() },
                enabled = plan.isNotEmpty() && res.games.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Bắt đầu xuất") }
        }
    }
}

@Composable
private fun kindColor(k: SegKind): Color {
    val cs = MaterialTheme.colorScheme
    return when (k) {
        SegKind.GAME -> cs.outline
        SegKind.REACTION -> cs.primary
        SegKind.REST -> cs.secondary
        SegKind.CONNECT -> cs.tertiary
        SegKind.COMEBACK -> cs.inversePrimary
        SegKind.CRASH -> cs.error
    }
}

/** Bản đồ toàn bộ video: mỗi loại đoạn một màu, vạch = vị trí đang render. */
@Composable
private fun SegmentMap(layout: List<SegInfo>, progress: Float) {
    val total = layout.sumOf { it.durationMs }.coerceAtLeast(1L).toFloat()
    val colors = SegKind.entries.associateWith { kindColor(it) }
    val marker = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(16.dp)) {
        var x = 0f
        for (b in layout) {
            val w = size.width * (b.durationMs / total)
            drawRect(colors[b.kind] ?: Color.Gray, topLeft = Offset(x, 0f), size = Size(maxOf(w - 0.5f, 0.5f), size.height))
            x += w
        }
        val px = size.width * progress.coerceIn(0f, 1f)
        drawRect(marker, topLeft = Offset((px - 1.5f).coerceAtLeast(0f), 0f), size = Size(3f, size.height))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            SegKind.GAME to "Game", SegKind.REACTION to "Reaction", SegKind.REST to "Rest mode",
            SegKind.CONNECT to "PS5", SegKind.COMEBACK to "Come back", SegKind.CRASH to "Crash",
        ).forEach { (k, name) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Dot(colors[k] ?: Color.Gray)
                Hint(name)
            }
        }
    }
}

@Composable
private fun ToggleRow(text: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

private fun fmtBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> "%.1f MB".format(b / (1024.0 * 1024))
    else -> "%d KB".format(b / 1024)
}

// ============================ CỔNG PIN ============================

@Composable
fun BatteryGate(onRequest: () -> Unit, onSkip: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp), contentAlignment = Alignment.Center) {
            Section("Bật chế độ pin không tối ưu") {
                Text(
                    "Xuất video vài tiếng cần app chạy ngầm liên tục. Nếu hệ thống tối ưu pin, app có thể bị kill giữa chừng và mất tiến độ.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Hint("Bấm nút bên dưới → chọn “Cho phép”. Trên Xiaomi/Oppo/Vivo/Samsung nên bật thêm Tự khởi chạy / Không hạn chế nền trong cài đặt pin.")
                Spacer(Modifier.height(4.dp))
                Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) { Text("Bật không tối ưu pin") }
                OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Để sau (không khuyến nghị)") }
            }
        }
    }
}
