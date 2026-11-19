package dev.singscale.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.singscale.app.core.RoutingPolicyMode
import dev.singscale.app.core.SingScaleUiState
import dev.singscale.app.core.TunnelStatus
import dev.singscale.app.ui.theme.CyanBright
import dev.singscale.app.ui.theme.CyanPrimary
import dev.singscale.app.ui.theme.EmeraldGood
import dev.singscale.app.ui.theme.IndigoAccent
import dev.singscale.app.ui.theme.ObsidianBorder
import dev.singscale.app.ui.theme.ObsidianCard
import dev.singscale.app.ui.theme.ObsidianElevated
import dev.singscale.app.ui.theme.TextMuted
import dev.singscale.app.ui.theme.TextPrimary
import dev.singscale.app.ui.theme.TextSecondary
import dev.singscale.app.ui.theme.accentColor
import kotlin.math.roundToInt

/**
 * Clean, lightweight Flighty-style inline route map:
 * Origin <-> Animated Packet Wire <-> Active Gateway, with optional middle Relay pill.
 */
@Composable
fun TopologyBar(
    state: SingScaleUiState,
    modifier: Modifier = Modifier
) {
    val isRunning = state.tunnelStatus == TunnelStatus.RUNNING
    val relayPeer = state.activeRelayPeer
    val exitPeer = state.activeExitPeer
    val pathColor = if (isRunning) state.activeQuality.pathType.accentColor() else TextMuted

    val flowPhase: Float = if (isRunning) {
        val infinite = rememberInfiniteTransition(label = "topoFlow")
        val animPhase by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1900, easing = LinearEasing)
            ),
            label = "flowPhase"
        )
        animPhase
    } else {
        0f
    }

    val localTitle = state.selfPeer?.displayName
        ?.takeIf { it.isNotBlank() && it != "localhost" && it != "cached-self" }
        ?: "Android 本机"
    val localIp = state.selfPeer?.primaryIpv4?.ifBlank { "172.19.0.1" } ?: "172.19.0.1"

    val gatewayTitle = when {
        exitPeer != null -> exitPeer.displayName
        state.policyMode == RoutingPolicyMode.MESH_ONLY -> "Tailnet 内网"
        !isRunning -> "等待连接"
        else -> "公网直连"
    }
    val gatewayIp = when {
        exitPeer != null -> exitPeer.primaryIpv4
        state.policyMode == RoutingPolicyMode.MESH_ONLY -> "100.64.0.0/10"
        !isRunning -> "—"
        else -> "本地物理出口"
    }

    val wireBadge = when {
        relayPeer != null -> "经 ${relayPeer.displayName}"
        !isRunning -> "待机"
        state.activeQuality.reachable && state.activeQuality.latencyMs > 0 ->
            "${state.activeQuality.latencyMs.roundToInt()} ms"
        else -> "P2P"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ObsidianElevated.copy(alpha = 0.45f))
            .border(0.8.dp, ObsidianBorder.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "本机",
                style = MaterialTheme.typography.labelSmall,
                color = CyanBright
            )
            Text(
                text = localTitle,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = localIp,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1
            )
        }

        Box(
            modifier = Modifier
                .weight(0.85f)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            RoutePacketWire(
                active = isRunning,
                phase = flowPhase,
                startColor = CyanPrimary,
                endColor = if (relayPeer != null) IndigoAccent else if (exitPeer != null) EmeraldGood else pathColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianCard)
                    .border(0.8.dp, ObsidianBorder, RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = wireBadge,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (relayPeer != null) IndigoAccent else if (isRunning) pathColor else TextMuted,
                    maxLines = 1
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = "出口",
                style = MaterialTheme.typography.labelSmall,
                color = if (exitPeer != null) EmeraldGood else TextSecondary,
                textAlign = TextAlign.End
            )
            Text(
                text = gatewayTitle,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = gatewayIp,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                textAlign = TextAlign.End,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun RoutePacketWire(
    active: Boolean,
    phase: Float,
    startColor: Color,
    endColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val y = size.height / 2f
        val pad = 4.dp.toPx()
        val usableW = (size.width - pad * 2f).coerceAtLeast(1f)

        drawCircle(
            color = if (active) startColor else TextMuted,
            radius = 3.dp.toPx(),
            center = Offset(pad, y)
        )
        drawCircle(
            color = if (active) endColor else TextMuted,
            radius = 3.dp.toPx(),
            center = Offset(size.width - pad, y)
        )

        drawLine(
            brush = Brush.horizontalGradient(
                listOf(
                    startColor.copy(alpha = if (active) 0.45f else 0.22f),
                    endColor.copy(alpha = if (active) 0.45f else 0.22f)
                )
            ),
            start = Offset(pad, y),
            end = Offset(size.width - pad, y),
            strokeWidth = 1.8.dp.toPx(),
            pathEffect = if (active) null else PathEffect.dashPathEffect(floatArrayOf(7f, 7f), 0f),
            cap = StrokeCap.Round
        )

        if (active) {
            for (offset in listOf(0f, 0.5f)) {
                val p = (phase + offset) % 1f
                val px = pad + usableW * p
                drawCircle(
                    color = CyanBright.copy(alpha = 0.25f),
                    radius = 5.dp.toPx(),
                    center = Offset(px, y)
                )
                drawCircle(
                    color = Color.White,
                    radius = 2.2.dp.toPx(),
                    center = Offset(px, y)
                )
            }
        }
    }
}
