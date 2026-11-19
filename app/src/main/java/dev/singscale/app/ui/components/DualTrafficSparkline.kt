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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.singscale.app.core.TrafficFormat
import dev.singscale.app.core.TrafficSplitTracker
import dev.singscale.app.ui.theme.AmberWarn
import dev.singscale.app.ui.theme.CyanBright
import dev.singscale.app.ui.theme.EmeraldGood
import dev.singscale.app.ui.theme.EmeraldSoft
import dev.singscale.app.ui.theme.IndigoAccent
import dev.singscale.app.ui.theme.ObsidianBorder
import dev.singscale.app.ui.theme.ObsidianCard
import dev.singscale.app.ui.theme.ObsidianElevated
import dev.singscale.app.ui.theme.TextSecondary
import kotlin.math.max

/**
 * Three-way mutually-exclusive split ratio bar:
 * - 中继 / 出口 (Emerald)
 * - 组网直连 (Indigo)
 * - 本地直连 (Amber)
 * Displayed percentages use largest-remainder tenths so non-zero totals always conserve 100.0%.
 */
@Composable
fun SplitRatioProgressBar(
    relayBytes: Long,
    meshBytes: Long,
    directBytes: Long,
    relayBps: Long,
    meshBps: Long,
    directBps: Long,
    modifier: Modifier = Modifier
) {
    val safeRelayBytes = relayBytes.coerceAtLeast(0L)
    val safeMeshBytes = meshBytes.coerceAtLeast(0L)
    val safeDirectBytes = directBytes.coerceAtLeast(0L)
    val totalBytes = safeRelayBytes + safeMeshBytes + safeDirectBytes

    val safeRelayBps = relayBps.coerceAtLeast(0L)
    val safeMeshBps = meshBps.coerceAtLeast(0L)
    val safeDirectBps = directBps.coerceAtLeast(0L)
    val totalBps = safeRelayBps + safeMeshBps + safeDirectBps

    val hasAnyTraffic = totalBytes > 0L || totalBps > 0L

    val rawRelayRatio = when {
        totalBytes > 0L -> (safeRelayBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        totalBps > 0L -> (safeRelayBps.toDouble() / totalBps.toDouble()).toFloat().coerceIn(0f, 1f)
        else -> 0f
    }
    val rawMeshRatio = when {
        totalBytes > 0L -> (safeMeshBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        totalBps > 0L -> (safeMeshBps.toDouble() / totalBps.toDouble()).toFloat().coerceIn(0f, 1f)
        else -> 0f
    }
    val rawDirectRatio = when {
        totalBytes > 0L -> (safeDirectBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        totalBps > 0L -> (safeDirectBps.toDouble() / totalBps.toDouble()).toFloat().coerceIn(0f, 1f)
        else -> 0f
    }

    val animRelay by animateFloatAsState(
        targetValue = rawRelayRatio,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "relayRatio"
    )
    val animMesh by animateFloatAsState(
        targetValue = rawMeshRatio,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "meshRatio"
    )
    val animDirect by animateFloatAsState(
        targetValue = rawDirectRatio,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "directRatio"
    )

    val (relayPctStr, meshPctStr, directPctStr) = if (totalBytes > 0L) {
        TrafficSplitTracker.computeDisplayPercents(safeRelayBytes, safeMeshBytes, safeDirectBytes)
    } else if (totalBps > 0L) {
        TrafficSplitTracker.computeDisplayPercents(safeRelayBps, safeMeshBps, safeDirectBps)
    } else {
        Triple("0.0%", "0.0%", "0.0%")
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RatioLegendItem(
                label = "中继",
                percentText = relayPctStr,
                dotColor = EmeraldGood,
                textColor = EmeraldSoft
            )
            RatioLegendItem(
                label = "组网直连",
                percentText = meshPctStr,
                dotColor = IndigoAccent,
                textColor = IndigoAccent
            )
            RatioLegendItem(
                label = "本地直连",
                percentText = directPctStr,
                dotColor = AmberWarn,
                textColor = AmberWarn
            )
        }

        Spacer(modifier = Modifier.height(7.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(ObsidianElevated)
        ) {
            if (!hasAnyTraffic) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ObsidianBorder.copy(alpha = 0.65f))
                )
            } else {
                val activeSegments = buildList {
                    if (rawRelayRatio > 0f || animRelay > 0.004f) {
                        add(
                            Triple(
                                animRelay.coerceAtLeast(if (rawRelayRatio > 0f) 0.04f else 0.004f),
                                listOf(CyanBright, EmeraldGood),
                                "relay"
                            )
                        )
                    }
                    if (rawMeshRatio > 0f || animMesh > 0.004f) {
                        add(
                            Triple(
                                animMesh.coerceAtLeast(if (rawMeshRatio > 0f) 0.04f else 0.004f),
                                listOf(IndigoAccent.copy(alpha = 0.80f), IndigoAccent),
                                "mesh"
                            )
                        )
                    }
                    if (rawDirectRatio > 0f || animDirect > 0.004f) {
                        add(
                            Triple(
                                animDirect.coerceAtLeast(if (rawDirectRatio > 0f) 0.04f else 0.004f),
                                listOf(AmberWarn.copy(alpha = 0.80f), AmberWarn),
                                "direct"
                            )
                        )
                    }
                }

                if (activeSegments.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ObsidianBorder.copy(alpha = 0.65f))
                    )
                } else {
                    activeSegments.forEachIndexed { idx, (weight, colors, _) ->
                        if (idx > 0) {
                            Spacer(modifier = Modifier.width(2.dp))
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(weight.coerceAtLeast(0.01f))
                                .background(Brush.horizontalGradient(colors = colors))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RatioLegendItem(
    label: String,
    percentText: String,
    dotColor: Color,
    textColor: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.5.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "$label $percentText",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum"
            ),
            color = textColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Real-time 30-point throughput waveform plotting all 3 independent traffic buckets:
 * 中继 / 出口 (Emerald), 组网直连 (Indigo), and 本地直连 (Amber).
 */
@Composable
fun DualTrafficSparkline(
    relaySamples: List<Float>,
    directSamples: List<Float>,
    modifier: Modifier = Modifier,
    meshSamples: List<Float> = emptyList(),
    relayColor: Color = EmeraldGood,
    meshColor: Color = IndigoAccent,
    directColor: Color = AmberWarn
) {
    val peakBps = max(
        max(relaySamples.maxOrNull() ?: 0f, meshSamples.maxOrNull() ?: 0f),
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
                    color = strokeColor.copy(alpha = if (hasSignal) 0.95f else 0.30f),
                    style = Stroke(width = if (hasSignal) 2.1.dp.toPx() else 1.2.dp.toPx(), cap = StrokeCap.Round)
                )

                if (hasSignal) {
                    val tip = points.last()
                    drawCircle(
                        color = strokeColor.copy(alpha = 0.28f),
                        radius = 5.5.dp.toPx(),
                        center = tip
                    )
                    drawCircle(
                        color = strokeColor,
                        radius = 2.6.dp.toPx(),
                        center = tip
                    )
                }
            }

            drawSeries(directSamples, directColor, topFillAlpha = 0.20f)
            if (meshSamples.size >= 2) {
                drawSeries(meshSamples, meshColor, topFillAlpha = 0.24f)
            }
            drawSeries(relaySamples, relayColor, topFillAlpha = 0.26f)
        }

        // Compact 3-way legend pill top-left
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .clip(RoundedCornerShape(6.dp))
                .background(ObsidianCard.copy(alpha = 0.84f))
                .border(0.75.dp, ObsidianBorder, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SparkLegendDot("中继", relayColor)
            SparkLegendDot("组网", meshColor)
            SparkLegendDot("直连", directColor)
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
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontFeatureSettings = "tnum"
                    ),
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun SparkLegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
            color = TextSecondary,
            maxLines = 1
        )
    }
}
