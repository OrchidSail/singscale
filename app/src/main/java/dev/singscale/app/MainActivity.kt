package dev.singscale.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.singscale.app.core.SingScaleRepository
import dev.singscale.app.core.TunnelStatus
import dev.singscale.app.service.SingScaleVpnService
import dev.singscale.app.ui.SingScaleMainScreen
import dev.singscale.app.ui.theme.SingScaleTheme
import dev.singscale.app.widget.SingScaleMiniWidgetProvider
import dev.singscale.app.widget.SingScaleWidgetProvider
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var pendingInteractiveLogin = false
    private var autoOpenNextAuthUrl = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            SingScaleVpnService.startTunnel(this, requestInteractiveLogin = pendingInteractiveLogin)
        } else {
            SingScaleRepository.updateTunnelStatus(
                TunnelStatus.STOPPED,
                "未授予 VPN 权限，请允许以启动组网引擎"
            )
        }
        pendingInteractiveLogin = false
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* optional foreground notification visibility */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        SingScaleApp.instance.ensureLibboxSetup()

        requestNotificationPermissionIfNeeded()

        lifecycleScope.launch {
            SingScaleRepository.state.collectLatest { state ->
                if (autoOpenNextAuthUrl && state.authUrl.isNotBlank() && !state.isLoggedIn) {
                    autoOpenNextAuthUrl = false
                    openAuthUrlInBrowser(state.authUrl)
                } else if (state.isLoggedIn || state.tunnelStatus == TunnelStatus.STOPPED) {
                    autoOpenNextAuthUrl = false
                }
            }
        }

        setContent {
            SingScaleTheme {
                SingScaleMainScreen(
                    onToggleTunnel = { toggleTunnelWithPermission() },
                    onStartInteractiveLogin = {
                        val currentState = SingScaleRepository.state.value
                        if (currentState.isLoggedIn) {
                            if (currentState.tunnelStatus == TunnelStatus.STOPPED) {
                                startTunnelWithPermission(interactiveLogin = false)
                            } else {
                                Toast.makeText(
                                    this,
                                    "当前设备已完成授权，如需重新绑定请先点击「注销」",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else if (currentState.authUrl.isNotBlank()) {
                            openAuthUrlInBrowser(currentState.authUrl)
                        } else {
                            autoOpenNextAuthUrl = true
                            startTunnelWithPermission(interactiveLogin = true)
                        }
                    },
                    onOpenAuthUrl = { url -> openAuthUrlInBrowser(url) },
                    onPinHomeWidget = { pinMiniWidget() },
                    onPinLargeWidget = { pinLargeWidget() }
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun toggleTunnelWithPermission() {
        val status = SingScaleRepository.state.value.tunnelStatus
        if (status == TunnelStatus.RUNNING || status == TunnelStatus.STARTING || status == TunnelStatus.NEEDS_LOGIN) {
            SingScaleVpnService.stopTunnel(this)
        } else {
            startTunnelWithPermission(interactiveLogin = false)
        }
    }

    private fun startTunnelWithPermission(interactiveLogin: Boolean) {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingInteractiveLogin = interactiveLogin
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            SingScaleVpnService.startTunnel(this, requestInteractiveLogin = interactiveLogin)
        }
    }

    private fun openAuthUrlInBrowser(url: String) {
        if (url.isBlank()) return
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        runCatching {
            val customTabs = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabs.launchUrl(this, uri)
        }.onFailure {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                Toast.makeText(this, "无法打开浏览器，请复制授权链接手动打开", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pinMiniWidget() {
        val requested = SingScaleMiniWidgetProvider.requestPinMiniWidget(this)
        if (requested) {
            Toast.makeText(this, "请在弹窗中确认添加 1×1 快捷开关到桌面", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "请长按桌面，在 SingScale 小组件中选择「快捷开关 (1×1)」", Toast.LENGTH_LONG).show()
        }
    }

    private fun pinLargeWidget() {
        val requested = SingScaleWidgetProvider.requestPinWidget(this)
        if (requested) {
            Toast.makeText(this, "请在弹窗中确认添加 4×2 状态卡片到桌面", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "请长按桌面，在 SingScale 小组件中选择「状态卡片 (4×2)」", Toast.LENGTH_LONG).show()
        }
    }
}
