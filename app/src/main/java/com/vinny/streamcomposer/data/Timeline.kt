package com.vinny.streamcomposer.data

import android.net.Uri

/** Thời lượng thật của các clip bundle trong assets/clips (ms). */
object Clips {
    const val COMEBACK = "comeback.mp4"
    const val STARTING_SOON = "starting_soon.mp4"     // 10 s, thanh tiến trình đầy dần – màn mở màn stream
    const val BE_RIGHT_BACK = "be_right_back.mp4"     // 10 s, loop – màn nghỉ giữa stream
    const val THANKS = "thanks_watching.mp4"          // 10 s, loop – màn kết thúc stream
    const val LUNCH_CARD = "lunch_card.mp4"
    const val CONNECTING = "ps5_connecting.mp4"
    const val CONNECTED = "ps5_connected.mp4"
    const val REST_A = "rest_a.mp4"   // 0–4,6s: menu GTA 6 → PS5 → Power (nền đen, overlay vẽ bằng code)
    const val REST_B = "rest_b.mp4"   // 4,6–10,3s: màn hình "Putting your PS5 into rest mode…"
    const val SILENCE = "silence.m4a"
    const val CRASH = "ps5_crash.mp4" // 8,5 s: tối → màn "Game or App Error" → "Report Problem" + âm thanh PS5, nền loop mới đã ghép sẵn
    /** Màn "Report Problem" tĩnh trên nền loop (28 s, khít đầu-cuối, im lặng). Nối tiếp ngay sau ps5_crash.mp4 để kéo dài màn lỗi. */
    const val CRASH_HOLD = "crash_hold.mp4"

    const val LOOP_MS = 10_000L
    const val REST_A_MS = 4_600L
    const val REST_B_MS = 5_733L
    const val REST_MS = REST_A_MS + REST_B_MS
    /** Thời điểm (ms, tính từ đầu chuyển cảnh) mà đồng hồ PS5 đang hiển thị – dùng để tính giờ trên đồng hồ. */
    const val CLOCK_AT_MS = 3_300L
    const val CONNECT_MS = 10_000L
    const val SILENCE_MS = 60_000L

    // ---- GTA crash cuối stream ----
    const val CRASH_MS = 8_500L
    const val FREEZE_MS = 1_280L          // game đứng hình + âm thanh lặp trước khi nhoè tối
    const val FREEZE_LOOP_MS = 160L       // đoạn âm thanh cuối được lặp lại (tiếng "rè" khi treo)
    const val CRASH_TOTAL_MS = FREEZE_MS + CRASH_MS
    const val CRASH_MIN_GAME_MS = 5_000L  // đoạn game cuối phải còn ít nhất chừng này sau khi cắt, nếu không bỏ qua crash
    const val CRASH_HOLD_LOOP_MS = 28_000L // độ dài một vòng nền loop trong crash_hold.mp4
    /** ps5_crash.mp4 kết thúc ở giây 8,5 của nền loop → vòng đầu của crash_hold bắt đầu từ đúng giây đó để nền liền mạch. */
    const val CRASH_HOLD_FIRST_START_MS = CRASH_MS

    // ---- Thanks for watching cuối stream ----
    const val THANKS_MIN_GAME_MS = 5_000L // đoạn game cuối phải còn ít nhất chừng này sau khi cắt, nếu không bỏ qua

    fun uri(name: String): Uri = Uri.parse("asset:///clips/$name")
}

/** Một đoạn trên timeline cuối cùng, sẵn sàng đổi thành EditedMediaItem. */
sealed interface Seg { val durationMs: Long }

data class GameSeg(val src: Uri, val startMs: Long, override val durationMs: Long) : Seg

/**
 * @param music   true = đoạn Come back soon (nhạc người dùng được trải lên đoạn này)
 * @param startMs vị trí bắt đầu lấy trong clip (mặc định từ đầu clip)
 */
data class AssetSeg(val file: String, override val durationMs: Long, val music: Boolean = false, val startMs: Long = 0L) : Seg

/**
 * Phần đầu chuyển cảnh (menu GTA 6 → PS5 → Power).
 * @param clockMin giờ hiển thị trên đồng hồ PS5, tính bằng phút trong ngày (khớp giờ live)
 * @param frozenSrc/frozenPosMs khung game cuối cùng trước khi dừng – làm nền mờ cho menu
 */
data class RestSeg(
    override val durationMs: Long,
    val clockMin: Int,
    val frozenSrc: Uri?,
    val frozenPosMs: Long,
    /** Vị trí bắt đầu của đoạn này trên timeline (ms) – để overlay quy về thời gian tương đối. */
    val offsetMs: Long = 0L,
) : Seg

/** Game đóng băng: giữ khung tại posMs, âm thanh là FREEZE_LOOP_MS cuối trước posMs lặp lại. */
data class FreezeSeg(val src: Uri, val posMs: Long, override val durationMs: Long) : Seg

/** Cảnh crash PS5 (clip bundle). Khung game đóng băng phủ lên rồi tối dần. offsetMs = vị trí bắt đầu trên timeline. */
data class CrashSeg(override val durationMs: Long, val frozenSrc: Uri, val frozenPosMs: Long, val offsetMs: Long) : Seg

data class ReactionSeg(val src: Uri, override val durationMs: Long, val dinner: Boolean) : Seg

const val THANKS_MIN_SEC = 10
const val THANKS_MAX_SEC = 120

data class Timeline(val segs: List<Seg>, val warnings: List<String>) {
    val totalMs: Long get() = segs.sumOf { it.durationMs }

    companion object {
        fun build(plan: List<PlanBlock>, res: Resources, st: ExportSettings, streamStartMin: Int = 20 * 60): Timeline {
            val warnings = ArrayList<String>()
            val out = ArrayList<Seg>()
            val games = GameCursor(res.games)
            var reactionIdx = 0
            var lastGame: GameSeg? = null

            for (b in plan) when (b) {
                is GameBlock -> {
                    val (segs, shortage) = games.take(b.durationMs)
                    out += segs
                    segs.lastOrNull { it is GameSeg }?.let { lastGame = it as GameSeg }
                    if (segs.lastOrNull() !is GameSeg) lastGame = null
                    if (shortage > 0) warnings += "Thiếu ${fmtDur(shortage)} video game – đoạn đó được lấp bằng Come back soon."
                }
                is BreakBlock -> {
                    val trans = st.transitions && !b.opening
                    val overhead = if (trans) Clips.REST_MS + 2 * Clips.CONNECT_MS else 0L
                    val breakStartMs = out.sumOf { it.durationMs }
                    val inner = (b.durationMs - overhead).coerceAtLeast(Clips.LOOP_MS)
                    if (trans) {
                        val clock = Math.floorMod(((streamStartMin * 60_000L + breakStartMs + Clips.CLOCK_AT_MS) / 60_000L).toInt(), 1440)
                        val g = lastGame
                        out += RestSeg(
                            Clips.REST_A_MS, clock,
                            g?.src, ((g?.let { it.startMs + it.durationMs } ?: 0L) - 120L).coerceAtLeast(0L),
                            offsetMs = breakStartMs,
                        )
                        out += AssetSeg(Clips.REST_B, Clips.REST_B_MS)
                    }
                    // màn chờ: mở màn dùng openingScreen, các break còn lại dùng breakScreen
                    val screen = if (b.opening) st.openingScreen.file else st.breakScreen.file
                    if (b.kind == BreakKind.REACTION && res.reactions.isNotEmpty()) {
                        val r = res.reactions[reactionIdx++ % res.reactions.size]
                        val play = minOf(inner, r.durationMs)
                        if (play >= 1000) out += ReactionSeg(r.uri, play, b.dinner)
                        out += loops(inner - maxOf(play, 0L), screen)
                    } else {
                        if (b.kind == BreakKind.REACTION) warnings += "Break reaction nhưng chưa có video reaction – dùng ${st.breakScreen.label}."
                        out += loops(inner, screen)
                    }
                    if (trans) {
                        out += AssetSeg(Clips.CONNECTING, Clips.CONNECT_MS)
                        out += AssetSeg(Clips.CONNECTED, Clips.CONNECT_MS)
                    }
                }
            }
            if (st.gtaCrashEnding) {
                applyCrash(out, warnings, st.crashHoldMin.coerceIn(0, 10) * MIN_MS)
                if (st.thanksEnding) warnings += "Đang bật GTA crash nên bỏ qua màn Thanks for watching (chỉ chọn một kiểu kết thúc)."
            } else if (st.thanksEnding) {
                applyThanks(out, warnings, st.thanksSec.coerceIn(THANKS_MIN_SEC, THANKS_MAX_SEC) * 1000L)
            }
            return Timeline(out, warnings.distinct())
        }

        /**
         * Kết thúc bằng crash mà KHÔNG đổi tổng thời lượng: cắt CRASH_TOTAL_MS (+ thời gian kéo dài màn lỗi) cuối của đoạn game cuối
         * (nên điểm đóng băng nằm trước giờ kết thúc chứ không phải giây cuối cùng của video game), rồi nối đóng băng + crash
         * + màn "Report Problem" giữ trên nền loop trong holdWantMs.
         */
        private fun applyCrash(out: MutableList<Seg>, warnings: MutableList<String>, holdWantMs: Long) {
            val last = out.lastOrNull() as? GameSeg
            if (last == null) {
                warnings += "Bật GTA crash nhưng lịch không kết thúc bằng đoạn game – bỏ qua crash."
                return
            }
            if (last.durationMs - Clips.CRASH_TOTAL_MS < Clips.CRASH_MIN_GAME_MS) {
                warnings += "Đoạn game cuối quá ngắn để chèn GTA crash – bỏ qua crash."
                return
            }
            // phần kéo dài lấy từ game cuối, luôn chừa lại tối thiểu CRASH_MIN_GAME_MS game
            val maxHold = last.durationMs - Clips.CRASH_TOTAL_MS - Clips.CRASH_MIN_GAME_MS
            val hold = (minOf(holdWantMs, maxHold).coerceAtLeast(0L) / 1000L) * 1000L // làm tròn xuống giây → tổng thời lượng không bị hụt
            if (hold < holdWantMs) {
                warnings += "Đoạn game cuối chỉ đủ để kéo màn lỗi PS5 thêm ${fmtDur(hold)} (muốn ${fmtDur(holdWantMs)})."
            }
            val keep = last.durationMs - Clips.CRASH_TOTAL_MS - hold
            out.removeAt(out.lastIndex)
            out += GameSeg(last.src, last.startMs, keep)
            val pos = last.startMs + keep
            out += FreezeSeg(last.src, pos, Clips.FREEZE_MS)
            val offset = out.sumOf { it.durationMs }
            out += CrashSeg(Clips.CRASH_MS, last.src, (pos - 40L).coerceAtLeast(0L), offset)
            out += crashHold(hold)
        }

        /**
         * Kết thúc bằng màn "Thanks for watching" MÀ KHÔNG đổi tổng thời lượng: cắt thanksMs cuối của đoạn game cuối
         * rồi nối màn cảm ơn (loop 10 s, nhạc người dùng trải lên như Come back soon).
         */
        private fun applyThanks(out: MutableList<Seg>, warnings: MutableList<String>, thanksMs: Long) {
            val last = out.lastOrNull() as? GameSeg
            if (last == null) {
                warnings += "Bật Thanks for watching nhưng lịch không kết thúc bằng đoạn game – bỏ qua."
                return
            }
            if (last.durationMs - thanksMs < Clips.THANKS_MIN_GAME_MS) {
                warnings += "Đoạn game cuối quá ngắn để chèn Thanks for watching – bỏ qua."
                return
            }
            out.removeAt(out.lastIndex)
            out += GameSeg(last.src, last.startMs, last.durationMs - thanksMs)
            out += loops(thanksMs, Clips.THANKS)
        }

        /** Màn "Report Problem" giữ nguyên trên nền loop: vòng đầu bắt đầu ở giây 8,5 (nối khít với crash), các vòng sau từ đầu clip. */
        fun crashHold(durMs: Long): List<Seg> {
            val out = ArrayList<Seg>()
            var left = durMs
            var start = Clips.CRASH_HOLD_FIRST_START_MS
            while (left > 0) {
                val t = minOf(left, Clips.CRASH_HOLD_LOOP_MS - start)
                out += AssetSeg(Clips.CRASH_HOLD, t, startMs = start)
                left -= t
                start = 0L
            }
            return out
        }

        /** Lặp clip 10 s (Come back soon / Starting soon / Be right back / Thanks for watching) cho đủ durMs. */
        fun loops(durMs: Long, file: String = Clips.COMEBACK): List<Seg> {
            val out = ArrayList<Seg>()
            var left = durMs
            while (left >= 500) {
                val t = minOf(left, Clips.LOOP_MS)
                out += AssetSeg(file, t, music = true)
                left -= t
            }
            return out
        }
    }
}

private class GameCursor(private val games: List<MediaRef>) {
    private var i = 0
    private var off = games.firstOrNull()?.trimStartMs ?: 0L

    fun take(durMs: Long): Pair<List<Seg>, Long> {
        val out = ArrayList<Seg>()
        var need = durMs
        while (need >= 500) {
            if (i >= games.size) {
                out += Timeline.loops(need)
                return out to need
            }
            val g = games[i]
            val avail = g.trimEndMs - off
            if (avail < 500) { i++; off = games.getOrNull(i)?.trimStartMs ?: 0L; continue }
            val t = minOf(avail, need)
            out += GameSeg(g.uri, off, t)
            off += t
            need -= t
        }
        return out to 0L
    }
}
