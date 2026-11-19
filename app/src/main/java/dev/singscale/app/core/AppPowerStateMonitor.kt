package dev.singscale.app.core

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dev.singscale.app.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.max

enum class RuntimePowerMode {
    /** Screen is ON and MainActivity is visible in foreground. */
    FOREGROUND_INTERACTIVE,

    /** Screen is ON while user is in another app or Home Screen. */
    BACKGROUND_SCREEN_ON,

    /** Screen is OFF — suspend all UI/telemetry timers and active radio probes. */
    DOZE_SCREEN_OFF
}

data class RuntimePowerState(
    val isScreenInteractive: Boolean = true,
    val isAppForeground: Boolean = false,
    val isBatterySaverEnabled: Boolean = false
) {
    val mode: RuntimePowerMode
        get() = when {
            !isScreenInteractive -> RuntimePowerMode.DOZE_SCREEN_OFF
            isAppForeground -> RuntimePowerMode.FOREGROUND_INTERACTIVE
            else -> RuntimePowerMode.BACKGROUND_SCREEN_ON
        }

    val shouldStreamLiveUiTelemetry: Boolean
        get() = mode == RuntimePowerMode.FOREGROUND_INTERACTIVE

    /**
     * Computes adaptive Tailnet snapshot polling interval in milliseconds.
     * Returns <= 0L when polling should be completely suspended (Screen OFF).
     */
    fun tailnetPollIntervalMs(
        isWaitingForAuth: Boolean,
        withinStartupBootstrapWindow: Boolean
    ): Long = when (mode) {
        RuntimePowerMode.DOZE_SCREEN_OFF -> 0L
        RuntimePowerMode.FOREGROUND_INTERACTIVE -> {
            if (isWaitingForAuth || withinStartupBootstrapWindow) 2_000L
            else if (isBatterySaverEnabled) 10_000L
            else 6_000L
        }
        RuntimePowerMode.BACKGROUND_SCREEN_ON -> {
            // Keep fast 2.5s detection while user completes OAuth in Chrome Custom Tabs or during initial boot
            if (isWaitingForAuth || withinStartupBootstrapWindow) 2_500L
            else if (isBatterySaverEnabled) 60_000L
            else 30_000L
        }
    }

    /**
     * Computes adaptive active link-quality probe interval in seconds.
     * Returns <= 0 when active Disco/HTTP probing must be suspended to avoid cellular RRC tail drain.
     */
    fun qualityProbeIntervalSec(
        configuredIntervalSec: Int,
        policyMode: RoutingPolicyMode
    ): Int {
        val base = configuredIntervalSec.coerceIn(15, 300)
        return when (mode) {
            RuntimePowerMode.DOZE_SCREEN_OFF -> 0
            RuntimePowerMode.FOREGROUND_INTERACTIVE -> {
                if (isBatterySaverEnabled) max(base * 2, 90) else base
            }
            RuntimePowerMode.BACKGROUND_SCREEN_ON -> {
                if (policyMode == RoutingPolicyMode.MESH_ONLY) 0
                else if (isBatterySaverEnabled) max(base * 6, 300)
                else max(base * 4, 180)
            }
        }
    }
}

/**
 * Tracks Android screen interactive state, MainActivity foreground visibility, and Battery Saver
 * without holding any WakeLock, driving SingScale's zero-idle-wakeup power management.
 */
object AppPowerStateMonitor {

    private var initialized = false
    private var startedMainActivities = 0

    private val _state = MutableStateFlow(RuntimePowerState())
    val state: StateFlow<RuntimePowerState> = _state.asStateFlow()

    @Synchronized
    fun init(application: Application) {
        if (initialized) return
        initialized = true

        val pm = application.getSystemService(PowerManager::class.java)
        _state.value = RuntimePowerState(
            isScreenInteractive = runCatching { pm?.isInteractive ?: true }.getOrDefault(true),
            isAppForeground = false,
            isBatterySaverEnabled = runCatching { pm?.isPowerSaveMode ?: false }.getOrDefault(false)
        )

        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (activity is MainActivity) {
                    startedMainActivities++
                    val interactive = runCatching { pm?.isInteractive ?: true }.getOrDefault(true)
                    _state.update {
                        it.copy(
                            isAppForeground = startedMainActivities > 0,
                            isScreenInteractive = interactive
                        )
                    }
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (activity is MainActivity) {
                    startedMainActivities = (startedMainActivities - 1).coerceAtLeast(0)
                    _state.update {
                        it.copy(isAppForeground = startedMainActivities > 0)
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        _state.update { it.copy(isScreenInteractive = false) }
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        val saver = runCatching { pm?.isPowerSaveMode ?: false }.getOrDefault(false)
                        _state.update {
                            it.copy(
                                isScreenInteractive = true,
                                isBatterySaverEnabled = saver
                            )
                        }
                    }
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                        val saver = runCatching { pm?.isPowerSaveMode ?: false }.getOrDefault(false)
                        _state.update { it.copy(isBatterySaverEnabled = saver) }
                    }
                }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                application,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    internal fun overrideForTest(newState: RuntimePowerState) {
        _state.value = newState
    }
}
