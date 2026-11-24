package com.vinny.streamcomposer.ui

import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Bảng màu lấy từ nhận diện kênh Vinny Gamer Official: nâu đen + cam lửa + viền chấm pixel. */
object Pal {
    val bg = Color(0xFF0A0503)
    val panel = Color(0xFF1A0D06)
    val panel2 = Color(0xFF26140A)
    val orange = Color(0xFFFF9A1F)
    val amber = Color(0xFFFFC857)
    val ember = Color(0xFFE2501A)
    val text = Color(0xFFFFF1DC)
    val muted = Color(0xFFB98F6A)
    val ok = Color(0xFF4ADE80)
    val bad = Color(0xFFFF5A4D)
}

val Condensed = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD))

@Composable
fun StreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pal.orange, onPrimary = Pal.bg, secondary = Pal.amber, background = Pal.bg,
            surface = Pal.panel, onSurface = Pal.text, onBackground = Pal.text, error = Pal.bad,
        ),
        typography = Typography(
            titleLarge = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Black, fontSize = 28.sp, letterSpacing = 0.5.sp),
            titleMedium = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 20.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, color = Pal.text),
            labelLarge = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Black, fontSize = 16.sp, letterSpacing = 1.sp),
        ),
        shapes = Shapes(small = RoundedCornerShape(2.dp), medium = RoundedCornerShape(2.dp), large = RoundedCornerShape(4.dp)),
        content = content,
    )
}

/** Viền chấm cam kiểu khung stream. */
fun Modifier.dottedBorder(color: Color = Pal.orange, width: Float = 3f): Modifier = drawBehind {
    drawRect(
        color = color, topLeft = Offset(width / 2, width / 2),
        size = Size(size.width - width, size.height - width),
        style = Stroke(width = width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 6f))),
    )
}

@Composable
fun PixelPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Pal.panel2, Pal.panel)))
            .dottedBorder()
            .padding(14.dp),
        content = content,
    )
}

@Composable
fun Title(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier = modifier,
        style = MaterialTheme.typography.titleLarge.copy(
            brush = Brush.verticalGradient(listOf(Pal.amber, Pal.orange, Pal.ember)),
            shadow = Shadow(Pal.ember.copy(alpha = 0.7f), Offset(3f, 3f), 0f),
        ),
    )
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Pal.muted) =
    Text(text, modifier = modifier, color = color, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp))

@Composable
fun ForgeButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = true, onClick: () -> Unit) {
    val fill = if (primary && enabled) Brush.verticalGradient(listOf(Pal.amber, Pal.orange, Pal.ember))
    else Brush.verticalGradient(listOf(Pal.panel2, Pal.panel2))
    Box(
        modifier
            .background(fill, RoundedCornerShape(2.dp))
            .border(BorderStroke(2.dp, if (enabled) Pal.amber else Pal.muted.copy(alpha = 0.4f)), RoundedCornerShape(2.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(PaddingValues(horizontal = 16.dp, vertical = 11.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            color = if (primary && enabled) Pal.bg else if (enabled) Pal.amber else Pal.muted,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
fun Chip(text: String, color: Color = Pal.orange, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Text(
        text.uppercase(),
        color = color,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
        modifier = modifier
            .border(1.5.dp, color, RoundedCornerShape(2.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Thanh tiến trình chia ô như thanh máu Minecraft. */
@Composable
fun PixelProgress(progress: Float, modifier: Modifier = Modifier, segments: Int = 40) {
    Row(modifier.fillMaxWidth().height(18.dp).dottedBorder(Pal.muted, 2f).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        val lit = (progress.coerceIn(0f, 1f) * segments).toInt()
        for (i in 0 until segments) {
            Box(
                Modifier.weight(1f).height(12.dp).background(
                    if (i < lit) Brush.verticalGradient(listOf(Pal.amber, Pal.ember)) else Brush.verticalGradient(listOf(Pal.panel2, Pal.panel2))
                )
            )
        }
    }
}

@Composable
fun Dot(color: Color) = Box(Modifier.size(8.dp).background(color))
