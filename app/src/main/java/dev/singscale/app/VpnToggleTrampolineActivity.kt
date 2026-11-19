package dev.singscale.app

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.singscale.app.core.SingScaleRepository
import dev.singscale.app.core.TunnelStatus
import dev.singscale.app.service.SingScaleVpnService

/**
 * Lightweight translucent activity invoked only when the user toggles SingScale ON from the
 * Home Screen Widget or Quick Settings Tile before Android VPN consent has been granted.
 */
class VpnToggleTrampolineActivity : ComponentActivity() {

    private var requestLogin: Boolean = false

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            SingScaleVpnService.startTunnel(this, requestInteractiveLogin = requestLogin)
        } else {
            SingScaleRepository.updateTunnelStatus(
                TunnelStatus.STOPPED,
                "已取消 Android VPN 权限授权"
            )
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestLogin = intent?.getBooleanExtra("login", false) == true

        val consentIntent = VpnService.prepare(this)
        if (consentIntent != null) {
            vpnConsentLauncher.launch(consentIntent)
        } else {
            SingScaleVpnService.startTunnel(this, requestInteractiveLogin = requestLogin)
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
