package com.vinny.streamcomposer.data

import android.net.Uri

const val MIN_MS = 60_000L

/** Một file người dùng đã chọn (video/nhạc). */
data class MediaRef(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val trimStartMs: Long = 0L,          // cắt: bắt đầu lấy từ đây
    val trimEndMs: Long = durationMs,    // cắt: lấy tới đây
) {
    /** Thời lượng thực sự được dùng sau khi cắt. */
    val playMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(0L)
}

enum class BreakKind { COMEBACK, REACTION }

/** Kế hoạch live = chuỗi block: Game / Break. */
sealed interface PlanBlock { val durationMs: Long }

data class GameBlock(override val durationMs: Long) : PlanBlock

/**
 * @param durationMs tổng thời lượng break (đã gồm chuyển cảnh rest-mode + PS5 connecting/connected)
 * @param opening    true = block mở màn (Come back soon ngay đầu stream, không có chuyển cảnh)
 */
data class BreakBlock(
    val kind: BreakKind,
    override val durationMs: Long,
    val opening: Boolean = false,
    val label: String = "",
    val dinner: Boolean = false,
) : PlanBlock

/** Tên hiển thị rõ ràng của một break. */
fun BreakBlock.title(): String = when {
    opening -> "Mở màn · màn chờ"
    kind == BreakKind.COMEBACK -> "Nghỉ · màn chờ"
    dinner -> "Dinner reaction"
    label.contains("lunch", ignoreCase = true) -> "Lunch reaction"
    else -> "Reaction"
}

data class Resources(
    val games: List<MediaRef> = emptyList(),
    val reactions: List<MediaRef> = emptyList(),
    val music: MediaRef? = null,
    val frameLunch: Uri? = null,   // null = khung mặc định trong app
    val frameDinner: Uri? = null,  // null = dùng khung lunch
    val socialOverlay: Uri? = null // null = PNG 3 social mặc định
)

enum class Codec(val label: String, val mime: String) {
    H264("H.264 (AVC)", "video/avc"),
    H265("H.265 (HEVC)", "video/hevc"),
}

data class ExportSettings(
    val codec: Codec = Codec.H265,
    val videoMbps: Float = 6f,
    val fragmentedMp4: Boolean = false,
    val transitions: Boolean = true,
    val safeMode: Boolean = false, // chẩn đoán: tắt overlay contour + social
    val gpuContour: Boolean = true, // contour bằng fragment shader (GPU); tắt = vẽ bitmap bằng CPU như bản cũ
    val gtaCrashEnding: Boolean = false, // kết thúc stream bằng cảnh GTA crash (đóng băng → lỗi PS5). Tuỳ chọn, mặc định tắt
    val openingScreen: OpeningScreen = OpeningScreen.STARTING, // màn mở màn stream
    val breakScreen: BreakScreen = BreakScreen.BRB,            // màn nghỉ giữa stream
    val thanksEnding: Boolean = true,    // kết thúc stream bằng màn Thanks for watching (lấy bớt từ cuối đoạn game nên tổng không đổi)
    val thanksSec: Int = 30,             // độ dài màn Thanks for watching (giây, bội số của 10)
    val crashHoldMin: Int = 3,           // kéo dài màn lỗi PS5 (Report Problem, nền loop) thêm bao nhiêu phút; lấy từ cuối đoạn game nên tổng không đổi
)

/** Màn chờ ở đầu stream (block mở màn). */
enum class OpeningScreen(val label: String, val file: String) {
    STARTING("Starting soon", Clips.STARTING_SOON),
    COMEBACK("Come back soon", Clips.COMEBACK),
}

/** Màn chờ ở các break giữa stream. */
enum class BreakScreen(val label: String, val file: String) {
    BRB("Be right back", Clips.BE_RIGHT_BACK),
    COMEBACK("Come back soon", Clips.COMEBACK),
}

data class StreamTime(val startMin: Int = 20 * 60, val endMin: Int = 23 * 60) {
    val totalMin: Int get() = Planner.durationMinutes(startMin, endMin)
}

fun fmtClock(min: Int): String = "%02d:%02d".format((min / 60) % 24, min % 60)

fun fmtDur(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
