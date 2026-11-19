package dev.singscale.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.RemoteViews
import dev.singscale.app.MainActivity
import dev.singscale.app.R
import dev.singscale.app.VpnToggleTrampolineActivity
import dev.singscale.app.core.RoutingPolicyMode
import dev.singscale.app.core.SingScaleRepository
import dev.singscale.app.core.SingScaleUiState
import dev.singscale.app.core.TunnelStatus
import dev.singscale.app.service.SingScaleVpnService
import kotlin.math.roundToInt

/**
 * 4x2 Status Card Home Screen Widget with Canvas-rendered Chronograph Power Orb.
 */
class SingScaleWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_TOGGLE = "dev.singscale.app.action.WIDGET_TOGGLE"
        const val ACTION_WIDGET_CYCLE_POLICY = "dev.singscale.app.action.WIDGET_CYCLE_POLICY"

        fun updateAllWidgets(context: Context) {
            val appCtx = context.applicationContext
            SingScaleMiniWidgetProvider.updateMiniWidgets(appCtx)
            updateCardWidgetsOnly(appCtx)
        }

        fun updateCardWidgetsOnly(context: Context) {
            val appCtx = context.applicationContext
            SingScaleRepository.init(appCtx)
            val manager = AppWidgetManager.getInstance(appCtx)
            val component = ComponentName(appCtx, SingScaleWidgetProvider::class.java)
            val ids = runCatching { manager.getAppWidgetIds(component) }.getOrDefault(IntArray(0))
            if (ids.isEmpty()) return
            val views = buildRemoteViews(appCtx, SingScaleRepository.state.value)
            for (widgetId in ids) {
                manager.updateAppWidget(widgetId, views)
            }
        }

        fun isPinSupported(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            return manager.isRequestPinAppWidgetSupported
        }

        fun requestPinWidget(context: Context): Boolean {
            val appCtx = context.applicationContext
            SingScaleRepository.init(appCtx)
            val manager = AppWidgetManager.getInstance(appCtx)
            if (!manager.isRequestPinAppWidgetSupported) return false
            val provider = ComponentName(appCtx, SingScaleWidgetProvider::class.java)
            val extras = Bundle().apply {
                putParcelable(
                    AppWidgetManager.EXTRA_APPWIDGET_PREVIEW,
                    buildRemoteViews(appCtx, SingScaleRepository.state.value)
                )
            }
            val callbackIntent = PendingIntent.getBroadcast(
                appCtx,
                902,
                Intent(appCtx, SingScaleWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return manager.requestPinAppWidget(provider, extras, callbackIntent)
        }

        private fun buildRemoteViews(context: Context, state: SingScaleUiState): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_singscale)

            val isActive = state.tunnelStatus == TunnelStatus.RUNNING ||
                state.tunnelStatus == TunnelStatus.STARTING ||
                state.tunnelStatus == TunnelStatus.NEEDS_LOGIN

            val statusText = when (state.tunnelStatus) {
                TunnelStatus.RUNNING -> "运行中"
                TunnelStatus.STARTING -> "连接中"
                TunnelStatus.NEEDS_LOGIN -> "待授权"
                TunnelStatus.STOPPING -> "断开中"
                TunnelStatus.ERROR -> "异常"
                TunnelStatus.STOPPED -> "待机"
            }

            views.setTextViewText(R.id.tv_widget_status, statusText)
            views.setInt(
                R.id.tv_widget_status,
                "setBackgroundResource",
                if (isActive) R.drawable.bg_widget_pill_active else R.drawable.bg_widget_pill_idle
            )
            views.setTextColor(
                R.id.tv_widget_status,
                context.getColor(if (isActive) R.color.widget_accent_emerald else R.color.widget_text_secondary)
            )

            val exitNode = state.activeExitPeer
            val relayNode = state.activeRelayPeer
            val primaryTitle = when {
                state.tunnelStatus == TunnelStatus.NEEDS_LOGIN -> "点击完成设备授权"
                exitNode != null && relayNode != null -> "${relayNode.displayName} → ${exitNode.displayName}"
                exitNode != null -> exitNode.displayName
                relayNode != null -> relayNode.displayName
                state.tailnetName.isNotBlank() -> state.tailnetName
                else -> "SingScale 智能组网"
            }
            views.setTextViewText(R.id.tv_widget_node, primaryTitle)

            val subtitle = when {
                state.tunnelStatus == TunnelStatus.RUNNING && state.activeQuality.reachable && state.activeQuality.latencyMs > 0 -> {
                    val rtt = state.activeQuality.latencyMs.roundToInt()
                    "${state.activeQuality.pathType.badge} · ${rtt} ms"
                }
                state.tunnelStatus == TunnelStatus.RUNNING -> {
                    "${state.onlinePeersCount} 台在线"
                }
                state.tunnelStatus == TunnelStatus.ERROR -> {
                    state.errorMessage?.take(28) ?: "连接异常"
                }
                else -> "轻触右侧旋钮一键连接"
            }
            views.setTextViewText(R.id.tv_widget_metrics, subtitle)
            views.setTextViewText(R.id.tv_widget_policy, state.policyMode.badge)

            // Render crisp Chronograph Power Orb via Canvas
            views.setImageViewBitmap(
                R.id.btn_widget_toggle,
                WidgetCanvasRenderer.renderCardOrb(state.tunnelStatus)
            )

            val toggleIntent = if (VpnService.prepare(context) != null) {
                PendingIntent.getActivity(
                    context,
                    904,
                    Intent(context, VpnToggleTrampolineActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getBroadcast(
                    context,
                    900,
                    Intent(context, SingScaleWidgetProvider::class.java).apply {
                        action = ACTION_WIDGET_TOGGLE
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            views.setOnClickPendingIntent(R.id.btn_widget_toggle, toggleIntent)

            val cyclePolicyIntent = PendingIntent.getBroadcast(
                context,
                901,
                Intent(context, SingScaleWidgetProvider::class.java).apply {
                    action = ACTION_WIDGET_CYCLE_POLICY
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.tv_widget_policy, cyclePolicyIntent)

            val openAppIntent = PendingIntent.getActivity(
                context,
                903,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root_info, openAppIntent)

            return views
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        SingScaleRepository.init(context)
        val views = buildRemoteViews(context, SingScaleRepository.state.value)
        for (appWidgetId in appWidgetIds) {
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        SingScaleRepository.init(context)
        when (intent.action) {
            ACTION_WIDGET_TOGGLE -> {
                SingScaleVpnService.toggleTunnel(context)
                updateAllWidgets(context)
            }

            ACTION_WIDGET_CYCLE_POLICY -> {
                val modes = RoutingPolicyMode.entries
                val curIdx = modes.indexOf(SingScaleRepository.state.value.policyMode)
                val nextMode = modes[(curIdx + 1) % modes.size]
                SingScaleRepository.setPolicyMode(nextMode)
                SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                updateAllWidgets(context)
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                updateAllWidgets(context)
            }
        }
    }
}
