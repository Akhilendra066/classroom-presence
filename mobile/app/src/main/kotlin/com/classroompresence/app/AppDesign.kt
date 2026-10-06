package com.classroompresence.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun PresenceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF4658C7), onPrimary = Color.White,
            primaryContainer = Color(0xFFE9EDFF), onPrimaryContainer = Color(0xFF243279),
            secondary = Color(0xFF46665D), secondaryContainer = Color(0xFFE5F3EC),
            background = Color(0xFFF6F7FB), surface = Color.White,
            surfaceVariant = Color(0xFFEEF0F7), onSurface = Color(0xFF202638),
            onSurfaceVariant = Color(0xFF5C6375), outline = Color(0xFF757D90),
            outlineVariant = Color(0xFFE2E5EE), error = Color(0xFFB3261E)
        ),
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp)),
        content = content
    )
}

@Composable
internal fun NavigationGlyph(kind: String) {
    val ink = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val unit = size.width / 24f
        val stroke = Stroke(1.8f * unit)
        fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(ink, Offset(x * unit, y * unit), Offset(xx * unit, yy * unit), strokeWidth = 1.8f * unit)
        when (kind) {
            "home" -> { line(3f, 11f, 12f, 3f); line(12f, 3f, 21f, 11f); line(6f, 9f, 6f, 21f); line(18f, 9f, 18f, 21f); line(6f, 21f, 18f, 21f); line(10f, 21f, 10f, 15f); line(10f, 15f, 14f, 15f); line(14f, 15f, 14f, 21f) }
            "classes" -> { drawRoundRect(ink, Offset(3f * unit, 4f * unit), Size(18f * unit, 16f * unit), CornerRadius(2f * unit), style = stroke); line(12f, 4f, 12f, 20f); line(6f, 8f, 9f, 8f); line(15f, 8f, 18f, 8f) }
            "history" -> { drawCircle(ink, 9f * unit, style = stroke); line(12f, 7f, 12f, 12f); line(12f, 12f, 16f, 14f) }
            else -> { for (x in listOf(5f, 12f, 19f)) drawCircle(ink, 1.8f * unit, Offset(x * unit, 12f * unit)) }
        }
    }
}

@Composable
internal fun LabelBadge(text: String, positive: Boolean = false) {
    Surface(color = if (positive) Color(0xFFE5F3EC) else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (positive) Color(0xFF23664B) else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun Metric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
