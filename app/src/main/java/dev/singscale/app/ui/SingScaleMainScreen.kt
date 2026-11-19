package dev.singscale.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.singscale.app.core.RoutingPolicyMode
import dev.singscale.app.core.SingScaleRepository
import dev.singscale.app.core.TailscaleAuthConfig
import dev.singscale.app.core.TunnelStatus
import dev.singscale.app.service.SingScaleVpnService
import dev.singscale.app.ui.components.AuroraMeshBackdrop
import dev.singscale.app.ui.components.GlassCard
import dev.singscale.app.ui.screens.DashboardScreen
import dev.singscale.app.ui.screens.NodesScreen
import dev.singscale.app.ui.screens.PolicyScreen
import dev.singscale.app.ui.screens.TrafficInspectorScreen
import dev.singscale.app.ui.theme.AmberWarn
import dev.singscale.app.ui.theme.CyanBright
import dev.singscale.app.ui.theme.CyanPrimary
import dev.singscale.app.ui.theme.EmeraldGood
import dev.singscale.app.ui.theme.GlassDockBg
import dev.singscale.app.ui.theme.GlassRimBottom
import dev.singscale.app.ui.theme.GlassRimTop
import dev.singscale.app.ui.theme.IndigoAccent
import dev.singscale.app.ui.theme.ObsidianBg
import dev.singscale.app.ui.theme.ObsidianSurface
import dev.singscale.app.ui.theme.TextMuted
import dev.singscale.app.ui.theme.TextPrimary
import dev.singscale.app.ui.theme.TextSecondary
import dev.singscale.app.ui.theme.accentColor

private data class NavTab(val label: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingScaleMainScreen(
    onToggleTunnel: () -> Unit,
    onStartInteractiveLogin: () -> Unit,
    onOpenAuthUrl: (String) -> Unit,
    onPinHomeWidget: () -> Unit,
    onPinLargeWidget: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val state by SingScaleRepository.state.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showAuthConfigSheet by remember { mutableStateOf(false) }
    var showQuickModeSheet by remember { mutableStateOf(false) }

    // 4 spacious, focused destinations instead of 5 cramped tabs
    val tabs = remember {
        listOf(
            NavTab("连接", Icons.Rounded.SpaceDashboard),
            NavTab("分流", Icons.Rounded.DataUsage),
            NavTab("节点", Icons.Rounded.Hub),
            NavTab("设置", Icons.Rounded.Tune)
        )
    }

    val isTunnelActive = state.tunnelStatus == TunnelStatus.RUNNING
    val statusAccent = state.tunnelStatus.accentColor()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        AuroraMeshBackdrop(
            primaryColor = statusAccent,
            active = isTunnelActive,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            AnimatedContent(
                targetState = selectedTab.coerceIn(0, 3),
                transitionSpec = {
                    fadeIn(animationSpec = tween(200)) togetherWith
                        fadeOut(animationSpec = tween(140))
                },
                label = "mainTabTransition"
            ) { tabIdx ->
                when (tabIdx) {
                    0 -> DashboardScreen(
                        state = state,
                        onToggleTunnel = onToggleTunnel,
                        onStartInteractiveLogin = onStartInteractiveLogin,
                        onOpenAuthUrl = onOpenAuthUrl,
                        onOpenAuthSettingsSheet = { showAuthConfigSheet = true },
                        onOpenQuickModeSheet = { showQuickModeSheet = true },
                        onNavigateToNodes = { selectedTab = 2 },
                        onNavigateToTraffic = { selectedTab = 1 }
                    )

                    1 -> TrafficInspectorScreen(state = state)

                    2 -> NodesScreen(
                        state = state,
                        onStartInteractiveLogin = onStartInteractiveLogin
                    )

                    else -> PolicyScreen(
                        state = state,
                        onToggleTunnel = onToggleTunnel,
                        onStartInteractiveLogin = onStartInteractiveLogin,
                        onPinHomeWidget = onPinHomeWidget,
                        onPinLargeWidget = onPinLargeWidget
                    )
                }
            }
        }

        // Floating Titanium-Glass Navigation Capsule Dock (4 spacious items)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            ObsidianBg.copy(alpha = 0.85f),
                            ObsidianBg
                        )
                    )
                )
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 10.dp)
        ) {
            FloatingGlassNavDock(
                tabs = tabs,
                selectedTab = selectedTab.coerceIn(0, 3),
                isTunnelActive = isTunnelActive,
                onSelectTab = { next ->
                    if (next != selectedTab) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        selectedTab = next
                    }
                }
            )
        }
    }

    // 1-Tap Quick Routing Mode Selector Sheet (invoked right from Home header pill)
    if (showQuickModeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showQuickModeSheet = false },
            containerColor = ObsidianSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "选择路由模式",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                Text(
                    text = "切换后立即在后台无感热更新路由表，不断开现有连接",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))

                RoutingPolicyMode.entries.forEach { mode ->
                    val selected = state.policyMode == mode
                    val accent = when (mode) {
                        RoutingPolicyMode.SMART_QUALITY -> CyanPrimary
                        RoutingPolicyMode.GLOBAL_EXIT -> EmeraldGood
                        RoutingPolicyMode.RULE_SPLIT -> AmberWarn
                        RoutingPolicyMode.MESH_ONLY -> IndigoAccent
                    }
                    val modeIcon = when (mode) {
                        RoutingPolicyMode.SMART_QUALITY -> Icons.Rounded.AutoAwesome
                        RoutingPolicyMode.GLOBAL_EXIT -> Icons.Rounded.Public
                        RoutingPolicyMode.RULE_SPLIT -> Icons.Rounded.Route
                        RoutingPolicyMode.MESH_ONLY -> Icons.Rounded.Hub
                    }

                    GlassCard(
                        cornerRadius = 16.dp,
                        accentTint = if (selected) accent else null,
                        highlightBorderColor = if (selected) accent else null,
                        contentPadding = PaddingValues(14.dp),
                        onClick = {
                            SingScaleRepository.setPolicyMode(mode)
                            SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                            showQuickModeSheet = false
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = modeIcon,
                                contentDescription = null,
                                tint = if (selected) accent else TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mode.title,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = TextPrimary
                                )
                                Text(
                                    text = mode.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (selected) accent else TextMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showAuthConfigSheet) {
        var controlUrl by remember { mutableStateOf(state.authConfig.controlUrl) }
        var authKey by remember { mutableStateOf(state.authConfig.authKey) }

        ModalBottomSheet(
            onDismissRequest = { showAuthConfigSheet = false },
            containerColor = ObsidianSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "AuthKey 免交互登录 / 自建 Headscale",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "默认留空即可使用官方 Tailscale OAuth 网页登录；如需对接自建 Headscale 或使用 Pre-Auth Key 可在此填入。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = controlUrl,
                    onValueChange = { controlUrl = it.trim() },
                    label = { Text("Control URL (可选)") },
                    placeholder = { Text("https://headscale.example.com") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = authKey,
                    onValueChange = { authKey = it.trim() },
                    label = { Text("Pre-Auth Key (可选)") },
                    placeholder = { Text("tskey-auth-...") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        SingScaleRepository.updateAuthConfig(
                            TailscaleAuthConfig(
                                controlUrl = controlUrl,
                                authKey = authKey,
                                hostname = state.authConfig.hostname,
                                ephemeral = state.authConfig.ephemeral,
                                localRelayServerPort = state.authConfig.localRelayServerPort
                            )
                        )
                        showAuthConfigSheet = false
                        onStartInteractiveLogin()
                        SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("保存并立即连接", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun FloatingGlassNavDock(
    tabs: List<NavTab>,
    selectedTab: Int,
    isTunnelActive: Boolean,
    onSelectTab: (Int) -> Unit
) {
    val dockShape = RoundedCornerShape(26.dp)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .shadow(
                elevation = 20.dp,
                shape = dockShape,
                ambientColor = Color.Black,
                spotColor = Color.Black
            )
            .clip(dockShape)
            .background(GlassDockBg)
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(GlassRimTop, GlassRimBottom)
                ),
                shape = dockShape
            )
            .padding(horizontal = 6.dp, vertical = 5.dp)
    ) {
        val count = tabs.size.coerceAtLeast(1)
        val itemWidth = maxWidth / count
        val indicatorOffset by animateDpAsState(
            targetValue = itemWidth * selectedTab.coerceIn(0, count - 1),
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "navDockIndicator"
        )

        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(itemWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            CyanPrimary.copy(alpha = 0.22f),
                            CyanPrimary.copy(alpha = 0.07f)
                        )
                    )
                )
                .border(
                    width = 0.8.dp,
                    color = CyanPrimary.copy(alpha = 0.36f),
                    shape = RoundedCornerShape(20.dp)
                )
        )

        Row(modifier = Modifier.fillMaxSize()) {
            tabs.forEachIndexed { idx, tab ->
                val selected = idx == selectedTab
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelectTab(idx) }
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = if (selected) CyanBright else TextMuted,
                            modifier = Modifier.size(21.dp)
                        )
                        if (idx == 0 && isTunnelActive) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 3.dp, y = (-1).dp)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(EmeraldGood)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (selected) TextPrimary else TextMuted,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
