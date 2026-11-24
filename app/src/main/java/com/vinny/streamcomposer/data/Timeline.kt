package com.vinny.streamcomposer.data

import android.net.Uri

/** Thời lượng thật của các clip bundle trong assets/clips (ms). */
object Clips {
    const val COMEBACK = "comeback.mp4"
    const val LUNCH_CARD = "lunch_card.mp4"
    const val CONNECTING = "ps5_connecting.mp4"
    const val CONNECTED = "ps5_connected.mp4"
    const val REST = "rest_transition.mp4"
    const val SILENCE = "silence.m4a"

    const val LOOP_MS = 10_000L
    const val REST_MS = 10_366L
    const val CONNECT_MS = 10_000L
    const val SILENCE_MS = 60_000L

    fun uri(name: String): Uri = Uri.parse("asset:///clips/$name")
}

/** Một đoạn trên timeline cuối cùng, sẵn sàng đổi thành EditedMediaItem. */
sealed interface Seg { val durationMs: Long }

data class GameSeg(val src: Uri, val startMs: Long, override val durationMs: Long) : Seg

/** @param music true = đoạn Come back soon (nhạc người dùng được trải lên đoạn này) */
data class AssetSeg(val file: String, override val durationMs: Long, val music: Boolean = false) : Seg

data class ReactionSeg(val src: Uri, override val durationMs: Long, val dinner: Boolean) : Seg

data class Timeline(val segs: List<Seg>, val warnings: List<String>) {
    val totalMs: Long get() = segs.sumOf { it.durationMs }

    companion object {
        fun build(plan: List<PlanBlock>, res: Resources, st: ExportSettings): Timeline {
            val warnings = ArrayList<String>()
            val out = ArrayList<Seg>()
            val games = GameCursor(res.games)
            var reactionIdx = 0

            for (b in plan) when (b) {
                is GameBlock -> {
                    val (segs, shortage) = games.take(b.durationMs)
                    out += segs
                    if (shortage > 0) warnings += "Thiếu ${fmtDur(shortage)} video game – đoạn đó được lấp bằng Come back soon."
                }
                is BreakBlock -> {
                    val trans = st.transitions && !b.opening
                    val overhead = if (trans) Clips.REST_MS + 2 * Clips.CONNECT_MS else 0L
                    val inner = (b.durationMs - overhead).coerceAtLeast(Clips.LOOP_MS)
                    if (trans) out += AssetSeg(Clips.REST, Clips.REST_MS)
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
