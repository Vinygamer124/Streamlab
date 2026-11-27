package com.vinny.streamcomposer.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.vinny.streamcomposer.data.BreakBlock
import com.vinny.streamcomposer.data.BreakKind
import com.vinny.streamcomposer.data.Codec
import com.vinny.streamcomposer.data.GameBlock
import com.vinny.streamcomposer.data.MIN_MS
import com.vinny.streamcomposer.data.MediaRef
import com.vinny.streamcomposer.data.Planner
import com.vinny.streamcomposer.data.StreamTime
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

private enum class Tab(val label: String) { RES("Tài nguyên"), PLAN("Lịch live"), EXPORT("Xuất") }

@UnstableApi
@Composable
fun MainApp(vm: AppViewModel) {
    var tab by remember { mutableStateOf(Tab.RES) }
    Column(Modifier.fillMaxSize().background(Pal.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Title("Stream Composer")
                Label("GTA 6 · Vinny Gamer Official")
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (tab) {
                    Tab.RES -> ResourcesScreen(vm)
                    Tab.PLAN -> ScheduleScreen(vm)
                    Tab.EXPORT -> ExportScreen(vm)
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Pal.panel).dottedBorder(Pal.muted.copy(alpha = 0.5f), 2f)) {
            Tab.entries.forEach { t ->
                val sel = t == tab
                Box(
                    Modifier.weight(1f).clickable { tab = t }.padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        t.label.uppercase(),
                        color = if (sel) Pal.orange else Pal.muted,
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    )
                }
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

    PixelPanel {
        Title("1 · Video game")
        Label("Có thể rất dài (vài tiếng), chọn nhiều file – app nối theo thứ tự.")
        Spacer(Modifier.height(8.dp))
        res.games.forEachIndexed { i, g -> GameRow(i, g, vm) }
        ForgeButton("+ Thêm video game", onClick = { pickGames.launch(arrayOf("video/*")) })
        if (res.games.isNotEmpty()) Label("Tổng sau khi cắt: ${fmtDur(res.games.sumOf { it.playMs })}")
    }
    PixelPanel {
        Title("2 · Video reaction")
        Label("Được đặt vào khung Lunch/Dinner + phủ đường đồng mức animated chống bản quyền.")
        Spacer(Modifier.height(8.dp))
        res.reactions.forEachIndexed { i, g -> FileRow(g, onRemove = { vm.removeReaction(i) }) }
        ForgeButton("+ Thêm video reaction", onClick = { pickReact.launch(arrayOf("video/*")) })
    }
    PixelPanel {
        Title("3 · Nhạc Come back soon")
        Label("Tự lặp cho vừa độ dài mỗi lần Come back soon.")
        Spacer(Modifier.height(8.dp))
        res.music?.let { FileRow(it, onRemove = { vm.clearMusic() }) }
        ForgeButton(if (res.music == null) "Chọn nhạc" else "Đổi nhạc", onClick = { pickMusic.launch(arrayOf("audio/*")) })
    }
    PixelPanel {
        Title("4 · Ảnh phủ & khung")
        Label("PNG 1920×1080 trong suốt. Không chọn = dùng bản có sẵn trong app.")
        Spacer(Modifier.height(8.dp))
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
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(Pal.orange)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(g.name, color = Pal.text, maxLines = 1, fontSize = 14.sp)
                Label("Dùng ${fmtDur(g.playMs)} / ${fmtDur(g.durationMs)}  (${fmtHms(g.trimStartMs)} → ${fmtHms(g.trimEndMs)})")
            }
            Chip("✂", Pal.amber, onClick = { open = !open })
            Spacer(Modifier.padding(horizontal = 3.dp))
            Chip("✕", Pal.bad, onClick = { vm.removeGame(i) })
        }
        if (open) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeField("Từ (h:mm:ss)", s, Modifier.weight(1f)) { s = it; commit() }
                TimeField("Đến (h:mm:ss)", e, Modifier.weight(1f)) { e = it; commit() }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Nhân đôi: cắt thêm đoạn khác", Pal.amber, onClick = { vm.duplicateGame(i) })
                Chip("Cả file", Pal.muted, onClick = {
                    s = fmtHms(0); e = fmtHms(g.durationMs); vm.setTrim(i, 0, g.durationMs)
                })
            }
        }
    }
}

@Composable
private fun FileRow(m: MediaRef, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(Pal.orange)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(m.name, color = Pal.text, maxLines = 1, fontSize = 14.sp)
            Label(fmtDur(m.durationMs))
        }
        Chip("✕", Pal.bad, onClick = onRemove)
    }
}

@Composable
private fun AssetRow(title: String, uri: Uri?, onPick: () -> Unit, onReset: () -> Unit) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(title, color = Pal.text, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Chip(if (uri == null) "Mặc định" else "Tuỳ chỉnh", if (uri == null) Pal.muted else Pal.ok)
            Chip("Chọn PNG", onClick = onPick)
            if (uri != null) Chip("Đặt lại", Pal.amber, onClick = onReset)
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

    PixelPanel {
        Title("Giờ phát live")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeField("Từ (HH:mm)", startTxt, Modifier.weight(1f)) { startTxt = it; parseClock(it)?.let { m -> vm.setTime(time.copy(startMin = m)) } }
            TimeField("Đến (HH:mm)", endTxt, Modifier.weight(1f)) { endTxt = it; parseClock(it)?.let { m -> vm.setTime(time.copy(endMin = m)) } }
        }
        Spacer(Modifier.height(8.dp))
        Label("Độ dài live: ${time.totalMin / 60}h${"%02d".format(time.totalMin % 60)} (nếu giờ đến ≤ giờ từ → qua nửa đêm)", color = Pal.amber)
        Spacer(Modifier.height(4.dp))
        Label(Planner.explain(time))
        Spacer(Modifier.height(10.dp))
        ForgeButton("Gợi ý lịch tự động", Modifier.fillMaxWidth()) { vm.autoPlan() }
    }

    PlanSummary(plan, time.startMin)

    val planMs = Planner.totalMs(plan)
    val targetMs = time.totalMin * MIN_MS
    val diffMin = ((planMs - targetMs) / MIN_MS).toInt()
    PixelPanel {
        Title("Timeline")
        Label("Plan ${fmtDur(planMs)} / live ${fmtDur(targetMs)}", color = if (diffMin == 0) Pal.ok else Pal.bad)
        if (diffMin != 0) {
            Spacer(Modifier.height(6.dp))
            ForgeButton("Co game cho khớp (${if (diffMin > 0) "+" else ""}$diffMin phút)", primary = false) { vm.fit() }
        }
        Spacer(Modifier.height(10.dp))
        var clock = time.startMin
        plan.forEachIndexed { i, b ->
            BlockRow(i, b, clock, vm)
            clock += (b.durationMs / MIN_MS).toInt()
        }
    }
}

/** Tóm tắt rõ ràng: mỗi break là gì, lúc mấy giờ, bao nhiêu phút + tổng theo từng loại. */
@Composable
private fun PlanSummary(plan: List<com.vinny.streamcomposer.data.PlanBlock>, startMin: Int) {
    val breaks = plan.filterIsInstance<BreakBlock>()
    val totals = ArrayList<String>()
    PixelPanel {
        Title("Tóm tắt break")
        Spacer(Modifier.height(8.dp))
        var clock = startMin
        plan.forEach { b ->
            if (b is BreakBlock) {
                val c = if (b.kind == BreakKind.COMEBACK) Pal.ember else Pal.amber
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(c)
                    Text(fmtClock(clock), color = c, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 10.dp),
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    Text(b.title().uppercase(), color = Pal.text, modifier = Modifier.weight(1f),
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                    Text("${b.durationMs / MIN_MS} phút", color = c, fontSize = 15.sp,
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                }
            }
            clock += (b.durationMs / MIN_MS).toInt()
        }
        Spacer(Modifier.height(6.dp))
        fun line(name: String, pred: (BreakBlock) -> Boolean) {
            val l = breaks.filter(pred)
            totals += if (l.isEmpty()) "$name: không có" else "$name: ${l.size} lần · ${l.sumOf { it.durationMs } / MIN_MS} phút"
        }
        line("Come back soon") { it.kind == BreakKind.COMEBACK }
        line("Lunch reaction") { it.title() == "Lunch reaction" }
        line("Dinner reaction") { it.title() == "Dinner reaction" }
        line("Reaction (thường)") { it.title() == "Reaction" }
        totals.forEach { Label(it, color = if (it.endsWith("không có")) Pal.muted else Pal.text) }
        Label("Game: ${Planner.gameMs(plan) / MIN_MS} phút", color = Pal.orange)
    }
}

@Composable
private fun TimeField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Pal.orange, unfocusedBorderColor = Pal.muted,
            focusedTextColor = Pal.text, unfocusedTextColor = Pal.text,
            focusedLabelColor = Pal.orange, unfocusedLabelColor = Pal.muted, cursorColor = Pal.orange,
        ),
    )
}

@UnstableApi
@Composable
private fun BlockRow(i: Int, b: com.vinny.streamcomposer.data.PlanBlock, clockMin: Int, vm: AppViewModel) {
    val isGame = b is GameBlock
    val accent = when {
        isGame -> Pal.orange
        (b as BreakBlock).kind == BreakKind.REACTION -> Pal.amber
        else -> Pal.ember
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).background(Pal.bg.copy(alpha = 0.5f)).dottedBorder(accent.copy(alpha = 0.6f), 2f).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmtClock(clockMin), color = accent, fontSize = 18.sp, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Spacer(Modifier.padding(horizontal = 6.dp))
            Text(
                if (isGame) "GAMEPLAY" else (b as BreakBlock).title().uppercase(),
                color = Pal.text, modifier = Modifier.weight(1f),
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
            Text("${b.durationMs / MIN_MS}′", color = accent, fontSize = 18.sp, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("−5", onClick = { vm.changeMinutes(i, -5) })
            Chip("−1", onClick = { vm.changeMinutes(i, -1) })
            Chip("+1", onClick = { vm.changeMinutes(i, 1) })
            Chip("+5", onClick = { vm.changeMinutes(i, 5) })
            if (isGame) Chip("Chèn break", Pal.amber, onClick = { vm.splitGame(i) })
            else if (!(b as BreakBlock).opening) {
                Chip(if (b.kind == BreakKind.COMEBACK) "→ Reaction" else "→ Come back", Pal.amber, onClick = { vm.toggleKind(i) })
                if (b.kind == BreakKind.REACTION) Chip(if (b.dinner) "Dinner" else "Lunch", Pal.ok, onClick = { vm.toggleDinner(i) })
                Chip("Xoá", Pal.bad, onClick = { vm.removeBreak(i) })
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

    PixelPanel {
        Title("Cấu hình xuất")
        Spacer(Modifier.height(8.dp))
        Label("Codec")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            Codec.entries.forEach { c ->
                Chip(c.label, if (c == st.codec) Pal.orange else Pal.muted, onClick = { vm.updateSettings(st.copy(codec = c)) })
            }
        }
        Label("Bitrate video: ${"%.1f".format(st.videoMbps)} Mbps (1080p30)")
        Slider(
            value = st.videoMbps, onValueChange = { vm.updateSettings(st.copy(videoMbps = Math.round(it * 2f) / 2f)) },
            valueRange = 2f..20f,
            colors = SliderDefaults.colors(thumbColor = Pal.amber, activeTrackColor = Pal.orange, inactiveTrackColor = Pal.panel2),
        )
        ToggleRow("Chuyển cảnh menu GTA 6 → rest mode → PS5 connected", st.transitions) { vm.updateSettings(st.copy(transitions = it)) }
        ToggleRow("Kết thúc stream bằng GTA crash (đóng băng → lỗi PS5, video dừng ở màn lỗi) – tuỳ chọn", st.gtaCrashEnding) { vm.updateSettings(st.copy(gtaCrashEnding = it)) }
        ToggleRow("Contour reaction bằng GPU (shader) – nhanh; tắt nếu lỗi/hiển thị lạ", st.gpuContour) { vm.updateSettings(st.copy(gpuContour = it)) }
        ToggleRow("Chế độ an toàn (tắt contour + social, dùng để dò lỗi)", st.safeMode) { vm.updateSettings(st.copy(safeMode = it)) }
        Spacer(Modifier.height(8.dp))
        Label("Ước lượng trước khi xuất: ${fmtBytes(estimate)} · ổ trống: ${if (free >= 0) fmtBytes(free) else "?"}",
            color = if (free in 0 until estimate) Pal.bad else Pal.amber)
        if (moviesOk) Label("Ghi thẳng vào Movies/StreamComposer (thư viện máy), không có file đệm trung gian.")
        else {
            Label("⚠ Chưa có quyền ghi vào thư mục Movies – đang lưu trong data của app (khó tìm).", color = Pal.amber)
            Spacer(Modifier.height(6.dp))
            ForgeButton("Cấp quyền lưu vào Movies", Modifier.fillMaxWidth(), primary = false) {
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    } catch (e: Exception) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    if (tl.warnings.isNotEmpty() || res.games.isEmpty()) {
        PixelPanel {
            if (res.games.isEmpty()) Label("⚠ Chưa chọn video game.", color = Pal.bad)
            tl.warnings.forEach { Label("⚠ $it", color = Pal.amber) }
        }
    }

    PixelPanel {
        Title(if (ui.running) "Đang xuất…" else if (ui.done) "Xong!" else "Sẵn sàng")
        Spacer(Modifier.height(8.dp))
        PixelProgress(ui.progress)
        if (layout.isNotEmpty() && (ui.running || ui.done)) {
            Spacer(Modifier.height(6.dp))
            SegmentMap(layout, if (ui.done) 1f else ui.progress)
        }
        Spacer(Modifier.height(8.dp))
        Label("${"%.1f".format(ui.progress * 100)}%  ·  đã chạy ${fmtDur(ui.elapsedSec * 1000)}", color = Pal.text)
        if (ui.running && ui.segKind != null) {
            Spacer(Modifier.height(6.dp))
            Label("▶ Đang render: ${ui.segTitle}", color = Pal.amber)
            Label("Khối ${ui.segIndex + 1}/${ui.segCount}  ·  ${fmtDur(ui.segPosMs)} / ${fmtDur(ui.segDurMs)}  ·  giờ live ${ui.streamClock}", color = Pal.text)
            Spacer(Modifier.height(4.dp))
            PixelProgress(if (ui.segDurMs > 0) ui.segPosMs.toFloat() / ui.segDurMs else 0f, segments = 24)
            if (ui.warmup) Label("⏳ Đang chuẩn bị đoạn này (tua video nguồn tới điểm cắt, giải mã bỏ các khung phía trước). File tạm ít tăng là bình thường – máy không treo, sẽ tự nhanh lại.", color = Pal.amber)
            else if (ui.segSpeedX > 0f) Label("Tốc độ riêng của đoạn này: ${"%.2f".format(ui.segSpeedX)}× thời gian thực", color = Pal.muted)
            ui.preview?.let { bmp ->
                Spacer(Modifier.height(8.dp))
                Image(
                    bitmap = bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Pal.bg),
                )
                Label("Khung nguồn đang xử lý: ${ui.previewCaption}", color = Pal.muted)
            }
        }
        if (ui.running) {
            val etaTxt = if (ui.etaSec >= 0) "còn ${if (ui.etaApprox) "~" else ""}${fmtDur(ui.etaSec * 1000)}${if (ui.etaApprox) " (ước lượng thô)" else ""} · xong lúc " +
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + ui.etaSec * 1000)) else "đang đo…"
            Label("ETA: $etaTxt", color = Pal.amber)
            Label("Tốc độ trung bình: ${"%.2f".format(ui.speedX)}× thời gian thực", color = Pal.text)
            Label("File hiện tại: ${fmtBytes(ui.bytes)}  →  dự đoán cuối: ${if (ui.predictedBytes > 0) fmtBytes(ui.predictedBytes) else "…"}", color = Pal.amber)
        }
        ui.error?.let { Label("✕ $it", color = Pal.bad) }
        if (ui.done) {
            Label("File: ${ui.outputPath}  (${fmtBytes(ui.bytes)})", color = Pal.ok)
            Spacer(Modifier.height(8.dp))
            ForgeButton("Mở / chia sẻ video", primary = false) {
                val f = File(ui.outputPath ?: return@ForgeButton)
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                ctx.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Mở video"
                ))
            }
        }
        Spacer(Modifier.height(10.dp))
        if (ui.running) {
            ForgeButton("Huỷ xuất", Modifier.fillMaxWidth(), primary = false) { ExportService.cancel(ctx) }
        } else {
            ForgeButton("Bắt đầu xuất", Modifier.fillMaxWidth(), enabled = plan.isNotEmpty() && res.games.isNotEmpty()) { vm.startExport() }
        }
    }
}

private fun kindColor(k: SegKind): Color = when (k) {
    SegKind.GAME -> Pal.muted
    SegKind.REACTION -> Pal.orange
    SegKind.REST -> Color(0xFF6C8CFF)
    SegKind.CONNECT -> Color(0xFF4AA3DF)
    SegKind.COMEBACK -> Pal.ember
    SegKind.CRASH -> Pal.bad
}

/** Bản đồ toàn bộ video: mỗi loại đoạn một màu, vạch trắng = vị trí đang render. */
@Composable
private fun SegmentMap(layout: List<SegInfo>, progress: Float) {
    val total = layout.sumOf { it.durationMs }.coerceAtLeast(1L).toFloat()
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        var x = 0f
        for (b in layout) {
            val w = size.width * (b.durationMs / total)
            drawRect(kindColor(b.kind), topLeft = Offset(x, 0f), size = Size(maxOf(w - 0.5f, 0.5f), size.height))
            x += w
        }
        val px = size.width * progress.coerceIn(0f, 1f)
        drawRect(Color.White, topLeft = Offset((px - 1.5f).coerceAtLeast(0f), 0f), size = Size(3f, size.height))
    }
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(
            SegKind.GAME to "Game", SegKind.REACTION to "Reaction", SegKind.REST to "Rest mode",
            SegKind.CONNECT to "PS5", SegKind.COMEBACK to "Come back", SegKind.CRASH to "Crash",
        ).forEach { (k, name) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Dot(kindColor(k))
                Text(name, color = Pal.muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ToggleRow(text: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Pal.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Pal.bg, checkedTrackColor = Pal.orange, uncheckedThumbColor = Pal.muted, uncheckedTrackColor = Pal.panel2),
        )
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
    Box(Modifier.fillMaxSize().background(Pal.bg).statusBarsPadding().navigationBarsPadding().padding(20.dp), contentAlignment = Alignment.Center) {
        PixelPanel {
            Title("Bật chế độ pin không tối ưu")
            Spacer(Modifier.height(10.dp))
            Text(
                "Xuất video vài tiếng cần app chạy ngầm liên tục. Nếu hệ thống tối ưu pin, app có thể bị kill giữa chừng và mất tiến độ.",
                color = Pal.text, fontSize = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
            Label("Bấm nút bên dưới → chọn “Cho phép”. Trên Xiaomi/Oppo/Vivo/Samsung nên bật thêm Tự khởi chạy / Không hạn chế nền trong cài đặt pin.")
            Spacer(Modifier.height(14.dp))
            ForgeButton("Bật không tối ưu pin", Modifier.fillMaxWidth(), onClick = onRequest)
            Spacer(Modifier.height(8.dp))
            ForgeButton("Để sau (không khuyến nghị)", Modifier.fillMaxWidth(), primary = false, onClick = onSkip)
        }
    }
}
