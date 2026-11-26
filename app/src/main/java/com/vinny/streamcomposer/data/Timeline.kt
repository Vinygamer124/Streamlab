package com.vinny.streamcomposer.data

import android.net.Uri

/** Thời lượng thật của các clip bundle trong assets/clips (ms). */
object Clips {
    const val COMEBACK = "comeback.mp4"
    const val LUNCH_CARD = "lunch_card.mp4"
    const val CONNECTING = "ps5_connecting.mp4"
    const val CONNECTED = "ps5_connected.mp4"
    const val REST_A = "rest_a.mp4"   // 0–4,6s: menu GTA 6 → PS5 → Power (nền đen, overlay vẽ bằng code)
    const val REST_B = "rest_b.mp4"   // 4,6–10,3s: màn hình "Putting your PS5 into rest mode…"
    const val SILENCE = "silence.m4a"
    const val CRASH = "ps5_crash.mp4" // 8,5 s: tối → màn "Game or App Error" → "Report Problem" + âm thanh PS5 (kết thúc ở khung cuối)

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

    fun uri(name: String): Uri = Uri.parse("asset:///clips/$name")
}

/** Một đoạn trên timeline cuối cùng, sẵn sàng đổi thành EditedMediaItem. */
sealed interface Seg { val durationMs: Long }

data class GameSeg(val src: Uri, val startMs: Long, override val durationMs: Long) : Seg

/** @param music true = đoạn Come back soon (nhạc người dùng được trải lên đoạn này) */
data class AssetSeg(val file: String, override val durationMs: Long, val music: Boolean = false) : Seg

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
                    if (b.kind == BreakKind.REACTION && res.reactions.isNotEmpty()) {
                        val r = res.reactions[reactionIdx++ % res.reactions.size]
                        val play = minOf(inner, r.durationMs)
                        if (play >= 1000) out += ReactionSeg(r.uri, play, b.dinner)
                        out += loops(inner - maxOf(play, 0L))
                    } else {
                        if (b.kind == BreakKind.REACTION) warnings += "Break reaction nhưng chưa có video reaction – dùng Come back soon."
                        out += loops(inner)
                    }
                    if (trans) {
                        out += AssetSeg(Clips.CONNECTING, Clips.CONNECT_MS)
                        out += AssetSeg(Clips.CONNECTED, Clips.CONNECT_MS)
                    }
                }
            }
            if (st.gtaCrashEnding) applyCrash(out, warnings)
            return Timeline(out, warnings.distinct())
        }

        /**
         * Kết thúc bằng crash mà KHÔNG đổi tổng thời lượng: cắt CRASH_TOTAL_MS cuối của đoạn game cuối
         * (nên điểm đóng băng nằm gần cuối chứ không phải giây cuối cùng của video game), rồi nối đóng băng + crash.
         */
        private fun applyCrash(out: MutableList<Seg>, warnings: MutableList<String>) {
            val last = out.lastOrNull() as? GameSeg
            if (last == null) {
                warnings += "Bật GTA crash nhưng lịch không kết thúc bằng đoạn game – bỏ qua crash."
                return
            }
            val keep = last.durationMs - Clips.CRASH_TOTAL_MS
            if (keep < Clips.CRASH_MIN_GAME_MS) {
                warnings += "Đoạn game cuối quá ngắn để chèn GTA crash – bỏ qua crash."
                return
            }
            out.removeAt(out.lastIndex)
            out += GameSeg(last.src, last.startMs, keep)
            val pos = last.startMs + keep
            out += FreezeSeg(last.src, pos, Clips.FREEZE_MS)
            val offset = out.sumOf { it.durationMs }
            out += CrashSeg(Clips.CRASH_MS, last.src, (pos - 40L).coerceAtLeast(0L), offset)
        }

        fun loops(durMs: Long): List<Seg> {
            val out = ArrayList<Seg>()
            var left = durMs
            while (left >= 500) {
                val t = minOf(left, Clips.LOOP_MS)
                out += AssetSeg(Clips.COMEBACK, t, music = true)
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
