package com.vinny.streamcomposer.render

import android.content.Context
import android.graphics.RectF
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Đường đồng mức (contour) tính hoàn toàn trên GPU bằng fragment shader.
 *
 * Cùng công thức với [ContourRenderer] (CPU): trường độ cao h(x,y,t) = tổng 4 sóng sin, tần số thời gian là bội
 * nguyên của 2π/loopSec nên loop khít sau [loopSec] giây. Khác biệt: thay vì marching squares + vẽ bitmap + upload
 * texture mỗi khung, mỗi pixel trong cửa sổ reaction tự tính khoảng cách tới đường đồng mức gần nhất (xấp xỉ bậc
 * một bằng gradient) rồi pha trắng lên khung video → không còn việc nào ở CPU, chạy đủ 30 fps.
 *
 * Đặt SAU ReactionWindow.matrix() và TRƯỚC OverlayEffect chứa khung PNG.
 */
@UnstableApi
class ContourEffect(
    private val region: RectF,
    private val loopSec: Float = 20f,
    private val step: Float = 0.17f,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ContourShaderProgram(useHdr, region, loopSec, step)

    companion object {
        /** Cửa sổ reaction trên canvas 1920x1080 (toạ độ pixel, gốc ở góc trên trái). */
        fun forReactionWindow() = ContourEffect(
            RectF(ReactionWindow.LEFT, ReactionWindow.TOP, ReactionWindow.RIGHT, ReactionWindow.BOTTOM)
        )
    }
}

@UnstableApi
private class ContourShaderProgram(
    useHdr: Boolean,
    private val region: RectF,
    private val loopSec: Float,
    private val step: Float,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity = */ 1) {

    private val program: GlProgram = try {
        GlProgram(VERTEX, fragment(step))
    } catch (e: Exception) {
        throw VideoFrameProcessingException(e)
    }
    private var width = OUT_W.toFloat()
    private var height = OUT_H.toFloat()

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth.toFloat()
        height = inputHeight.toFloat()
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val phase = ((presentationTimeUs / 1_000_000.0) % loopSec) / loopSec
            val tt = (2.0 * Math.PI * phase).toFloat()
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            program.setFloatUniform("uTime", tt)
            program.setFloatsUniform("uSize", floatArrayOf(width, height))
            // vùng contour tính theo tỉ lệ khung thật (canvas xuất luôn 1920x1080 nhưng giữ đúng nếu khác)
            val sx = width / OUT_W
            val sy = height / OUT_H
            program.setFloatsUniform(
                "uRegion", floatArrayOf(region.left * sx, region.top * sy, region.right * sx, region.bottom * sy)
            )
            program.setBufferAttribute(
                "aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
            GlUtil.checkGlError()
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexCoord;
void main() {
  gl_Position = aFramePosition;
  vTexCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

        fun fragment(step: Float): String = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform float uTime;
uniform vec2 uSize;
uniform vec4 uRegion;
varying vec2 vTexCoord;

const float STEP = ${"%.5f".format(java.util.Locale.US, step)};

float field(vec2 p) {
  float tt = uTime;
  float wx = p.x + 0.45 * sin(1.3 * p.y + tt);
  float wy = p.y + 0.45 * cos(1.1 * p.x - tt);
  return sin(1.9 * wx + 0.7 * wy + tt)
       + 0.8 * sin(1.1 * wx - 1.7 * wy - 2.0 * tt + 1.3)
       + 0.6 * sin(2.9 * wx + 2.3 * wy + tt + 4.1)
       + 0.4 * sin(4.3 * wy - 3.1 * wx - tt + 2.2);
}

void main() {
  vec4 c = texture2D(uTexSampler, vTexCoord);
  // pixel, gốc ở góc TRÊN trái (v = 1 là đỉnh ảnh)
  vec2 px = vec2(vTexCoord.x * uSize.x, (1.0 - vTexCoord.y) * uSize.y);
  if (px.x < uRegion.x || px.x > uRegion.z || px.y < uRegion.y || px.y > uRegion.w) {
    gl_FragColor = c;
    return;
  }
  vec2 rs = uRegion.zw - uRegion.xy;
  float aspect = rs.x / rs.y;
  vec2 scale = vec2(1.8 * aspect, 1.8);
  vec2 n = (px - uRegion.xy) / rs * scale;
  vec2 perPx = scale / rs;                      // 1 pixel = bao nhiêu đơn vị chuẩn hoá

  float h = field(n);
  float gx = field(n + vec2(perPx.x, 0.0)) - h;
  float gy = field(n + vec2(0.0, perPx.y)) - h;
  float g = max(length(vec2(gx, gy)), 0.0001);  // độ dốc trên mỗi pixel

  float k = floor(h / STEP + 0.5);              // đường đồng mức gần nhất
  float d = abs(h - k * STEP) / g;              // khoảng cách tới nó (pixel)
  bool thick = mod(k, 5.0) < 0.5;               // mỗi 5 đường vẽ đậm (index contour)
  float half_w = thick ? 1.7 : 0.9;             // nửa bề rộng nét (pixel 1080p)
  float cov = clamp(half_w + 0.5 - d, 0.0, 1.0);
  float a = cov * (thick ? 0.843 : 0.647);

  gl_FragColor = vec4(mix(c.rgb, vec3(1.0), a), max(c.a, a));
}
"""
    }
}
