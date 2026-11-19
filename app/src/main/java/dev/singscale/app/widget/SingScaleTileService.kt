package dev.singscale.app.widget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.singscale.app.VpnToggleTrampolineActivity
import dev.singscale.app.core.SingScaleRepository
import dev.singscale.app.core.TunnelStatus
import dev.singscale.app.service.SingScaleVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Android Quick Settings Tile synced in real time with SingScaleRepository.
 */
class SingScaleTileService : TileService() {

    companion object {
        fun requestListeningUpdate(context: Context) {
            runCatching {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, SingScaleTileService::class.java)
                )
            }
        }
    }

    private var tileScope: CoroutineScope? = null
    private var collectJob: Job? = null

    private data class TileSnapshotKey(
        val status: TunnelStatus,
        val exitId: String,
        val relayId: String,
        val policyBadge: String,
        val latencyBucket: Int
    )

    override fun onStartListening() {
        super.onStartListening()
        SingScaleRepository.init(this)
        syncTile()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        tileScope = scope
        collectJob = scope.launch {
            SingScaleRepository.state
                .map { s ->
                    TileSnapshotKey(
                        status = s.tunnelStatus,
                        exitId = s.activeExitPeerId,
                        relayId = s.activeRelayPeerId,
                        policyBadge = s.policyMode.badge,
                        latencyBucket = if (s.activeQuality.reachable && s.activeQuality.latencyMs > 0) {
                            (s.activeQuality.latencyMs / 10.0).toInt()
                        } else -1
                    )
                }
                .distinctUntilChanged()
                .collect {
                    syncTile()
                }
        }
    }

    override fun onStopListening() {
        collectJob?.cancel()
        tileScope?.cancel()
        tileScope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        SingScaleRepository.init(this)
        val act = Runnable {
            val status = SingScaleRepository.state.value.tunnelStatus
            val isRunningLike = status == TunnelStatus.RUNNING ||
                status == TunnelStatus.STARTING ||
                status == TunnelStatus.NEEDS_LOGIN
            if (!isRunningLike && VpnService.prepare(this) != null) {
                val trampIntent = Intent(this, VpnToggleTrampolineActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pi = PendingIntent.getActivity(
                        this,
                        920,
                        trampIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pi)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(trampIntent)
                }
            } else {
                SingScaleVpnService.toggleTunnel(this)
                syncTile()
            }
        }
        if (isLocked) {
            unlockAndRun(act)
        } else {
            act.run()
        }
    }

    private fun syncTile() {
        val tile = qsTile ?: return
        val s = SingScaleRepository.state.value
        val active = s.tunnelStatus == TunnelStatus.RUNNING ||
            s.tunnelStatus == TunnelStatus.STARTING ||
            s.tunnelStatus == TunnelStatus.NEEDS_LOGIN

        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "SingScale"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                s.tunnelStatus == TunnelStatus.RUNNING -> {
                    val node = s.activeExitPeer?.displayName ?: s.activeRelayPeer?.displayName ?: s.policyMode.badge
                    val rtt = if (s.activeQuality.reachable && s.activeQuality.latencyMs > 0) {
                        " · ${s.activeQuality.latencyMs.roundToInt()}ms"
                    } else ""
                    "$node$rtt"
                }
                s.tunnelStatus == TunnelStatus.NEEDS_LOGIN -> "待登录授权"
                s.tunnelStatus == TunnelStatus.STARTING -> "正在连接…"
                else -> "已关闭"
            }
        }
        tile.updateTile()
    }
}
