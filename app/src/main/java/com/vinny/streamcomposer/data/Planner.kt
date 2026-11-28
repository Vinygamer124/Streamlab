package com.vinny.streamcomposer.data

import kotlin.math.roundToInt

/**
 * Gợi ý lịch live. Quy tắc (có thể sửa tay trong tab LỊCH LIVE):
 *  - Mở màn: Come back soon ≈ 1/30 độ dài stream, tối thiểu 3, tối đa 20 phút.
 *  - Nghỉ giữa trận: khoảng mỗi 75 phút chơi game một break.
 *  - Break rơi vào giờ ăn (11:00–14:00 / 17:30–20:30) và có video reaction -> Lunch/Dinner reaction.
 *  - Còn lại: Come back soon (5 phút, 8 phút nếu stream > 6 giờ); cứ 3 break chen 1 reaction nếu có reaction.
 */
object Planner {
    const val GAME_PER_BREAK_MIN = 75
    const val MIN_GAME_BLOCK_MIN = 15
    private const val LUNCH_FROM = 11 * 60
    private const val LUNCH_TO = 14 * 60
    private const val DINNER_FROM = 17 * 60 + 30
    private const val DINNER_TO = 20 * 60 + 30

    fun durationMinutes(startMin: Int, endMin: Int): Int {
        var d = endMin - startMin
        if (d <= 0) d += 24 * 60
        return d
    }

    fun openingMinutes(totalMin: Int): Int = (totalMin / 30.0).roundToInt().coerceIn(3, 20)

    private fun isMeal(clockMin: Int): Boolean? {
        val c = ((clockMin % 1440) + 1440) % 1440
        return when {
            c in LUNCH_FROM..LUNCH_TO -> false          // lunch
            c in DINNER_FROM..DINNER_TO -> true         // dinner
            else -> null
        }
    }

    fun suggest(time: StreamTime, reactions: List<MediaRef>): List<PlanBlock> {
        val total = time.totalMin
        val opening = openingMinutes(total)
        val remaining = total - opening

        var n = if (remaining < 90) 0 else (remaining / GAME_PER_BREAK_MIN).coerceAtMost(24)
        val kinds = ArrayList<BreakBlock>()
        while (true) {
            kinds.clear()
            var reactionIdx = 0
            for (k in 0 until n) {
                val clock = time.startMin + opening + ((k + 1) * remaining) / (n + 1)
                val meal = if (reactions.isNotEmpty()) isMeal(clock) else null
                val useReaction = reactions.isNotEmpty() && (meal != null || k % 3 == 2)
                if (useReaction) {
                    val r = reactions[reactionIdx++ % reactions.size]
                    val mins = (r.durationMs / MIN_MS).toInt().coerceIn(8, 25)
                    val label = when (meal) {
                        false -> "Lunch reaction"
                        true -> "Dinner reaction"
                        null -> "Reaction"
                    }
                    kinds += BreakBlock(BreakKind.REACTION, mins * MIN_MS, label = label, dinner = meal == true)
                } else {
                    val mins = if (total > 360) 8 else 5
                    kinds += BreakBlock(BreakKind.COMEBACK, mins * MIN_MS, label = "Come back soon")
                }
            }
            val gameTotalMin = remaining - kinds.sumOf { (it.durationMs / MIN_MS).toInt() }
            if (n == 0 || gameTotalMin >= (n + 1) * MIN_GAME_BLOCK_MIN) break
            n--
        }

        val gameTotalMin = remaining - kinds.sumOf { (it.durationMs / MIN_MS).toInt() }
        val per = gameTotalMin / (n + 1)
        val out = ArrayList<PlanBlock>()
        out += BreakBlock(BreakKind.COMEBACK, opening * MIN_MS, opening = true, label = "Mở màn – Come back soon")
        for (i in 0..n) {
            // dồn phần dư vào game block cuối
            val m = if (i == n) gameTotalMin - per * n else per
            out += GameBlock(m.coerceAtLeast(1) * MIN_MS)
            if (i < n) out += kinds[i]
        }
        return out
    }

    fun explain(time: StreamTime): String {
        val total = time.totalMin
        val o = openingMinutes(total)
        return "Stream ${total / 60}h${"%02d".format(total % 60)}: mở màn (màn chờ) $o phút " +
            "(≈1/30 độ dài live, giới hạn 3–20), nghỉ khoảng mỗi $GAME_PER_BREAK_MIN phút chơi."
    }

    fun totalMs(plan: List<PlanBlock>) = plan.sumOf { it.durationMs }
    fun gameMs(plan: List<PlanBlock>) = plan.filterIsInstance<GameBlock>().sumOf { it.durationMs }

    /** Co giãn các game block để tổng plan = đúng thời lượng live. */
    fun fitGames(plan: List<PlanBlock>, targetMs: Long): List<PlanBlock> {
        val breaks = plan.filterIsInstance<BreakBlock>().sumOf { it.durationMs }
        val wantGame = (targetMs - breaks).coerceAtLeast(MIN_MS)
        val curGame = gameMs(plan).coerceAtLeast(1)
        return plan.map {
            if (it is GameBlock) GameBlock(((it.durationMs.toDouble() / curGame * wantGame) / MIN_MS).roundToInt().coerceAtLeast(1) * MIN_MS) else it
        }
    }
}
