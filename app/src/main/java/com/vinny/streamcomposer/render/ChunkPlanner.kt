package com.vinny.streamcomposer.render

import com.vinny.streamcomposer.data.AssetSeg
import com.vinny.streamcomposer.data.Clips
import com.vinny.streamcomposer.data.CrashSeg
import com.vinny.streamcomposer.data.GameSeg
import com.vinny.streamcomposer.data.ReactionSeg
import com.vinny.streamcomposer.data.RestSeg
import com.vinny.streamcomposer.data.Seg

/**
 * Một phần xuất: các đoạn liền nhau của timeline.
 * @param startMs vị trí bắt đầu của phần này trên timeline gốc
 * Các đoạn có `offsetMs` (Rest, Crash) đã được quy về thời gian tương đối của phần.
 */
data class ExportChunk(val segs: List<Seg>, val startMs: Long) {
    val durationMs: Long get() = segs.sumOf { it.durationMs }
}

/**
 * Chia timeline thành nhiều phần ~[TARGET_MS] để xuất lần lượt, mỗi phần một Transformer riêng.
 *
 * Lý do: xuất một mạch 1,5–3 tiếng thì chỉ cần encoder âm thanh AAC (phần mềm, dùng chung tiến trình
 * mediaswcodec) chết một lần là mất sạch. Chia phần cho phép (1) thử lại ĐÚNG phần bị lỗi thay vì làm lại từ đầu,
 * (2) giải phóng toàn bộ codec sau mỗi phần.
 *
 * Quy tắc cắt (xem [canCut]): không bao giờ cắt giữa một cụm nhạc (nhạc sẽ bị bắt đầu lại), giữa chuyển cảnh
 * Rest mode, hay giữa chuỗi đóng băng/crash. Đoạn game dài được cắt đều thành các mảnh liền mạch.
 */
object ChunkPlanner {
    const val TARGET_MS = 6 * 60_000L

    fun plan(segs: List<Seg>, targetMs: Long = TARGET_MS): List<ExportChunk> {
        val pieces = splitLongGames(segs, targetMs)
        val out = ArrayList<ExportChunk>()
        var cur = ArrayList<Seg>()
        var curMs = 0L
        var startMs = 0L
        for ((i, s) in pieces.withIndex()) {
            cur += s
            curMs += s.durationMs
            val next = pieces.getOrNull(i + 1)
            if (next != null && curMs >= targetMs && canCut(s, next)) {
                out += ExportChunk(rebase(cur, startMs), startMs)
                startMs += curMs
                cur = ArrayList()
                curMs = 0L
            }
        }
        if (cur.isNotEmpty()) out += ExportChunk(rebase(cur, startMs), startMs)
        return out
    }

    /** Được phép cắt giữa [prev] và [next] không. */
    internal fun canCut(prev: Seg, next: Seg): Boolean = when (prev) {
        is GameSeg, is ReactionSeg -> true
        is AssetSeg -> prev.file == Clips.CONNECTED || (prev.music && !(next is AssetSeg && next.music))
        else -> false
    }

    /** Đoạn game dài hơn 1,25 × mục tiêu thì cắt đều thành các mảnh liền nhau (cùng nguồn, startMs nối tiếp). */
    private fun splitLongGames(segs: List<Seg>, targetMs: Long): List<Seg> {
        val out = ArrayList<Seg>(segs.size + 16)
        for (s in segs) {
            if (s is GameSeg && s.durationMs > targetMs * 5 / 4) {
                val n = ((s.durationMs + targetMs - 1) / targetMs).toInt()
                var done = 0L
                for (k in 0 until n) {
                    val end = s.durationMs * (k + 1) / n
                    out += GameSeg(s.src, s.startMs + done, end - done)
                    done = end
                }
            } else out += s
        }
        return out
    }

    /** Quy offset (vị trí trên timeline gốc) của Rest/Crash về vị trí trong phần. */
    private fun rebase(segs: List<Seg>, startMs: Long): List<Seg> =
        if (startMs == 0L) segs else segs.map {
            when (it) {
                is RestSeg -> it.copy(offsetMs = (it.offsetMs - startMs).coerceAtLeast(0L))
                is CrashSeg -> it.copy(offsetMs = (it.offsetMs - startMs).coerceAtLeast(0L))
                else -> it
            }
        }
}
