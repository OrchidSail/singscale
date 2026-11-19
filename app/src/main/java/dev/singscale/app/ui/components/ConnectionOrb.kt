package dev.singscale.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.singscale.app.core.TunnelStatus
import dev.singscale.app.ui.theme.CyanBright
import dev.singscale.app.ui.theme.CyanPrimary
import dev.singscale.app.ui.theme.GlassRimBottom
import dev.singscale.app.ui.theme.GlassRimTop
import dev.singscale.app.ui.theme.ObsidianBorder
import dev.singscale.app.ui.theme.ObsidianCard
import dev.singscale.app.ui.theme.ObsidianCardBottom
import dev.singscale.app.ui.theme.ObsidianElevated
import dev.singscale.app.ui.theme.TextMuted
import dev.singscale.app.ui.theme.TextPrimary
import dev.singscale.app.ui.theme.TextSecondary
import dev.singscale.app.ui.theme.accentColor
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ConnectionOrb(
    status: TunnelStatus,
    connectedSinceMs: Long,
    tailnetLabel: String,
    downlinkFormatted: String = "",
    uplinkFormatted: String = "",
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "orbPressScale"
    )

    val accent by animateColorAsState(
        targetValue = status.accentColor(),
        animationSpec = tween(500),
        label = "orbAccent"
    )

    val isHandshaking = status == TunnelStatus.STARTING || status == TunnelStatus.NEEDS_LOGIN
    val sweepAngle: Float
    val counterAngle: Float
    val breathe: Float
    if (isHandshaking) {
        val infinite = rememberInfiniteTransition(label = "orbInfinite")
        val animSweep by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2800, easing = LinearEasing)
            ),
            label = "sweepAngle"
        )
        val animCounter by infinite.animateFloat(
            initialValue = 360f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 7000, easing = LinearEasing)
            ),
            label = "counterAngle"
        )
        val animBreathe by infinite.animateFloat(
            initialValue = 0.92f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathe"
        )
        sweepAngle = animSweep
        counterAngle = animCounter
        breathe = animBreathe
    } else {
        // Zero continuous 120fps GPU redraws when STOPPED or steady-state RUNNING
        sweepAngle = 0f
        counterAngle = 315f
        breathe = 1f
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status, connectedSinceMs, lifecycleOwner) {
        if (status == TunnelStatus.RUNNING && connectedSinceMs > 0L) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    nowMs = System.currentTimeMillis()
                    delay(1000L)
                }
            }
        }
    }

    val uptimeStr = if (status == TunnelStatus.RUNNING && connectedSinceMs > 0L) {
        val sec = ((nowMs - connectedSinceMs).coerceAtLeast(0L)) / 1000L
        String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
    } else null

    val isActive = status == TunnelStatus.RUNNING
    val statusHeadline = when (status) {
        TunnelStatus.RUNNING -> "智能组网运行中"
        TunnelStatus.STARTING -> "正在握手建立隧道…"
        TunnelStatus.NEEDS_LOGIN -> "等待设备登录授权"
        TunnelStatus.STOPPING -> "正在安全释放隧道…"
        TunnelStatus.ERROR -> "隧道异常 · 点击重连"
        TunnelStatus.STOPPED -> "轻触启动智能组网"
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(204.dp)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggle()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxR = size.minDimension / 2f

                // 1. Deep multi-stop radial bloom
                val glowAlpha = if (status == TunnelStatus.STOPPED) 0.10f else 0.24f * breathe.coerceIn(0.85f, 1.08f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = glowAlpha),
                            accent.copy(alpha = glowAlpha * 0.35f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = maxR * breathe.coerceIn(0.92f, 1.06f)
                    ),
                    radius = maxR,
                    center = center
                )

                // 2. Chronograph 60-tick precision bezel
                val tickOuterR = maxR * 0.90f
                for (i in 0 until 60) {
                    val deg = i * 6f
                    val rad = Math.toRadians((deg - 90f).toDouble())
                    val isMajor = i % 5 == 0
                    val tickLen = if (isMajor) 7.5.dp.toPx() else 4.dp.toPx()
                    val innerR = tickOuterR - tickLen

                    // Angular distance to rotating scanner wave
                    val rawDiff = abs((deg - sweepAngle + 540f) % 360f - 180f)
                    val waveBoost = (1f - (rawDiff / 48f)).coerceIn(0f, 1f)

                    val tickColor = when {
                        status == TunnelStatus.STOPPED -> {
                            ObsidianBorder.copy(alpha = if (isMajor) 0.85f else 0.42f)
                        }
                        else -> {
                            val baseAlpha = if (isMajor) 0.55f else 0.22f
                            accent.copy(alpha = (baseAlpha + waveBoost * 0.45f).coerceAtMost(1f))
                        }
                    }

                    val p1 = Offset(
                        x = center.x + (innerR * cos(rad)).toFloat(),
                        y = center.y + (innerR * sin(rad)).toFloat()
                    )
                    val p2 = Offset(
                        x = center.x + (tickOuterR * cos(rad)).toFloat(),
                        y = center.y + (tickOuterR * sin(rad)).toFloat()
                    )
                    drawLine(
                        color = tickColor,
                        start = p1,
                        end = p2,
                        strokeWidth = if (isMajor) 2.dp.toPx() else 1.2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                // 3. Inner luminous orbital arc
                val orbitR = maxR * 0.74f
                rotate(degrees = counterAngle, pivot = center) {
                    val topLeft = Offset(center.x - orbitR, center.y - orbitR)
                    val arcSize = Size(orbitR * 2f, orbitR * 2f)
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to Color.Transparent,
                            0.35f to accent.copy(alpha = if (status == TunnelStatus.STOPPED) 0.16f else 0.58f),
                            0.5f to CyanBright.copy(alpha = if (status == TunnelStatus.STOPPED) 0.22f else 0.75f),
                            0.65f to Color.Transparent,
                            1f to Color.Transparent,
                            center = center
                        ),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            // 4. Tactile Titanium-Glass Core Button
            Box(
                modifier = Modifier
                    .size(122.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            colors = if (isActive) {
                                listOf(
                                    accent.copy(alpha = 0.24f),
                                    ObsidianCard,
                                    ObsidianCardBottom
                                )
                            } else {
                                listOf(
                                    ObsidianElevated,
                                    ObsidianCard,
                                    ObsidianCardBottom
                                )
                            }
                        )
                    )
                    .border(
                        width = 1.6.dp,
                        brush = Brush.verticalGradient(
                            colors = if (isActive) {
                                listOf(accent.copy(alpha = 0.90f), accent.copy(alpha = 0.22f))
                            } else {
                                listOf(GlassRimTop, GlassRimBottom)
                            }
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PowerSettingsNew,
                        contentDescription = "切换组网开关",
                        tint = if (status == TunnelStatus.STOPPED) TextPrimary else accent,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = uptimeStr ?: if (status == TunnelStatus.STOPPED) "STANDBY" else "CONNECTING",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = if (uptimeStr != null) 0.8.sp else 1.2.sp,
                            fontFeatureSettings = "tnum"
                        ),
                        color = if (status == TunnelStatus.STOPPED) TextMuted else TextPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = statusHeadline,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Unified Identity & Live Throughput Pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(accent.copy(alpha = 0.12f))
                    .border(1.dp, accent.copy(alpha = 0.32f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 4.5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.5.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = tailnetLabel,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isActive) accent else TextSecondary
                )
            }

            if (isActive && downlinkFormatted.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(ObsidianCard)
                        .border(1.dp, ObsidianBorder, RoundedCornerShape(999.dp))
                        .padding(horizontal = 11.dp, vertical = 4.5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "↓ $downlinkFormatted",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum"
                        ),
                        color = CyanBright
                    )
                    if (uplinkFormatted.isNotBlank()) {
                        Text(
                            text = "  ↑ $uplinkFormatted",
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}
