package dev.singscale.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.singscale.app.core.QualityGrade
import dev.singscale.app.ui.theme.CyanBright
import dev.singscale.app.ui.theme.ObsidianBorder
import dev.singscale.app.ui.theme.TextPrimary
import dev.singscale.app.ui.theme.accentColor

@Composable
fun SparklineChart(
    samples: List<Float>,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val pts = when {
            samples.size >= 2 -> samples
            samples.size == 1 -> listOf(samples[0], samples[0])
            else -> listOf(28f, 26f, 30f, 25f, 27f)
        }
        val valid = pts.map { if (it <= 0f || it > 800f) 400f else it }
        val minVal = (valid.minOrNull() ?: 10f) * 0.85f
        val maxVal = ((valid.maxOrNull() ?: 100f) * 1.15f).coerceAtLeast(minVal + 12f)

        val dash = PathEffect.dashPathEffect(floatArrayOf(5f, 7f), 0f)
        drawLine(
            color = ObsidianBorder.copy(alpha = 0.45f),
            start = Offset(0f, size.height * 0.5f),
            end = Offset(size.width, size.height * 0.5f),
            strokeWidth = 1f,
            pathEffect = dash
        )

        val stepX = (size.width - 5.dp.toPx()) / (valid.size - 1).coerceAtLeast(1)
        val coords = valid.mapIndexed { idx, v ->
            val norm = ((v - minVal) / (maxVal - minVal)).coerceIn(0f, 1f)
            val y = size.height - (norm * (size.height * 0.76f) + size.height * 0.12f)
            Offset(idx * stepX, y)
        }

        val strokePath = Path().apply {
            moveTo(coords.first().x, coords.first().y)
            for (i in 1 until coords.size) {
                val prev = coords[i - 1]
                val cur = coords[i]
                val midX = (prev.x + cur.x) / 2f
                cubicTo(midX, prev.y, midX, cur.y, cur.x, cur.y)
            }
        }

        val fillPath = Path().apply {
            addPath(strokePath)
            lineTo(coords.last().x, size.height)
            lineTo(coords.first().x, size.height)
            close()
        }

        val alphaFactor = if (samples.isEmpty()) 0.08f else 0.24f
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = alphaFactor), Color.Transparent)
            )
        )
        drawPath(
            path = strokePath,
            color = if (samples.isEmpty()) lineColor.copy(alpha = 0.32f) else lineColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
        if (samples.isNotEmpty()) {
            val tip = coords.last()
            drawCircle(
                color = lineColor.copy(alpha = 0.28f),
                radius = 5.5.dp.toPx(),
                center = tip
            )
            drawCircle(
                color = lineColor,
                radius = 2.8.dp.toPx(),
                center = tip
            )
        }
    }
}

@Composable
fun QualityScoreRing(
    score: Int,
    grade: QualityGrade,
    ringSize: Dp = 56.dp
) {
    val accent = grade.accentColor()
    val sweep = (score.coerceIn(0, 100) / 100f) * 300f

    Box(
        modifier = Modifier.size(ringSize),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = 4.dp.toPx()
            val pad = strokePx / 2f + 2f
            val arcSize = Size(size.width - pad * 2, size.height - pad * 2)
            val topLeft = Offset(pad, pad)

            drawArc(
                color = ObsidianBorder.copy(alpha = 0.75f),
                startAngle = 120f,
                sweepAngle = 300f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
            if (score > 0) {
                drawArc(
                    brush = Brush.linearGradient(
                        colors = listOf(CyanBright, accent)
                    ),
                    startAngle = 120f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (score > 0) score.toString() else "—",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontFeatureSettings = "tnum"
                ),
                color = TextPrimary
            )
            Text(
                text = grade.code,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = accent
            )
        }
    }
}
