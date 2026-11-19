package dev.singscale.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AddToHomeScreen
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import dev.singscale.app.widget.WidgetCanvasRenderer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.singscale.app.core.ConfigBuilder
import dev.singscale.app.core.PolicyDecisionEvent
import dev.singscale.app.core.RoutingPolicyMode
import dev.singscale.app.core.SingScaleRepository
import dev.singscale.app.core.SingScaleUiState
import dev.singscale.app.core.TailscaleAuthConfig
import dev.singscale.app.core.TrafficFormat
import dev.singscale.app.service.SingScaleVpnService
import dev.singscale.app.ui.components.ExpandableGlassSection
import dev.singscale.app.ui.components.GlassCard
import dev.singscale.app.ui.components.MicroPillBadge
import dev.singscale.app.ui.components.singScaleSwitchColors
import dev.singscale.app.ui.theme.AmberWarn
import dev.singscale.app.ui.theme.CoralAlert
import dev.singscale.app.ui.theme.CyanBright
import dev.singscale.app.ui.theme.CyanPrimary
import dev.singscale.app.ui.theme.EmeraldGood
import dev.singscale.app.ui.theme.IndigoAccent
import dev.singscale.app.ui.theme.ObsidianBg
import dev.singscale.app.ui.theme.ObsidianBorder
import dev.singscale.app.ui.theme.ObsidianElevated
import dev.singscale.app.ui.theme.TextMuted
import dev.singscale.app.ui.theme.TextPrimary
import dev.singscale.app.ui.theme.TextSecondary
import io.nekohasekai.libbox.Libbox
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Unified, Calm, Progressive-Disclosure Settings & Policy Screen:
 * - Primary Essentials (Always Visible):
 *   1. 2x2 Bento Routing Mode Grid + Direct-First toggle (tap latency badge to adjust slider inline)
 *   2. Compact Identity & 1-Tap Add Home Screen Widget card
 * - Secondary / Expert Configurations (Neatly Folded in 4 Clean Accordions by Default!):
 *   1. Custom Domain Split Rules
 *   2. Advanced Probing & Failover Parameters
 *   3. Control Plane (Headscale / AuthKey / Hostname / Logout)
 *   4. Kernel Diagnostics, Decision Log & Raw sing-box JSON
 */
@Composable
fun PolicyScreen(
    state: SingScaleUiState,
    onToggleTunnel: () -> Unit = {},
    onStartInteractiveLogin: () -> Unit = {},
    onPinHomeWidget: () -> Unit = {},
    onPinLargeWidget: () -> Unit = {}
) {
    val context = LocalContext.current
    val t = state.thresholds

    var showDirectLatencySlider by rememberSaveable { mutableStateOf(false) }
    var expandDomains by rememberSaveable { mutableStateOf(false) }
    var expandAdvancedThresholds by rememberSaveable { mutableStateOf(false) }
    var expandControlPlane by rememberSaveable { mutableStateOf(false) }
    var expandDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showRawJson by rememberSaveable { mutableStateOf(false) }

    var newDirectDomain by remember { mutableStateOf("") }
    var newProxyDomain by remember { mutableStateOf("") }

    var controlUrl by remember(state.authConfig.controlUrl) { mutableStateOf(state.authConfig.controlUrl) }
    var authKey by remember(state.authConfig.authKey) { mutableStateOf(state.authConfig.authKey) }
    var hostname by remember(state.authConfig.hostname) { mutableStateOf(state.authConfig.hostname) }
    var ephemeral by remember(state.authConfig.ephemeral) { mutableStateOf(state.authConfig.ephemeral) }
    var relayPortText by remember(state.authConfig.localRelayServerPort) {
        mutableStateOf(if (state.authConfig.localRelayServerPort > 0) state.authConfig.localRelayServerPort.toString() else "")
    }

    val coreVersion = remember {
        runCatching { Libbox.version() }.getOrDefault("1.14.4")
    }
    val proxyDomainList = remember(t.forcedRelayDomains, t.customProxyDomains) {
        (t.forcedRelayDomains + t.customProxyDomains).distinct()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Header
        item {
            Text(
                text = "策略与设置",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                color = TextPrimary
            )
        }

        // 2. Primary Essential #1: 2x2 Routing Mode Bento + Direct-First Switch
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RoutingPolicyMode.entries.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        pair.forEach { mode ->
                            PolicyModeBentoTile(
                                mode = mode,
                                selected = state.policyMode == mode,
                                onClick = {
                                    SingScaleRepository.setPolicyMode(mode)
                                    SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Direct-First Core Switch Card (Tap latency pill to reveal slider!)
                GlassCard(
                    cornerRadius = 18.dp,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "直连质量优先",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = TextPrimary
                                )
                                if (t.directFirstPreferred) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(AmberWarn.copy(alpha = 0.16f))
                                            .clickable { showDirectLatencySlider = !showDirectLatencySlider }
                                            .padding(horizontal = 7.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "≤ ${t.directAcceptableLatencyMs} ms ▾",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = AmberWarn
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "本地直连可达且延迟良好时直接放行，仅受阻或过慢时走中继",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Switch(
                            checked = t.directFirstPreferred,
                            onCheckedChange = { v ->
                                SingScaleRepository.updateThresholds { it.copy(directFirstPreferred = v) }
                                SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                            },
                            colors = singScaleSwitchColors(AmberWarn)
                        )
                    }

                    AnimatedVisibility(visible = t.directFirstPreferred && showDirectLatencySlider) {
                        Column {
                            HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.6f))
                            Spacer(modifier = Modifier.height(8.dp))
                            ThresholdSliderRow(
                                label = "直连最大可接受延迟",
                                valueText = "${t.directAcceptableLatencyMs} ms",
                                value = t.directAcceptableLatencyMs.toFloat(),
                                valueRange = 60f..600f,
                                accent = AmberWarn,
                                onValueChange = { v ->
                                    SingScaleRepository.updateThresholds { it.copy(directAcceptableLatencyMs = v.roundToInt()) }
                                    SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                                }
                            )
                        }
                    }
                }
            }
        }

        // 3. Primary Essential #2: 1x1 Quick Toggle Widget Preview & 1-Tap Pin Card
        item {
            val jewelBitmap = remember(state.tunnelStatus) {
                WidgetCanvasRenderer.renderMiniJewel(state.tunnelStatus).asImageBitmap()
            }

            GlassCard(
                cornerRadius = 18.dp,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Exact 1:1 Pixel-Identical Jewel Widget Preview
                    Image(
                        bitmap = jewelBitmap,
                        contentDescription = "1×1 桌面快捷开关预览",
                        modifier = Modifier
                            .size(62.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(onClick = onToggleTunnel)
                    )

                    Spacer(modifier = Modifier.width(13.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "1×1 桌面快捷开关",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            MicroPillBadge(text = "仅占 1 格", color = CyanBright)
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "精密时计旋钮造型，待机曜石钛金 · 开启点亮翡翠光环",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onPinHomeWidget,
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                        shape = RoundedCornerShape(11.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.AddToHomeScreen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("添加 1×1 快捷开关", fontWeight = FontWeight.Bold, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = onPinLargeWidget,
                        shape = RoundedCornerShape(11.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Rounded.Widgets, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("4×2 卡片", color = TextSecondary, maxLines = 1)
                    }
                }
            }
        }

        // 4. Secondary Group Header
        item {
            Text(
                text = "高级配置（按需展开）",
                style = MaterialTheme.typography.labelLarge,
                color = TextMuted,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp)
            )
        }

        // Accordion #1: Custom Domain Split Rules (Folded by Default)
        item {
            val totalRules = proxyDomainList.size + t.customDirectDomains.size
            ExpandableGlassSection(
                title = "自定义域名规则",
                subtitle = "手动指定特定域名强制走中继或强制直连",
                icon = Icons.Rounded.Route,
                iconTint = EmeraldGood,
                badgeText = if (totalRules > 0) "$totalRules 条" else null,
                badgeColor = EmeraldGood,
                expanded = expandDomains,
                onToggle = { expandDomains = !expandDomains }
            ) {
                DomainChipSection(
                    title = "强制走中继 / 出口",
                    accent = EmeraldGood,
                    domains = proxyDomainList,
                    input = newProxyDomain,
                    onInputChange = { newProxyDomain = it },
                    onAdd = {
                        val cleaned = newProxyDomain.trim().lowercase().trimStart('.')
                        if (cleaned.isNotBlank()) {
                            SingScaleRepository.updateThresholds {
                                it.copy(
                                    forcedRelayDomains = (it.forcedRelayDomains + cleaned).distinct(),
                                    customProxyDomains = (it.customProxyDomains + cleaned).distinct(),
                                    customDirectDomains = it.customDirectDomains.filterNot { d -> d.equals(cleaned, true) }
                                )
                            }
                            newProxyDomain = ""
                            SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                        }
                    },
                    onRemove = { dom ->
                        SingScaleRepository.updateThresholds {
                            it.copy(
                                forcedRelayDomains = it.forcedRelayDomains - dom,
                                customProxyDomains = it.customProxyDomains - dom
                            )
                        }
                        SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                    }
                )

                HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.5f))

                DomainChipSection(
                    title = "强制本地直连",
                    accent = AmberWarn,
                    domains = t.customDirectDomains,
                    input = newDirectDomain,
                    onInputChange = { newDirectDomain = it },
                    onAdd = {
                        val cleaned = newDirectDomain.trim().lowercase().trimStart('.')
                        if (cleaned.isNotBlank()) {
                            SingScaleRepository.updateThresholds {
                                it.copy(customDirectDomains = (it.customDirectDomains + cleaned).distinct())
                            }
                            newDirectDomain = ""
                            SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                        }
                    },
                    onRemove = { dom ->
                        SingScaleRepository.updateThresholds {
                            it.copy(customDirectDomains = it.customDirectDomains - dom)
                        }
                        SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                    }
                )
            }
        }

        // Accordion #2: Advanced Probing & Failover Protection (Folded by Default)
        item {
            ExpandableGlassSection(
                title = "链路探测与容灾保护",
                subtitle = "探测周期 ${t.probeIntervalSec}s · 息屏零唤醒省电 · DERP 保护",
                icon = Icons.Rounded.Speed,
                iconTint = CyanPrimary,
                expanded = expandAdvancedThresholds,
                onToggle = { expandAdvancedThresholds = !expandAdvancedThresholds }
            ) {
                ThresholdSliderRow(
                    label = "前台质量探测周期 (后台自动降频 / 息屏休眠)",
                    valueText = "${t.probeIntervalSec} 秒",
                    value = t.probeIntervalSec.toFloat(),
                    valueRange = 15f..180f,
                    onValueChange = { v ->
                        SingScaleRepository.updateThresholds { it.copy(probeIntervalSec = v.roundToInt()) }
                    }
                )

                ThresholdSliderRow(
                    label = "高延迟自动分流阈值",
                    valueText = "${t.highLatencyThresholdMs} ms",
                    value = t.highLatencyThresholdMs.toFloat(),
                    valueRange = 50f..350f,
                    onValueChange = { v ->
                        SingScaleRepository.updateThresholds { it.copy(highLatencyThresholdMs = v.roundToInt()) }
                        SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                    }
                )

                ThresholdSliderRow(
                    label = "切换防抖滞回分差",
                    valueText = "${t.hysteresisScoreDelta} 分",
                    value = t.hysteresisScoreDelta.toFloat(),
                    valueRange = 3f..25f,
                    onValueChange = { v ->
                        SingScaleRepository.updateThresholds { it.copy(hysteresisScoreDelta = v.roundToInt()) }
                    }
                )

                HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.5f))

                PolicySwitchRow(
                    title = "优先选择 P2P 直连路径",
                    subtitle = "为 UDP 打洞成功的节点赋予更高评分",
                    checked = t.preferDirectPath,
                    onCheckedChange = { v ->
                        SingScaleRepository.updateThresholds { it.copy(preferDirectPath = v) }
                        SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                    }
                )

                PolicySwitchRow(
                    title = "DERP 中转自动降级保护",
                    subtitle = "退化为高延迟中转时切换保守分流防拥塞",
                    checked = t.derpSmartDowngrade,
                    onCheckedChange = { v ->
                        SingScaleRepository.updateThresholds { it.copy(derpSmartDowngrade = v) }
                        SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                    }
                )

                PolicySwitchRow(
                    title = "出口故障安全直连 (Fail-Safe)",
                    subtitle = "出口不可达时保留组网内网并自动放行公网直连",
                    checked = t.failSafeDirectFallback,
                    onCheckedChange = { v ->
                        SingScaleRepository.updateThresholds { it.copy(failSafeDirectFallback = v) }
                        SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                    }
                )

                PolicySwitchRow(
                    title = "允许访问本地局域网 (Allow LAN)",
                    subtitle = "排除 192.168.0.0/16 等私网与无线调试流量",
                    checked = t.allowLanAccess,
                    onCheckedChange = { v ->
                        SingScaleRepository.updateThresholds { it.copy(allowLanAccess = v) }
                        SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                    }
                )

                PolicySwitchRow(
                    title = "允许组网设备直连本机端口",
                    subtitle = "将 Tailnet 认证节点访问本机 100.x:<端口>（如无线调试 ADB / SSH）透明转发至本机回环，绝不暴露公网",
                    checked = t.allowTailnetInboundLocalForward,
                    onCheckedChange = { v ->
                        SingScaleRepository.updateThresholds { it.copy(allowTailnetInboundLocalForward = v) }
                        SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                    }
                )

                PolicySwitchRow(
                    title = "自动接受组网子网路由",
                    subtitle = "接管远端节点宣告的内网网段",
                    checked = t.acceptSubnetRoutes,
                    onCheckedChange = { v ->
                        SingScaleRepository.updateThresholds { it.copy(acceptSubnetRoutes = v) }
                        SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                    }
                )
            }
        }

        // Accordion #3: Control Plane, Account & Hostname (Folded by Default)
        item {
            val accountSub = if (state.selfPeer != null && state.isLoggedIn) {
                "${state.selfPeer.userLogin.ifBlank { state.tailnetName }} · ${state.selfPeer.primaryIpv4}"
            } else if (state.authConfig.controlUrl.isNotBlank()) {
                state.authConfig.controlUrl
            } else {
                "官方 Tailscale 控制面"
            }

            ExpandableGlassSection(
                title = "账号身份与自建控制面",
                subtitle = accountSub,
                icon = Icons.Rounded.Security,
                iconTint = IndigoAccent,
                expanded = expandControlPlane,
                onToggle = { expandControlPlane = !expandControlPlane }
            ) {
                if (state.selfPeer != null && state.isLoggedIn) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(EmeraldGood.copy(alpha = 0.10f))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.selfPeer.userDisplayName.ifBlank {
                                    state.selfPeer.userLogin.ifBlank { state.tailnetName }
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = TextPrimary
                            )
                            Text(
                                text = "${state.selfPeer.hostName} · ${state.selfPeer.primaryIpv4}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        OutlinedButton(
                            onClick = { SingScaleVpnService.triggerLogout(context) },
                            shape = RoundedCornerShape(9.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, tint = CoralAlert, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("注销", color = CoralAlert, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                OutlinedTextField(
                    value = controlUrl,
                    onValueChange = { controlUrl = it.trim() },
                    label = { Text("Control URL (留空默认官方 Tailscale)") },
                    placeholder = { Text("https://headscale.example.com") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = authKey,
                    onValueChange = { authKey = it.trim() },
                    label = { Text("Pre-Auth Key (可选)") },
                    placeholder = { Text("tskey-auth-...") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = hostname,
                        onValueChange = { hostname = it.trim() },
                        label = { Text("本机名称") },
                        placeholder = { Text("singscale-android") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = relayPortText,
                        onValueChange = { relayPortText = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("Relay 端口") },
                        placeholder = { Text("可选") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.width(120.dp)
                    )
                }

                PolicySwitchRow(
                    title = "临时节点模式 (Ephemeral)",
                    subtitle = "下线后自动从控制面移除设备",
                    checked = ephemeral,
                    onCheckedChange = { ephemeral = it }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            SingScaleRepository.updateAuthConfig(
                                TailscaleAuthConfig(
                                    controlUrl = controlUrl,
                                    authKey = authKey,
                                    hostname = hostname,
                                    ephemeral = ephemeral,
                                    localRelayServerPort = relayPortText.toIntOrNull()?.coerceIn(0, 65535) ?: 0
                                )
                            )
                            SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                            Toast.makeText(context, "控制面参数已保存", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                        shape = RoundedCornerShape(11.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("保存参数", fontWeight = FontWeight.Bold)
                    }

                    FilledTonalButton(
                        onClick = onStartInteractiveLogin,
                        shape = RoundedCornerShape(11.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("网页授权登录")
                    }
                }
            }
        }

        // Accordion #4: Kernel Diagnostics, Decision Log & Raw JSON (Folded by Default)
        item {
            val memText = TrafficFormat.formatBytes(state.traffic.memoryBytes)
            ExpandableGlassSection(
                title = "内核诊断与调度日志",
                subtitle = "sing-box v$coreVersion · 内存 $memText · 活跃连接 ${state.traffic.connectionsOut}",
                icon = Icons.Rounded.Code,
                iconTint = TextSecondary,
                expanded = expandDiagnostics,
                onToggle = { expandDiagnostics = !expandDiagnostics }
            ) {
                if (state.decisionLog.isNotEmpty()) {
                    Text("最近调度事件", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    state.decisionLog.take(6).forEach { ev ->
                        DecisionEventItem(ev)
                    }
                } else {
                    Text(
                        text = "当前会话暂无自动切换记录，链路运行平稳。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }

                val previewJson = remember(state.lastGeneratedConfigJson) {
                    state.lastGeneratedConfigJson.ifBlank {
                        ConfigBuilder.build(state, "singscale-preview").configJson
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { showRawJson = !showRawJson },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (showRawJson) "收起 JSON 配置" else "查看运行 JSON")
                    }
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("sing-box.json", previewJson))
                            Toast.makeText(context, "已复制 JSON 配置", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("复制")
                    }
                }

                AnimatedVisibility(visible = showRawJson) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(ObsidianBg)
                            .border(1.dp, ObsidianBorder, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = previewJson,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.5.sp
                            ),
                            color = CyanBright
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PolicyModeBentoTile(
    mode: RoutingPolicyMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon: ImageVector = when (mode) {
        RoutingPolicyMode.SMART_QUALITY -> Icons.Rounded.AutoAwesome
        RoutingPolicyMode.GLOBAL_EXIT -> Icons.Rounded.Public
        RoutingPolicyMode.RULE_SPLIT -> Icons.Rounded.Route
        RoutingPolicyMode.MESH_ONLY -> Icons.Rounded.Hub
    }
    val accent = when (mode) {
        RoutingPolicyMode.SMART_QUALITY -> CyanPrimary
        RoutingPolicyMode.GLOBAL_EXIT -> EmeraldGood
        RoutingPolicyMode.RULE_SPLIT -> AmberWarn
        RoutingPolicyMode.MESH_ONLY -> IndigoAccent
    }
    val shortDesc = when (mode) {
        RoutingPolicyMode.SMART_QUALITY -> "实测直连质量，受阻自动切中继"
        RoutingPolicyMode.GLOBAL_EXIT -> "全部公网流量经出口转发"
        RoutingPolicyMode.RULE_SPLIT -> "按自定义后缀列表分流"
        RoutingPolicyMode.MESH_ONLY -> "仅接管 100.x 组网内网"
    }

    GlassCard(
        modifier = modifier,
        cornerRadius = 18.dp,
        accentTint = if (selected) accent else null,
        highlightBorderColor = if (selected) accent else null,
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) accent else TextMuted,
                modifier = Modifier.size(19.dp)
            )
            if (selected) {
                MicroPillBadge(text = "当前", color = accent, filled = true)
            }
        }

        Text(
            text = mode.badge,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = TextPrimary,
            maxLines = 1
        )

        Text(
            text = shortDesc,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ThresholdSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    accent: Color = CyanPrimary,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
            MicroPillBadge(text = valueText, color = accent, filled = true)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = ObsidianElevated
            )
        )
    }
}

@Composable
private fun PolicySwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    accent: Color = CyanPrimary,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimary
            )
            Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = singScaleSwitchColors(accent)
        )
    }
}

@Composable
private fun DomainChipSection(
    title: String,
    accent: Color,
    domains: List<String>,
    input: String,
    onInputChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "$title (${domains.size})",
            style = MaterialTheme.typography.labelMedium,
            color = accent
        )
        if (domains.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                domains.forEach { domain ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(accent.copy(alpha = 0.13f))
                            .border(0.8.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                            .clickable { onRemove(domain) }
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(domain, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                        Spacer(modifier = Modifier.width(5.dp))
                        Icon(Icons.Rounded.Close, contentDescription = "移除", tint = accent, modifier = Modifier.size(12.dp))
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ObsidianElevated.copy(alpha = 0.7f))
                    .border(1.dp, ObsidianBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 11.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (input.isEmpty()) {
                    Text("输入域名后缀，如 openai.com", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
                    cursorBrush = SolidColor(accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent)
                    .clickable(onClick = onAdd),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "添加", tint = Color(0xFF04131A), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun DecisionEventItem(event: PolicyDecisionEvent) {
    val timeStr = remember(event.timestampMs) {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(event.timestampMs))
    }
    val accent = if (event.isWarning) AmberWarn else CyanPrimary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ObsidianElevated.copy(alpha = 0.55f))
            .padding(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(text = timeStr, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
            Text(text = event.detail, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}
