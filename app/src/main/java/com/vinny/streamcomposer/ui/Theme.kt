package com.vinny.streamcomposer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Material 3 thuần: chỉ dùng thành phần có sẵn của thư viện (Card, Button, Chip, NavigationBar…),
 * không còn vẽ viền chấm / gradient / font tuỳ biến → ít recomposition và nhẹ hơn.
 * Màu chủ đạo vẫn là cam lửa của kênh Vinny Gamer Official; tự theo chế độ sáng/tối của hệ thống.
 */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFFB86B), onPrimary = Color(0xFF4A2800),
    primaryContainer = Color(0xFF6B3C00), onPrimaryContainer = Color(0xFFFFDCBC),
    secondary = Color(0xFFE3C1A0), onSecondary = Color(0xFF412C14),
    secondaryContainer = Color(0xFF5A422A), onSecondaryContainer = Color(0xFFFFDDBF),
    tertiary = Color(0xFFB7D68A), onTertiary = Color(0xFF223600),
    tertiaryContainer = Color(0xFF384D12), onTertiaryContainer = Color(0xFFD2F2A4),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF17130E), onBackground = Color(0xFFECE1D8),
    surface = Color(0xFF17130E), onSurface = Color(0xFFECE1D8),
    surfaceVariant = Color(0xFF4F4538), onSurfaceVariant = Color(0xFFD3C4B4),
    outline = Color(0xFF9C8E80), outlineVariant = Color(0xFF4F4538),
    inverseSurface = Color(0xFFECE1D8), inverseOnSurface = Color(0xFF362F27), inversePrimary = Color(0xFF8B5100),
    surfaceContainerLowest = Color(0xFF110E09), surfaceContainerLow = Color(0xFF1F1B15),
    surfaceContainer = Color(0xFF231F19), surfaceContainerHigh = Color(0xFF2E2923),
    surfaceContainerHighest = Color(0xFF39342D),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF8B5100), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCBC), onPrimaryContainer = Color(0xFF2D1600),
    secondary = Color(0xFF745B41), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDDBF), onSecondaryContainer = Color(0xFF2A1700),
    tertiary = Color(0xFF4C6624), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCDEE9E), onTertiaryContainer = Color(0xFF0E2000),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F4), onBackground = Color(0xFF201B14),
    surface = Color(0xFFFFF8F4), onSurface = Color(0xFF201B14),
    surfaceVariant = Color(0xFFF2DFCF), onSurfaceVariant = Color(0xFF50453A),
    outline = Color(0xFF827568), outlineVariant = Color(0xFFD5C4B5),
    inverseSurface = Color(0xFF352F28), inverseOnSurface = Color(0xFFFAEFE5), inversePrimary = Color(0xFFFFB86B),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFBF2E9),
    surfaceContainer = Color(0xFFF6ECE3), surfaceContainerHigh = Color(0xFFF0E6DD),
    surfaceContainerHighest = Color(0xFFEAE1D8),
)

@Composable
fun StreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}

/** Một nhóm nội dung: Card M3 + tiêu đề. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Dòng chữ phụ / ghi chú nhỏ. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text, modifier = modifier,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
fun Dot(color: Color) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
}
