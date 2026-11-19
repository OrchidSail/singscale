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
import dev.singscale.app.R
import dev.singscale.app.VpnToggleTrampolineActivity
import dev.singscale.app.core.SingScaleRepository
import dev.singscale.app.core.SingScaleUiState
import dev.singscale.app.core.TunnelStatus
import dev.singscale.app.service.SingScaleVpnService

/**
 * 1x1 Sculptural Chronograph Jewel Widget:
 * Pure symmetrical 1:1 squircle rendered via supersampled anti-aliased Canvas.
 */
class SingScaleMiniWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_MINI_WIDGET_TOGGLE = "dev.singscale.app.action.MINI_WIDGET_TOGGLE"

        fun updateMiniWidgets(context: Context) {
            val appCtx = context.applicationContext
            SingScaleRepository.init(appCtx)
            val manager = AppWidgetManager.getInstance(appCtx)
            val component = ComponentName(appCtx, SingScaleMiniWidgetProvider::class.java)
            val ids = runCatching { manager.getAppWidgetIds(component) }.getOrDefault(IntArray(0))
            if (ids.isEmpty()) return
            val state = SingScaleRepository.state.value
            val views = buildRemoteViews(appCtx, state)
            for (widgetId in ids) {
                manager.updateAppWidget(widgetId, views)
            }
        }

        fun requestPinMiniWidget(context: Context): Boolean {
            val appCtx = context.applicationContext
            SingScaleRepository.init(appCtx)
            val manager = AppWidgetManager.getInstance(appCtx)
            if (!manager.isRequestPinAppWidgetSupported) return false
            val provider = ComponentName(appCtx, SingScaleMiniWidgetProvider::class.java)
            val extras = Bundle().apply {
                putParcelable(
                    AppWidgetManager.EXTRA_APPWIDGET_PREVIEW,
                    buildRemoteViews(appCtx, SingScaleRepository.state.value)
                )
            }
            val callbackIntent = PendingIntent.getBroadcast(
                appCtx,
                912,
                Intent(appCtx, SingScaleMiniWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return manager.requestPinAppWidget(provider, extras, callbackIntent)
        }

        internal fun buildRemoteViews(context: Context, state: SingScaleUiState): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_singscale_mini)
            views.setImageViewBitmap(
                R.id.iv_mini_canvas,
                WidgetCanvasRenderer.renderMiniJewel(state.tunnelStatus)
            )

            val clickIntent = if (VpnService.prepare(context) != null) {
                PendingIntent.getActivity(
                    context,
                    910,
                    Intent(context, VpnToggleTrampolineActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getBroadcast(
                    context,
                    911,
                    Intent(context, SingScaleMiniWidgetProvider::class.java).apply {
                        action = ACTION_MINI_WIDGET_TOGGLE
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            views.setOnClickPendingIntent(R.id.mini_widget_root, clickIntent)
            views.setOnClickPendingIntent(R.id.iv_mini_canvas, clickIntent)
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
            ACTION_MINI_WIDGET_TOGGLE -> {
                SingScaleVpnService.toggleTunnel(context)
                SingScaleWidgetProvider.updateAllWidgets(context)
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                updateMiniWidgets(context)
            }
        }
    }
}
