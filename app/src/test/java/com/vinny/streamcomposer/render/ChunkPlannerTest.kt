package com.vinny.streamcomposer.render

import android.net.Uri
import com.vinny.streamcomposer.data.AssetSeg
import com.vinny.streamcomposer.data.Clips
import com.vinny.streamcomposer.data.CrashSeg
import com.vinny.streamcomposer.data.FreezeSeg
import com.vinny.streamcomposer.data.GameSeg
import com.vinny.streamcomposer.data.ReactionSeg
import com.vinny.streamcomposer.data.RestSeg
import com.vinny.streamcomposer.data.Seg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class ChunkPlannerTest {
    private val uri: Uri = Mockito.mock(Uri::class.java)
    private val min = 60_000L

    /** Dựng timeline giống thật: mở màn → game → break reaction (Rest + reaction + màn chờ + PS5) → game → kết thúc. */
    private fun sampleTimeline(): List<Seg> {
        val out = ArrayList<Seg>()
        fun total() = out.sumOf { it.durationMs }
        repeat(18) { out += AssetSeg(Clips.STARTING_SOON, 10_000L, music = true) }          // 3 phút mở màn
        out += GameSeg(uri, 0L, 40 * min)
        out += RestSeg(Clips.REST_A_MS, 20 * 60, uri, 40 * min, offsetMs = total())
        out += AssetSeg(Clips.REST_B, Clips.REST_B_MS)
        out += ReactionSeg(uri, 10 * min, dinner = false)
        repeat(30) { out += AssetSeg(Clips.BE_RIGHT_BACK, 10_000L, music = true) }          // 5 phút màn chờ
        out += AssetSeg(Clips.CONNECTING, Clips.CONNECT_MS)
        out += AssetSeg(Clips.CONNECTED, Clips.CONNECT_MS)
        out += GameSeg(uri, 40 * min, 30 * min)
        repeat(3) { out += AssetSeg(Clips.THANKS, 10_000L, music = true) }
        return out
    }

    @Test fun tongThoiLuongVaViTriBatDauKhop() {
        val segs = sampleTimeline()
        val chunks = ChunkPlanner.plan(segs)
        assertTrue("phải chia thành nhiều phần", chunks.size > 3)
        assertEquals(segs.sumOf { it.durationMs }, chunks.sumOf { it.durationMs })
        var acc = 0L
        for (c in chunks) { assertEquals(acc, c.startMs); acc += c.durationMs }
        // mọi phần (trừ phần cuối) đạt tối thiểu độ dài mục tiêu
        chunks.dropLast(1).forEach { assertTrue(it.durationMs >= ChunkPlanner.TARGET_MS) }
    }

    @Test fun khongCatGiuaCumNhacHayChuyenCanh() {
        val chunks = ChunkPlanner.plan(sampleTimeline())
        for (i in 0 until chunks.size - 1) {
            val a = chunks[i].segs.last()
            val b = chunks[i + 1].segs.first()
            assertFalse("cắt giữa cụm nhạc", a is AssetSeg && a.music && b is AssetSeg && b.music)
            assertFalse("cắt ngay sau RestSeg", a is RestSeg)
            assertFalse("cắt trước REST_B", b is AssetSeg && b.file == Clips.REST_B)
            assertFalse("cắt giữa PS5 connecting và connected", a is AssetSeg && a.file == Clips.CONNECTING)
        }
    }

    @Test fun cacManhGameNoiLienMachVaNhoHonMucTieu() {
        val chunks = ChunkPlanner.plan(sampleTimeline())
        val games = chunks.flatMap { it.segs }.filterIsInstance<GameSeg>()
        games.forEach { assertTrue(it.durationMs <= ChunkPlanner.TARGET_MS) }
        // hai nhóm game: 0..40 phút và 40..70 phút, mỗi nhóm phủ liền mạch không hở không chồng
        var expect = 0L
        for (g in games) { assertEquals(expect, g.startMs); expect += g.durationMs }
        assertEquals(70 * min, expect)
    }

    @Test fun offsetRestDuocQuyVeThoiGianTrongPhan() {
        val segs = sampleTimeline()
        val globalOffset = (segs.first { it is RestSeg } as RestSeg).offsetMs
        val chunks = ChunkPlanner.plan(segs)
        val c = chunks.first { ch -> ch.segs.any { it is RestSeg } }
        val rest = c.segs.first { it is RestSeg } as RestSeg
        assertEquals(globalOffset - c.startMs, rest.offsetMs)
        // vị trí thật của RestSeg trong phần phải đúng bằng offsetMs
        val idx = c.segs.indexOf(rest)
        assertEquals(c.segs.take(idx).sumOf { it.durationMs }, rest.offsetMs)
    }

    @Test fun timelineNganChiMotPhan() {
        val segs = listOf<Seg>(
            AssetSeg(Clips.STARTING_SOON, 10_000L, music = true),
            GameSeg(uri, 0L, 3 * min),
            AssetSeg(Clips.THANKS, 10_000L, music = true),
        )
        val chunks = ChunkPlanner.plan(segs)
        assertEquals(1, chunks.size)
        assertEquals(segs.sumOf { it.durationMs }, chunks[0].durationMs)
    }

    @Test fun duoiCrashGiuNguyenMotKhoi() {
        val segs = ArrayList<Seg>()
        segs += GameSeg(uri, 0L, 20 * min)
        segs += FreezeSeg(uri, 20 * min, Clips.FREEZE_MS)
        segs += CrashSeg(Clips.CRASH_MS, uri, 20 * min, offsetMs = 20 * min + Clips.FREEZE_MS)
        segs += AssetSeg(Clips.CRASH_HOLD, 3 * min, startMs = Clips.CRASH_HOLD_FIRST_START_MS)
        val chunks = ChunkPlanner.plan(segs)
        // Freeze + Crash + Hold không bị tách khỏi nhau
        val withFreeze = chunks.first { c -> c.segs.any { it is FreezeSeg } }
        assertTrue(withFreeze.segs.any { it is CrashSeg })
        assertTrue(withFreeze.segs.any { it is AssetSeg && it.file == Clips.CRASH_HOLD })
        assertEquals(segs.sumOf { it.durationMs }, chunks.sumOf { it.durationMs })
    }
}
