package com.vinny.streamcomposer.render

import android.content.Context
import android.media.MediaCodecInfo
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import com.vinny.streamcomposer.data.AssetSeg
import com.vinny.streamcomposer.data.Clips
import com.vinny.streamcomposer.data.CrashSeg
import com.vinny.streamcomposer.data.FreezeSeg
import com.vinny.streamcomposer.data.ExportSettings
import com.vinny.streamcomposer.data.GameSeg
import com.vinny.streamcomposer.data.MediaRef
import com.vinny.streamcomposer.data.ReactionSeg
import com.vinny.streamcomposer.data.RestSeg
import com.vinny.streamcomposer.data.Resources
import com.vinny.streamcomposer.data.Seg
import com.vinny.streamcomposer.data.Timeline

/** Mọi thứ service cần để xuất – giữ trong bộ nhớ (singleton ExportBus) vì Intent không chứa nổi. */
data class ExportJob(
    val timeline: Timeline,
    val resources: Resources,
    val settings: ExportSettings,
    val outputPath: String,
    val streamStartMin: Int = 20 * 60,
)

/**
 * Dựng Composition cho Media3 Transformer.
 *
 *  Sequence 0 (video + audio game/reaction/clip): mỗi Seg = 1 EditedMediaItem.
 *  Sequence 1 (chỉ audio, nếu có nhạc): trải nhạc lặp lên các đoạn Come back soon, xen clip im lặng ở đoạn khác.
 *  Hiệu ứng cấp Composition: overlay PNG 3 social phủ toàn bộ stream.
 *
 * Transformer ghi mẫu đã mã hoá thẳng vào muxer -> file đầu ra (không có file đệm trung gian).
 */
@UnstableApi
object ExportEngine {

    fun buildComposition(ctx: Context, job: ExportJob): Composition {
        val res = job.resources

        // Bitmap tĩnh dùng chung – decode đúng 1 lần
        val social = BitmapLoader.load(ctx, res.socialOverlay, "overlays/social_overlay.png")
        val frameLunch = BitmapLoader.load(ctx, res.frameLunch, "overlays/frame_lunch.png")
        val frameDinner = if (res.frameDinner != null) BitmapLoader.load(ctx, res.frameDinner, "overlays/frame_lunch.png") else frameLunch

        val common: List<Effect> = listOf(
            Presentation.createForWidthAndHeight(OUT_W, OUT_H, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
        )

        val videoItems = ArrayList<EditedMediaItem>(job.timeline.segs.size)
        for (seg in job.timeline.segs) {
            when (seg) {
                is GameSeg -> videoItems += item(seg.src, seg.startMs, seg.startMs + seg.durationMs, common)
                is AssetSeg -> videoItems += item(Clips.uri(seg.file), 0L, seg.durationMs, common)
                is FreezeSeg -> {
                    // Hình: khung cuối phủ kín. Tiếng: FREEZE_LOOP_MS cuối của game lặp lại nhiều lần (âm thanh treo).
                    val frozen = FrozenFrame(ctx, seg.src, (seg.posMs - 40L).coerceAtLeast(0L))
                    val loop = Clips.FREEZE_LOOP_MS
                    var left = seg.durationMs
                    while (left > 0L) {
                        val t = minOf(left, loop)
                        val fx = common + OverlayEffect(ImmutableList.of<androidx.media3.effect.TextureOverlay>(
                            LazyOverlay(SegClock(0L), { frozen.sharp }, { 1f })
                        ))
                        videoItems += item(seg.src, seg.posMs - loop, seg.posMs - loop + t, fx)
                        left -= t
                    }
                }
                is CrashSeg -> {
                    // khung game đóng băng giữ nét 0,2 s rồi tối dần tới 0,45 s (nền clip lúc này đen), sau đó hiện màn lỗi PS5
                    val frozen = FrozenFrame(ctx, seg.frozenSrc, seg.frozenPosMs)
                    val clock = SegClock(seg.offsetMs * 1000L)
                    val alpha = { t: Long -> 1f - ((t / 1_000_000f - 0.2f) / 0.25f).coerceIn(0f, 1f) }
                    videoItems += item(
                        Clips.uri(Clips.CRASH), 0L, seg.durationMs,
                        common + OverlayEffect(ImmutableList.of<androidx.media3.effect.TextureOverlay>(
                            LazyOverlay(clock, { frozen.sharp }, alpha, activeUntilUs = 500_000L)
                        )),
                    )
                }
                is RestSeg -> videoItems += item(
                    Clips.uri(Clips.REST_A), 0L, seg.durationMs,
                    common + OverlayEffect(RestOverlays.build(ctx, seg)),
                )
                is ReactionSeg -> {
                    val frame = if (seg.dinner) frameDinner else frameLunch
                    val gpu = job.settings.gpuContour && !job.settings.safeMode
                    val fx = ArrayList<Effect>()
                    // video reaction hay là 60 fps: ép về 30 fps ngay đầu chuỗi để contour/khung/encoder chỉ xử lý một nửa số khung
                    fx += FrameDropEffect.createDefaultFrameDropEffect(30f)
                    fx += Presentation.createForWidthAndHeight(OUT_W, OUT_H, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
                    fx += ReactionWindow.matrix()
                    // contour GPU: shader tính trực tiếp trên khung đã thu vào cửa sổ (dưới khung PNG)
                    if (gpu) fx += ContourEffect.forReactionWindow()
                    // thứ tự overlay: contour CPU (dưới, chỉ khi không dùng GPU) -> khung (trên). Mỗi item có overlay riêng để thread-safe.
                    fx += OverlayEffect(
                        if (job.settings.safeMode || gpu) ImmutableList.of<androidx.media3.effect.TextureOverlay>(
                            BitmapOverlay.createStaticBitmapOverlay(frame),
                        ) else ImmutableList.of<androidx.media3.effect.TextureOverlay>(
                            ContourOverlay.forReactionWindow(),
                            BitmapOverlay.createStaticBitmapOverlay(frame),
                        )
                    )
                    videoItems += item(seg.src, 0L, seg.durationMs, fx)
                }
            }
        }

        val primary = EditedMediaItemSequence.Builder().apply { videoItems.forEach { addItem(it) } }.build()
        val sequences = ArrayList<EditedMediaItemSequence>()
        sequences += primary
        musicSequence(job.timeline.segs, res.music)?.let { sequences += it }

        return Composition.Builder(sequences)
            .setEffects(
                Effects(
                    emptyList<AudioProcessor>(),
                    if (job.settings.safeMode) emptyList<Effect>()
                    else listOf<Effect>(OverlayEffect(ImmutableList.of<androidx.media3.effect.TextureOverlay>(
                        BitmapOverlay.createStaticBitmapOverlay(social)
                    ))),
                )
            )
            .build()
    }

    fun buildTransformer(ctx: Context, s: ExportSettings, listener: Transformer.Listener): Transformer {
        val encoder = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate((s.videoMbps * 1_000_000).toInt())
                    .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    .build()
            )
            .setEnableFallback(true) // máy không có encoder HEVC -> tự rơi về H.264
            .build()
        val b = Transformer.Builder(ctx)
            .setVideoMimeType(s.codec.mime)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)
            .setEncoderFactory(encoder)
            .addListener(listener)
        return b.build()
    }

    // ---------- helpers ----------

    private fun item(uri: Uri, startMs: Long, endMs: Long, video: List<Effect>, removeVideo: Boolean = false): EditedMediaItem {
        val mi = MediaItem.Builder().setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            ).build()
        return EditedMediaItem.Builder(mi)
            .setRemoveVideo(removeVideo)
            .setEffects(Effects(emptyList<AudioProcessor>(), video))
            .build()
    }

    /** Nhạc lặp lại trong từng cụm Come back soon liên tiếp; ngoài cụm là clip im lặng 60s cắt đúng độ dài. */
    private fun musicSequence(segs: List<Seg>, music: MediaRef?): EditedMediaItemSequence? {
        if (music == null || music.durationMs < 1000) return null
        val runs = ArrayList<Pair<Boolean, Long>>() // (isMusicRun, durationMs)
        for (s in segs) {
            val m = s is AssetSeg && s.music
            if (runs.isNotEmpty() && runs.last().first == m) runs[runs.size - 1] = m to (runs.last().second + s.durationMs)
            else runs += m to s.durationMs
        }
        if (runs.none { it.first }) return null
        // bỏ đoạn im lặng cuối (nếu có) – sequence ngắn hơn primary là được
        while (runs.isNotEmpty() && !runs.last().first) runs.removeAt(runs.size - 1)

        val b = EditedMediaItemSequence.Builder()
        val silence = Clips.uri(Clips.SILENCE)
        for ((isMusic, total) in runs) {
            var left = total
            val src = if (isMusic) music.uri else silence
            val srcLen = if (isMusic) music.durationMs else Clips.SILENCE_MS
            while (left > 0) {
                val t = minOf(left, srcLen)
                b.addItem(item(src, 0L, t, emptyList(), removeVideo = true))
                left -= t
            }
        }
        return b.build()
    }
}
