package top.uwu.mikubox.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.miku.ray.contracts.ServiceControl
import com.miku.ray.core.CoreServiceManager
import top.uwu.mikubox.R
import top.uwu.mikubox.core.MihomoConfigStore
import top.uwu.mikubox.core.CoreOverrides
import top.uwu.mikubox.core.MihomoCore
import top.uwu.mikubox.core.MihomoCoreSettings
import top.uwu.mikubox.core.MihomoDnsSettings
import top.uwu.mikubox.core.AndroidVpnSettings
import top.uwu.mikubox.core.MikuRayProfileSync
import top.uwu.mikubox.profile.MihomoProfileStore
import top.uwu.mikubox.profile.MihomoTrafficStore

/**
 * The MikuBox VPN service.
 *
 * Full lifecycle: consent → foreground notification → TUN establishment →
 * hand the TUN file descriptor to the Mihomo core → stop. The TUN installs a
 * catch-all route (0.0.0.0/0 and ::/0) so all traffic is captured and routed
 * through the core, minus this app's own UID to avoid feeding Mihomo's own
 * sockets back into the tunnel.
 */
class MikuVpnService : VpnService(), ServiceControl {

    /**
     * The tunnel descriptor, owned by the core once [MihomoCore.start] accepted
     * it. [MihomoCore.NO_TUN] while nothing is connected.
     */
    @Volatile
    private var tunFd: Int = MihomoCore.NO_TUN
    private var lastTunError: Throwable? = null

    /** Core startup (config parse + GeoSite loads) can take seconds; keep it off the main thread. */
    private val startExecutor = CoreServiceRuntime.executor
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    private var starting = false

    /**
     * Set while a start request is outstanding; cleared when one begins or when
     * the user disconnects. A start that meets a tunnel still registered from a
     * previous run is replayed once the teardown queue drained — returning there
     * used to drop the request and leave the VPN down while the UI reported the
     * service as restarting.
     */
    private var startRequested = false
    private var startReplayQueued = false

    private val checkpointHandler = Handler(Looper.getMainLooper())

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private val wakeLockLock = Any()

    /**
     * Keeps the CPU awake while the tunnel runs, when the user asked for it: a
     * suspended device stops forwarding traffic until something wakes it again.
     * Both accessors take the same lock because start runs on the executor while
     * settings changes call in from the main thread.
     */
    private fun acquireWakeLock() {
        synchronized(wakeLockLock) {
            if (!AndroidVpnSettings.keepAwake(this) || wakeLock != null) return
            val manager = getSystemService(android.os.PowerManager::class.java) ?: return
            wakeLock = runCatching {
                manager.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "$packageName:vpn",
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
        }
    }

    private fun releaseWakeLock() {
        synchronized(wakeLockLock) {
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            wakeLock = null
        }
    }

    /**
     * Answers the vendored layer's own service contract, so its screens, widget
     * and traffic loop can reach the service that actually runs the tunnel —
     * they ask [CoreServiceManager] for it, and MikuRay's own VPN service used
     * to be the answer.
     */
    override fun onCreate() {
        super.onCreate()
        CoreServiceManager.serviceControl = this
    }

    override fun getService(): Service = this

    override fun startService() {
        startVpn()
    }

    override fun stopService() {
        stopVpn()
    }

    override fun vpnProtect(socket: Int): Boolean = protect(socket)

    /** The platform call behind it reports what the network did; so does this. */
    override fun setUnderlyingNetworks(networks: Array<Network>?): Boolean =
        super<VpnService>.setUnderlyingNetworks(networks)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                startRequested = false
                startReplayQueued = false
                Log.i(TAG, "stop requested")
                stopVpn()
                return START_NOT_STICKY
            }

            ACTION_RESTART -> {
                restartVpn()
                return START_STICKY
            }

            ACTION_REFRESH -> {
                // Settings the running service can adopt without touching the core.
                applyRuntimeSettings()
                return START_STICKY
            }
        }
        if (!running && !starting) {
            startRequested = true
            startVpn()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        CoreServiceManager.clearServiceControl(this)
        super.onDestroy()
    }

    override fun onRevoke() {
        // System or another VPN app revoked our permission.
        stopVpn()
        super.onRevoke()
    }

    private fun startVpn() {
        if (starting) return
        if (tunFd != MihomoCore.NO_TUN) {
            // A tunnel is still registered: either it already serves this request,
            // or a teardown that was asked for a moment ago is queued behind it.
            // Replay the request after that queue drained instead of dropping it.
            if (startRequested && !startReplayQueued) {
                Log.i(TAG, "start deferred, tunnel still registered (running=$running)")
                startReplayQueued = true
                startExecutor.execute {
                    checkpointHandler.post {
                        startReplayQueued = false
                        if (startRequested) startVpn()
                    }
                }
            }
            return
        }
        startRequested = false
        starting = true
        val request = generation.incrementAndGet()
        MikuProxyService.stop(this)
        lastTunError = null
        // The foreground notification must appear promptly; the heavy work runs after it.
        startForegroundNotification()
        startExecutor.execute {
            if (generation.get() != request) return@execute
            // Tracked so a failure between establish() and the core taking over
            // can still close the detached descriptor instead of leaking it.
            var fd: Int = MihomoCore.NO_TUN
            var coreAccepted = false
            try {
                MihomoCoreSettings.prepareMixedPort(this)
                fd = establishTun() ?: run {
                    reportStartFailure(lastTunError?.message.orEmpty())
                    checkpointHandler.post { if (generation.get() == request) stopVpn() }
                    return@execute
                }
                val profile = MihomoProfileStore.selected(this)
                val config = profile?.config ?: MihomoConfigStore.activeConfig(this)
                require(config.isNotBlank()) { "Update the subscription before connecting" }
                // The routing rules the core is about to be given come from this
                // configuration, so the screen's list is refreshed against it —
                // including the rules the user switched off there.
                val startResult = CoreServiceRuntime.start(this, { MihomoTrafficStore.finish(this) }) { MihomoCore.start(
                    this,
                    config,
                    fd,
                    MihomoDnsSettings.effectiveOverride(this, config),
                    MihomoCoreSettings.overridesJson(this, AndroidVpnSettings.mtu(this)),
                ) }
                if (startResult.isFailure) {
                    closeDetachedTun(fd)
                    val failure = startResult.exceptionOrNull()
                    // The message alone is not enough to place a Kotlin failure;
                    // keep the trace in logcat for the diagnostics export.
                    runCatching { Log.e(TAG, "core start threw", failure) }
                    reportStartFailure(failure?.message.orEmpty())
                    checkpointHandler.post { if (generation.get() == request) stopVpn() }
                    return@execute
                }
                coreAccepted = true
                // Native startup cannot be interrupted. Its result may already be obsolete.
                if (generation.get() != request) {
                    CoreServiceRuntime.stop(this)
                    return@execute
                }
                top.uwu.mikubox.core.MikuRayRoutingMode.onStarted(this, profile?.id)
                MihomoTrafficStore.begin(profile)
                checkpointHandler.post {
                    if (generation.get() != request) return@post
                    starting = false
                    tunFd = fd
                    running = true
                    startedAtMillis = System.currentTimeMillis()
                    // What the tunnel was actually built from: the settings that
                    // reach it come from the ported screens, so the line is what
                    // tells a switch apart from a switch that does nothing.
                    val address = AndroidVpnSettings.interfaceAddress(this@MikuVpnService)
                    Log.i(
                        TAG,
                        "tunnel up (fd=$fd, mtu=${AndroidVpnSettings.mtu(this@MikuVpnService)}" +
                            ", addr=${address.ipv4Client}" +
                            ", dns=${AndroidVpnSettings.tunDnsServers(this@MikuVpnService)}" +
                            ", bypassLan=${AndroidVpnSettings.bypassLan(this@MikuVpnService)}" +
                            "(${AndroidVpnSettings.bypassLanRaw(this@MikuVpnService)})" +
                            ", apps=${AndroidVpnSettings.perAppMode(this@MikuVpnService)})",
                    )
                    // The vendored screens hear about the tunnel the same way they
                    // used to hear about MikuRay's own service.
                    CoreServiceManager.announceTunnelStarted(this@MikuVpnService)
                    checkpointHandler.post(rowTrafficRefresh)
                    checkpointHandler.post(trafficCheckpoint)
                    acquireWakeLock()
                }
            } catch (error: Throwable) {
                if (!coreAccepted && fd != MihomoCore.NO_TUN) closeDetachedTun(fd)
                reportStartFailure(error.message.orEmpty())
                checkpointHandler.post { if (generation.get() == request) stopVpn() }
            }
        }
    }

    /**
     * Folds the traffic measured so far into the profile's stored totals while
     * the tunnel runs. Without it a session that ends abruptly — the process
     * being killed, or an update while connected — would lose everything it
     * moved, because only a clean stop used to write the numbers out.
     */
    private val trafficCheckpoint = object : Runnable {
        override fun run() {
            if (!running) return
            // JNI reads plus a prefs commit stay off the main thread; the
            // executor only runs start/stop around these while the tunnel is up,
            // so checkpoints and the final fold still happen in order.
            runCatching {
                val request = generation.get()
                startExecutor.execute {
                    if (generation.get() == request) {
                        MihomoTrafficStore.checkpoint(this@MikuVpnService)
                        // The rows' delay belongs to the running node, so it is
                        // refreshed on the same beat that folds traffic.
                        MikuRayProfileSync.refreshRowDetails(this@MikuVpnService)
                    }
                }
            }
            checkpointHandler.postDelayed(this, CHECKPOINT_INTERVAL_MS)
        }
    }

    /**
     * Keeps the running profile's row moving.
     *
     * The app's own accounting is the source of the numbers — the core's session
     * counters count every connection, where MikuRay's loop could only see the
     * ones still open at each sample — and the row is told to read them again
     * through the message the vendored list already listens for.
     */
    private val rowTrafficRefresh = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching {
                val request = generation.get()
                startExecutor.execute {
                    if (generation.get() == request) {
                        MikuRayProfileSync.refreshRowTraffic(this@MikuVpnService)
                    }
                }
            }
            checkpointHandler.postDelayed(this, ROW_REFRESH_INTERVAL_MS)
        }
    }

    private fun reportStartFailure(detail: String) {
        val message = detail.ifBlank { getString(R.string.mihomo_start_failed) }
        Log.e(TAG, "VPN start failed: $message")
        sendBroadcast(
            Intent(ACTION_VPN_START_FAILED)
                .setPackage(packageName)
                .putExtra(EXTRA_FAILURE_DETAIL, message),
        )
        // The vendored screens start their connection indicator from these
        // messages, so a failure has to reach them or the UI waits forever.
        runCatching { CoreServiceManager.announceStartFailure(this, message) }
    }

    /**
     * Applies changed core settings by restarting the tunnel in place.
     *
     * The core reads its configuration at start, so a switch that changes it only
     * takes effect after a reload. The interface is recreated and the foreground
     * notification stays up, so the user sees a short interruption instead of
     * having to reconnect by hand.
     */
    private fun restartVpn() {
        if (tunFd == MihomoCore.NO_TUN) return
        Log.i(TAG, "restarting the core for changed settings")
        stopCore()
        // Both calls run on the same executor, so the start can never overtake the
        // stop and the old core is always shut down first.
        startVpn()
    }

    /** Re-reads the settings the tunnel can honour while it runs. */
    private fun applyRuntimeSettings() {
        if (tunFd == MihomoCore.NO_TUN) return
        releaseWakeLock()
        acquireWakeLock()
    }

    /** Tears the tunnel and the core down, leaving the service itself alive. */
    private fun stopCore() {
        // Read before the flags are cleared: a stop that had no tunnel to stop
        // must not tell the UI that a connection ended.
        val hadTunnel = tunFd != MihomoCore.NO_TUN
        generation.incrementAndGet()
        starting = false
        running = false
        startedAtMillis = 0L
        // Nothing left to poll once the core is going down, and a beat that
        // outlived the tunnel would keep asking a stopped core for counters.
        checkpointHandler.removeCallbacks(rowTrafficRefresh)
        if (hadTunnel) CoreServiceManager.announceTunnelStopped(this)
        checkpointHandler.removeCallbacks(trafficCheckpoint)
        releaseWakeLock()
        // The core owns the tunnel descriptor and closes it while shutting down,
        // so this only drops the reference. Folding the traffic has to happen
        // before the core stops (it reads the live counters).
        tunFd = MihomoCore.NO_TUN
        // The process-wide queue outlives services; ownership prevents a late
        // onDestroy from shutting down a newer service's core.
        runCatching {
            startExecutor.execute {
                runCatching { CoreServiceRuntime.stop(this) }
            }
        }
    }

    private fun stopVpn() {
        stopCore()
        stopForegroundCompat()
        stopSelf()
    }

    /**
     * Establishes the tunnel and hands its descriptor to the core.
     *
     * [ParcelFileDescriptor.detachFd] is what makes the handover safe: the core
     * wraps the descriptor in a Go file object and closes it when the tunnel
     * stops, and Go closes it with a raw syscall. Keeping Java as an owner as
     * well meant both sides closed the same descriptor; the second close landed
     * on an fd number the GPU driver had already been given, and bionic aborted
     * the process with an fdsan error right when the user disconnected.
     */
    private fun establishTun(): Int? {
        // Every value here comes from the screen that owns it, so the switch and
        // the tunnel agree (see AndroidVpnSettings).
        val appMode = AndroidVpnSettings.perAppMode(this)
        val address = AndroidVpnSettings.interfaceAddress(this)
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(AndroidVpnSettings.mtu(this))
            .addAddress(address.ipv4Client, PRIVATE_VLAN4_PREFIX)
            .allowBypass()
        // Without a resolver of its own Android answers from the underlying
        // network (or refuses to answer at all, depending on the vendor), so the
        // tunnel advertises an address inside itself and the core hijacks port 53.
        // The setting may name resolvers instead; they are reached through the
        // tunnel either way, so the hijack still answers them.
        AndroidVpnSettings.tunDnsServers(this).takeIf { it.isNotEmpty() }
            ?.forEach { builder.addDnsServer(it) }
            ?: builder.addDnsServer(address.ipv4Router)
        if (AndroidVpnSettings.bypassLan(this)) {
            // Leave the LAN alone: the routes cover public space, and private
            // ranges go out over the underlying network.
            AndroidVpnSettings.publicRoutes().forEach { route ->
                val parts = route.split('/')
                if (parts.size == 2) {
                    runCatching { builder.addRoute(parts[0], parts[1].toInt()) }
                }
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }
        if (MihomoCoreSettings.ipv6(this)) {
            builder.addAddress(address.ipv6Client, PRIVATE_VLAN6_PREFIX)
            builder.addRoute("::", 0)
            val v6Dns = AndroidVpnSettings.tunDnsServers(this).filter { it.contains(':') }
            if (v6Dns.isEmpty()) builder.addDnsServer(address.ipv6Router) else v6Dns.forEach { builder.addDnsServer(it) }
        }
        // The core shares this application's UID. Excluding it prevents
        // Mihomo's own sockets from being fed back into the VPN TUN. In
        // allow-list mode it is implicitly excluded by not being allowed.
        if (appMode != AndroidVpnSettings.PerAppMode.ONLY_SELECTED) {
            builder.addDisallowedApplication(packageName)
        }
        when (appMode) {
            AndroidVpnSettings.PerAppMode.ALL -> Unit

            AndroidVpnSettings.PerAppMode.ONLY_SELECTED -> {
                val packages = AndroidVpnSettings.perAppPackages(this)
                if (packages.isEmpty()) {
                    // A list that selects nothing would tunnel everything, so fall
                    // back to excluding only this app and letting the rest through.
                    builder.addDisallowedApplication(packageName)
                } else {
                    packages.forEach { runCatching { builder.addAllowedApplication(it) } }
                }
            }

            AndroidVpnSettings.PerAppMode.BYPASS_SELECTED -> {
                AndroidVpnSettings.perAppPackages(this).forEach { runCatching { builder.addDisallowedApplication(it) } }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && AndroidVpnSettings.appendHttpProxy(this)) {
            runCatching {
                builder.setHttpProxy(
                    android.net.ProxyInfo.buildDirectProxy(
                        HTTP_PROXY_HOST,
                        com.miku.ray.handler.SettingsManager.getHttpPort(),
                    ),
                )
            }
        }
        builder.setConfigureIntent(configurePendingIntent())
        return try {
            builder.establish()?.detachFd()
        } catch (t: Throwable) {
            lastTunError = t
            null
        }
    }

    /**
     * Closes a descriptor the core never took over. [ParcelFileDescriptor.adoptFd]
     * re-attaches ownership so the close is clean: a detached fd has no owner as
     * far as fdsan is concerned.
     */
    private fun closeDetachedTun(fd: Int) {
        runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
    }

    // region notification

    private fun startForegroundNotification() {
        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, MikuVpnService::class.java).setAction(ACTION_STOP),
            pendingFlags(),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            // The monochrome silhouette renders cleanly in the status bar; the
            // full-colour launcher icon becomes an opaque blob there.
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.vpn_notification_running))
            .setContentIntent(configurePendingIntent())
            .addAction(0, getString(R.string.vpn_action_stop), stopIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun configurePendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        // Tapping the notification opens the ported home screen.
        Intent(this, com.miku.ray.ui.main.MainActivity::class.java),
        pendingFlags(),
    )

    private fun pendingFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    // endregion

    companion object {
        const val ACTION_STOP = "top.uwu.mikubox.action.STOP_VPN"

        /** Reloads the core in place so changed settings apply immediately. */
        const val ACTION_RESTART = "top.uwu.mikubox.action.RESTART_VPN"

        /** Picks up settings the running tunnel can adopt as is. */
        const val ACTION_REFRESH = "top.uwu.mikubox.action.REFRESH_VPN"

        const val ACTION_VPN_START_FAILED = "top.uwu.mikubox.action.VPN_START_FAILED"
        const val EXTRA_FAILURE_DETAIL = "failure_detail"

        private const val TAG = "MikuBox"

        private const val CHANNEL_ID = "miku_vpn_status"
        private const val NOTIFICATION_ID = 1

        /** How often the running session's traffic is written into the profile's totals. */
        private const val CHECKPOINT_INTERVAL_MS = 30_000L

        /** How often the running profile's row re-reads its totals. */
        private const val ROW_REFRESH_INTERVAL_MS = 3_000L

        /** Where the platform points apps when the HTTP-proxy setting is on. */
        private const val HTTP_PROXY_HOST = "127.0.0.1"

        // The address pair and its resolvers come from the ported VPN screen's own
        // preset table (see AndroidVpnSettings.interfaceAddress); only the prefix
        // lengths are this app's, and they are what the table is built for.
        private const val PRIVATE_VLAN4_PREFIX = 30
        private const val PRIVATE_VLAN6_PREFIX = 126

        /** Coarse running flag for the UI/controller to reflect state. */
        @Volatile
        var running: Boolean = false
            private set

        /** Wall-clock start of the current session, 0 while stopped. */
        @Volatile
        var startedAtMillis: Long = 0L
            private set

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, MikuVpnService::class.java),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MikuVpnService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
