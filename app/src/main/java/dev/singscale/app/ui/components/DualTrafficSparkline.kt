package dev.singscale.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.singscale.app.core.TrafficFormat
import dev.singscale.app.ui.theme.AmberWarn
import dev.singscale.app.ui.theme.CyanBright
import dev.singscale.app.ui.theme.EmeraldGood
import dev.singscale.app.ui.theme.EmeraldSoft
import dev.singscale.app.ui.theme.ObsidianBorder
import dev.singscale.app.ui.theme.ObsidianCard
import dev.singscale.app.ui.theme.ObsidianElevated
import dev.singscale.app.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.max

@Composable
fun SplitRatioProgressBar(
    relayBytes: Long,
    directBytes: Long,
    relayBps: Long,
    directBps: Long,
    modifier: Modifier = Modifier
) {
    val totalBytes = relayBytes + directBytes
    val totalBps = relayBps + directBps
    val rawRelayRatio = when {
        totalBytes > 0L -> (relayBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        totalBps > 0L -> (relayBps.toDouble() / totalBps.toDouble()).toFloat().coerceIn(0f, 1f)
        else -> 0.5f
    }
    val animatedRelayFraction by animateFloatAsState(
        targetValue = rawRelayRatio,
        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
        label = "relayFraction"
    )

    val relayPct = if (totalBytes > 0L) (relayBytes.toDouble() * 100.0 / totalBytes.toDouble()) else 0.0
    val directPct = if (totalBytes > 0L) (100.0 - relayPct).coerceAtLeast(0.0) else 0.0

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(EmeraldGood)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = String.format(Locale.US, "中继/出口 %.1f%%", relayPct),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = EmeraldSoft
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = String.format(Locale.US, "本地直连 %.1f%%", directPct),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = AmberWarn
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(AmberWarn)
                )
            }
        }

        Spacer(modifier = Modifier.height(7.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(ObsidianElevated)
        ) {
            when {
                totalBytes == 0L && totalBps == 0L -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ObsidianBorder.copy(alpha = 0.65f))
                    )
                }
                animatedRelayFraction <= 0.005f -> {
                    // Pure 100% Direct — clean unbroken luminous gold-amber bar
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(AmberWarn.copy(alpha = 0.78f), AmberWarn)
                                )
                            )
                    )
                }
                animatedRelayFraction >= 0.995f -> {
                    // Pure 100% Relay/Exit — clean unbroken luminous cyan-emerald bar
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(CyanBright, EmeraldGood)
                                )
                            )
                    )
                }
                else -> {
                    val leftWeight = animatedRelayFraction.coerceIn(0.03f, 0.97f)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(leftWeight)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(CyanBright, EmeraldGood)
                                )
                            )
                    )
                    Spacer(modifier = Modifier.width(2.5.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight((1f - leftWeight).coerceAtLeast(0.03f))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(AmberWarn.copy(alpha = 0.80f), AmberWarn)
                                )
                            )
                    )
                }
            }
        }
    }
}

@Composable
fun DualTrafficSparkline(
    relaySamples: List<Float>,
    directSamples: List<Float>,
    modifier: Modifier = Modifier,
    relayColor: Color = EmeraldGood,
    directColor: Color = AmberWarn
) {
    val peakBps = max(
        relaySamples.maxOrNull() ?: 0f,
        directSamples.maxOrNull() ?: 0f
    )
    val safeCeil = max(peakBps * 1.18f, 4096f)

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f), 0f)
            val gridColor = ObsidianBorder.copy(alpha = 0.45f)
            drawLine(gridColor, Offset(0f, h * 0.25f), Offset(w, h * 0.25f), strokeWidth = 1f, pathEffect = dash)
            drawLine(gridColor, Offset(0f, h * 0.62f), Offset(w, h * 0.62f), strokeWidth = 1f, pathEffect = dash)
            drawLine(ObsidianBorder.copy(alpha = 0.7f), Offset(0f, h - 1f), Offset(w, h - 1f), strokeWidth = 1f)

            fun drawSeries(samples: List<Float>, strokeColor: Color, topFillAlpha: Float) {
                if (samples.size < 2) return
                val hasSignal = samples.any { it > 1f }
                val stepX = (w - 6.dp.toPx()) / (samples.size - 1).toFloat()
                val points = samples.mapIndexed { idx, v ->
                    val normalized = (v / safeCeil).coerceIn(0f, 1f)
                    Offset(
                        x = idx * stepX,
                        y = h - (normalized * (h - 10.dp.toPx())) - 3.dp.toPx()
                    )
                }

                val linePath = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    for (i in 1 until points.size) {
                        val prev = points[i - 1]
                        val curr = points[i]
                        val midX = (prev.x + curr.x) * 0.5f
                        cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
                    }
                }

                if (hasSignal) {
                    val fillPath = Path().apply {
                        addPath(linePath)
                        lineTo(points.last().x, h)
                        lineTo(points.first().x, h)
                        close()
                    }
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(strokeColor.copy(alpha = topFillAlpha), Color.Transparent),
                            startY = 0f,
                            endY = h
                        )
                    )
                }

                drawPath(
                    path = linePath,
                    color = strokeColor.copy(alpha = if (hasSignal) 0.95f else 0.35f),
                    style = Stroke(width = if (hasSignal) 2.2.dp.toPx() else 1.4.dp.toPx(), cap = StrokeCap.Round)
                )

                if (hasSignal) {
                    val tip = points.last()
                    drawCircle(
                        color = strokeColor.copy(alpha = 0.28f),
                        radius = 6.dp.toPx(),
                        center = tip
                    )
                    drawCircle(
                        color = strokeColor,
                        radius = 2.8.dp.toPx(),
                        center = tip
                    )
                }
            }

            drawSeries(directSamples, directColor, topFillAlpha = 0.22f)
            drawSeries(relaySamples, relayColor, topFillAlpha = 0.28f)
        }

        if (peakBps > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ObsidianCard.copy(alpha = 0.88f))
                    .border(0.75.dp, ObsidianBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "30s 峰值 ${TrafficFormat.formatBps(peakBps.toLong())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}
