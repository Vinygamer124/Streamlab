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

    const val LOOP_MS = 10_000L
    const val REST_A_MS = 4_600L
    const val REST_B_MS = 5_733L
    const val REST_MS = REST_A_MS + REST_B_MS
    /** Thời điểm (ms, tính từ đầu chuyển cảnh) mà đồng hồ PS5 đang hiển thị – dùng để tính giờ trên đồng hồ. */
    const val CLOCK_AT_MS = 3_300L
    const val CONNECT_MS = 10_000L
    const val SILENCE_MS = 60_000L

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
) : Seg

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
            return Timeline(out, warnings.distinct())
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
