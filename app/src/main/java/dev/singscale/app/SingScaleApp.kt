package dev.singscale.app

import android.app.ActivityManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import dev.singscale.app.core.AppPowerStateMonitor
import dev.singscale.app.core.SingScaleRepository
import dev.singscale.app.widget.SingScaleMiniWidgetProvider
import dev.singscale.app.widget.SingScaleWidgetProvider
import go.Seq
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale

class SingScaleApp : Application() {

    companion object {
        const val CHANNEL_VPN = "singscale_vpn_channel"
        const val CHANNEL_AUTH = "singscale_auth_channel"

        lateinit var instance: SingScaleApp
            private set

        val connectivity: ConnectivityManager
            get() = instance.getSystemService(ConnectivityManager::class.java)

        val wifiManager: WifiManager
            get() = instance.applicationContext.getSystemService(WifiManager::class.java)

        val powerManager: PowerManager
            get() = instance.getSystemService(PowerManager::class.java)

        val notificationManager: NotificationManager
            get() = instance.getSystemService(NotificationManager::class.java)

        fun tailscaleStateDir(context: Context = instance): File =
            File(context.filesDir, "tailscale").also { it.mkdirs() }

        /**
         * Inspects Tailscale's FileStore (`tailscaled.state`) in app-private internal storage
         * to determine whether a valid, non-logged-out PrivateNodeKey already exists on disk.
         */
        fun hasPersistedTailscaleState(context: Context): Boolean = runCatching {
            val stateFile = File(tailscaleStateDir(context), "tailscaled.state")
            if (!stateFile.isFile || stateFile.length() < 32L) return false
            val raw = stateFile.readText()
            val obj = Json.parseToJsonElement(raw).jsonObject
            if (obj.isEmpty()) return false

            fun decodeEntry(key: String): String {
                val b64 = obj[key]?.jsonPrimitive?.content?.trim().orEmpty()
                if (b64.isBlank()) return ""
                return String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            }

            val currentProfileId = decodeEntry("_current-profile").trim()
            if (currentProfileId.isNotBlank()) {
                val prefsJson = decodeEntry(currentProfileId)
                if (prefsJson.contains("\"PrivateNodeKey\":\"privkey:") &&
                    !prefsJson.contains("\"LoggedOut\":true")
                ) {
                    return true
                }
            }

            obj.keys.any { k ->
                if (!k.startsWith("profile-")) false
                else {
                    val prefsJson = decodeEntry(k)
                    prefsJson.contains("\"PrivateNodeKey\":\"privkey:") &&
                        !prefsJson.contains("\"LoggedOut\":true")
                }
            }
        }.getOrDefault(false)

        fun clearPersistedTailscaleState(context: Context) {
            runCatching {
                val dir = File(context.filesDir, "tailscale")
                File(dir, "tailscaled.state").delete()
                File(dir, "profile-data").deleteRecursively()
                context.getExternalFilesDir(null)?.let { ext ->
                    File(ext, "runtime/tailscale/tailscaled.state").delete()
                }
            }
        }
    }

    private val appExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("SingScaleApp", "Unhandled coroutine exception in appScope", throwable)
    }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + appExceptionHandler)

    @Volatile
    private var libboxInitialized = false

    override fun onCreate() {
        super.onCreate()
        instance = this

        createNotificationChannels()
        AppPowerStateMonitor.init(this)
        migrateLegacyTailscaleStateIfNeeded()
        SingScaleRepository.init(this)
        // Defer loading 63.5MB libbox.so until MainActivity or SingScaleVpnService actually starts,
        // keeping idle Widget / Tile broadcast wakeups lightweight (~20MB instead of ~86MB RSS).
        observeWidgetUpdates()
    }

    @Synchronized
    fun ensureLibboxSetup() {
        if (libboxInitialized) return
        setupLibbox()
        libboxInitialized = true
    }

    private fun migrateLegacyTailscaleStateIfNeeded() {
        runCatching {
            val internalDir = tailscaleStateDir(this)
            val internalState = File(internalDir, "tailscaled.state")
            if (internalState.isFile && hasPersistedTailscaleState(this)) return@runCatching

            val legacyDir = getExternalFilesDir(null)?.let { File(it, "runtime/tailscale") } ?: return@runCatching
            val legacyState = File(legacyDir, "tailscaled.state")
            if (legacyState.isFile && legacyState.length() > 4L) {
                legacyDir.copyRecursively(internalDir, overwrite = true)
            }
        }
    }

    private fun setupLibbox() {
        runCatching {
            Seq.setContext(this)
            Libbox.setLocale(Locale.getDefault().toLanguageTag().replace("-", "_"))

            val baseDir = filesDir.also { it.mkdirs() }
            val workingDir = File(filesDir, "runtime").also { it.mkdirs() }
            val tempDir = File(cacheDir, "libbox_tmp").also { it.mkdirs() }

            val totalRamBytes = runCatching {
                val am = getSystemService(ActivityManager::class.java)
                val memInfo = ActivityManager.MemoryInfo()
                am?.getMemoryInfo(memInfo)
                memInfo.totalMem
            }.getOrDefault(0L)
            // Soft memory budget protects background VPN from Android LMK / OOM kills
            val goMemoryBudgetBytes = if (totalRamBytes >= 6L * 1024L * 1024L * 1024L) {
                192L * 1024L * 1024L
            } else {
                144L * 1024L * 1024L
            }

            val options = SetupOptions().apply {
                basePath = baseDir.absolutePath
                workingPath = workingDir.absolutePath
                tempPath = tempDir.absolutePath
                fixAndroidStack = true
                logMaxLines = 250
                debug = BuildConfig.DEBUG
                crashReportSource = "SingScale"
                appVersion = BuildConfig.VERSION_CODE.toString()
                appMarketingVersion = BuildConfig.VERSION_NAME
                oomKillerEnabled = true
                oomKillerDisabled = false
                oomMemoryLimit = goMemoryBudgetBytes
            }
            Libbox.setup(options)
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        val vpnChannel = NotificationChannel(
            CHANNEL_VPN,
            "SingScale 组网隧道状态",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示 SingScale Tailscale 组网与动态路由运行状态"
            setShowBadge(false)
        }
        val authChannel = NotificationChannel(
            CHANNEL_AUTH,
            "Tailscale 登录与节点提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "用于弹出 Tailscale 交互式登录链接与节点故障安全提醒"
        }
        nm.createNotificationChannel(vpnChannel)
        nm.createNotificationChannel(authChannel)
    }

    private fun observeWidgetUpdates() {
        // 1x1 Mini Jewel Widget only depends on TunnelStatus and only updates when screen is interactive
        appScope.launch {
            combine(
                SingScaleRepository.state.map { it.tunnelStatus }.distinctUntilChanged(),
                AppPowerStateMonitor.state.map { it.isScreenInteractive }.distinctUntilChanged()
            ) { status, interactive -> status to interactive }
                .filter { (_, interactive) -> interactive }
                .map { (status, _) -> status }
                .distinctUntilChanged()
                .collect {
                    SingScaleMiniWidgetProvider.updateMiniWidgets(this@SingScaleApp)
                }
        }

        // 4x2 Status Card Widget buckets latency to 15ms steps to prevent jitter IPC and sleeps when screen is OFF
        appScope.launch {
            combine(
                SingScaleRepository.state.map { state ->
                    WidgetSnapshotKey(
                        status = state.tunnelStatus.name,
                        exitId = state.activeExitPeerId,
                        relayId = state.activeRelayPeerId,
                        policy = state.policyMode.name,
                        tier = state.dynamicTier.name,
                        latencyBucket = if (state.activeQuality.reachable && state.activeQuality.latencyMs > 0) {
                            (state.activeQuality.latencyMs / 15.0).toInt()
                        } else -1,
                        path = state.activeQuality.pathType.name,
                        tailnet = state.tailnetName
                    )
                }.distinctUntilChanged(),
                AppPowerStateMonitor.state.map { it.isScreenInteractive }.distinctUntilChanged()
            ) { key, interactive -> key to interactive }
                .filter { (_, interactive) -> interactive }
                .map { (key, _) -> key }
                .distinctUntilChanged()
                .collect {
                    SingScaleWidgetProvider.updateCardWidgetsOnly(this@SingScaleApp)
                }
        }
    }

    private data class WidgetSnapshotKey(
        val status: String,
        val exitId: String,
        val relayId: String,
        val policy: String,
        val tier: String,
        val latencyBucket: Int,
        val path: String,
        val tailnet: String
    )
}
