package dev.singscale.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AddToHomeScreen
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.singscale.app.core.ConfigBuilder
import dev.singscale.app.core.SingScaleRepository
import dev.singscale.app.core.SingScaleUiState
import dev.singscale.app.core.TailscaleAuthConfig
import dev.singscale.app.core.TunnelStatus
import dev.singscale.app.service.SingScaleVpnService
import dev.singscale.app.ui.components.GlassCard
import dev.singscale.app.ui.components.MicroPillBadge
import dev.singscale.app.ui.components.SingScaleBrandMark
import dev.singscale.app.ui.components.singScaleSwitchColors
import dev.singscale.app.ui.theme.CoralAlert
import dev.singscale.app.ui.theme.CyanBright
import dev.singscale.app.ui.theme.CyanPrimary
import dev.singscale.app.ui.theme.EmeraldGood
import dev.singscale.app.ui.theme.GlassRimBottom
import dev.singscale.app.ui.theme.GlassRimTop
import dev.singscale.app.ui.theme.IndigoAccent
import dev.singscale.app.ui.theme.ObsidianBg
import dev.singscale.app.ui.theme.ObsidianBorder
import dev.singscale.app.ui.theme.ObsidianCard
import dev.singscale.app.ui.theme.ObsidianElevated
import dev.singscale.app.ui.theme.TextPrimary
import dev.singscale.app.ui.theme.TextSecondary
import io.nekohasekai.libbox.Libbox
import kotlin.math.roundToInt

@Composable
fun SettingsWidgetScreen(
    state: SingScaleUiState,
    onToggleTunnel: () -> Unit,
    onStartInteractiveLogin: () -> Unit,
    onPinHomeWidget: () -> Unit
) {
    val context = LocalContext.current
    var controlUrl by remember(state.authConfig.controlUrl) { mutableStateOf(state.authConfig.controlUrl) }
    var authKey by remember(state.authConfig.authKey) { mutableStateOf(state.authConfig.authKey) }
    var hostname by remember(state.authConfig.hostname) { mutableStateOf(state.authConfig.hostname) }
    var ephemeral by remember(state.authConfig.ephemeral) { mutableStateOf(state.authConfig.ephemeral) }
    var relayPortText by remember(state.authConfig.localRelayServerPort) {
        mutableStateOf(if (state.authConfig.localRelayServerPort > 0) state.authConfig.localRelayServerPort.toString() else "")
    }
    var showRawConfig by remember { mutableStateOf(false) }

    val coreVersion = remember {
        runCatching { Libbox.version() }.getOrDefault("1.14.4-singscale")
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 106.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        item {
            Column {
                Text(
                    text = "桌面组件与控制面",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                    color = TextPrimary
                )
                Text(
                    text = "一键固定 Android 主屏幕开关小组件、磁贴及配置私有 Headscale",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        // Interactive Home Screen Widget Preview & 1-Tap Pin Card
        item {
            val isActive = state.tunnelStatus == TunnelStatus.RUNNING ||
                state.tunnelStatus == TunnelStatus.STARTING ||
                state.tunnelStatus == TunnelStatus.NEEDS_LOGIN
            val nodeLabel = state.activeExitPeer?.displayName
                ?: state.activeRelayPeer?.displayName
                ?: state.tailnetName.ifBlank { "SingScale 智能组网" }
            val metricLabel = if (isActive && state.activeQuality.reachable && state.activeQuality.latencyMs > 0) {
                "${state.activeQuality.pathType.badge} · ${state.activeQuality.latencyMs.roundToInt()} ms · 评分 ${state.activeQuality.score}"
            } else {
                "轻触右侧电源键随时启停 VPN 隧道"
            }

            GlassCard(
                contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Widgets, contentDescription = null, tint = CyanBright, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Android 桌面开关小组件",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                }

                // Live Titanium-Glass Replica of the Home Screen Widget
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    ObsidianElevated,
                                    ObsidianBg
                                )
                            )
                        )
                        .border(
                            width = 1.dp,
                            brush = Brush.verticalGradient(listOf(GlassRimTop, GlassRimBottom)),
                            shape = RoundedCornerShape(22.dp)
                        )
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SingScaleBrandMark(size = 22.dp, accent = if (isActive) EmeraldGood else CyanPrimary)
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                text = "SingScale",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            MicroPillBadge(
                                text = if (isActive) "运行中" else "已关闭",
                                color = if (isActive) EmeraldGood else TextSecondary,
                                filled = isActive
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = nodeLabel,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = metricLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        MicroPillBadge(
                            text = "模式：${state.policyMode.badge}",
                            color = CyanBright
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(if (isActive) EmeraldGood else ObsidianCard)
                            .border(
                                width = 1.5.dp,
                                color = if (isActive) EmeraldGood else CyanPrimary.copy(alpha = 0.6f),
                                shape = CircleShape
                            )
                            .clickable(onClick = onToggleTunnel),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PowerSettingsNew,
                            contentDescription = "手动开关隧道",
                            tint = if (isActive) Color(0xFF021F15) else CyanBright,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                Button(
                    onClick = onPinHomeWidget,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Rounded.AddToHomeScreen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("一键添加桌面小组件到主屏幕", fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }

        // Tailscale / Headscale Account & Control Plane Card
        item {
            GlassCard(
                contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Security, contentDescription = null, tint = IndigoAccent, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Tailscale 身份与控制面配置",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                }

                if (state.selfPeer != null && state.isLoggedIn) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(EmeraldGood.copy(alpha = 0.10f))
                            .border(1.dp, EmeraldGood.copy(alpha = 0.34f), RoundedCornerShape(14.dp))
                            .padding(13.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.selfPeer.userDisplayName.ifBlank {
                                    state.selfPeer.userLogin.ifBlank { state.tailnetName }
                                },
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Text(
                                text = "本机 IP：${state.selfPeer.primaryIpv4} · ${state.selfPeer.hostName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        OutlinedButton(
                            onClick = { SingScaleVpnService.triggerLogout(context) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, tint = CoralAlert, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("注销", color = CoralAlert)
                        }
                    }
                }

                OutlinedTextField(
                    value = controlUrl,
                    onValueChange = { controlUrl = it.trim() },
                    label = { Text("控制面地址 Control URL (留空默认官方 Tailscale)") },
                    placeholder = { Text("https://controlplane.tailscale.com") },
                    singleLine = true,
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = authKey,
                    onValueChange = { authKey = it.trim() },
                    label = { Text("预授权密钥 AuthKey (可选，留空走网页 OAuth)") },
                    placeholder = { Text("tskey-auth-...") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = hostname,
                        onValueChange = { hostname = it.trim() },
                        label = { Text("设备主机名") },
                        placeholder = { Text("singscale-android") },
                        singleLine = true,
                        shape = RoundedCornerShape(13.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = relayPortText,
                        onValueChange = { relayPortText = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("本机 Relay 端口") },
                        placeholder = { Text("可选") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(13.dp),
                        modifier = Modifier.width(126.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("临时节点模式 (Ephemeral)", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        Text("断开后自动从控制面清理离线节点记录", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                    Switch(checked = ephemeral, onCheckedChange = { ephemeral = it }, colors = singScaleSwitchColors())
                }

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
                            Toast.makeText(context, "控制面与节点参数已保存", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("保存配置", fontWeight = FontWeight.Bold, maxLines = 1)
                    }

                    FilledTonalButton(
                        onClick = onStartInteractiveLogin,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("网页登录授权", maxLines = 1)
                    }
                }
            }
        }

        // Generated sing-box 1.14.4 Config Inspector & Core Version Info
        item {
            val previewJson = remember(state.lastGeneratedConfigJson) {
                state.lastGeneratedConfigJson.ifBlank {
                    ConfigBuilder.build(state, "singscale-preview").configJson
                }
            }

            GlassCard(
                contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Code, contentDescription = null, tint = CyanBright, modifier = Modifier.size(19.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "sing-box 内核与实时配置",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "Go Core v$coreVersion · Tailscale tsnet 深度集成",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = { showRawConfig = !showRawConfig },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(if (showRawConfig) "收起" else "查看 JSON")
                    }
                }

                if (showRawConfig) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("sing-box.json", previewJson))
                                Toast.makeText(context, "已复制 sing-box JSON 配置", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("复制完整 JSON")
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(13.dp))
                            .background(ObsidianBg)
                            .border(1.dp, ObsidianBorder, RoundedCornerShape(13.dp))
                            .padding(12.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = previewJson,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp
                            ),
                            color = CyanBright
                        )
                    }
                }
            }
        }
    }
}
