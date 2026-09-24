package com.mikubox.mihomo.service

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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.miku.ray.contracts.ServiceControl
import com.miku.ray.core.CoreServiceManager
import com.miku.ray.extension.toSpeedString
import com.miku.ray.extension.toTrafficString
import com.miku.ray.handler.SpeedtestManager
import com.miku.ray.handler.TrafficController
import com.miku.ray.util.LogUtil
import com.mikubox.mihomo.R
import kotlinx.coroutines.launch
import com.mikubox.mihomo.core.MihomoConfigStore
import com.mikubox.mihomo.core.CoreOverrides
import com.mikubox.mihomo.core.MihomoCore
import com.mikubox.mihomo.core.MihomoCoreSettings
import com.mikubox.mihomo.core.MihomoDnsSettings
import com.mikubox.mihomo.core.AndroidVpnSettings
import com.mikubox.mihomo.core.MikuRayProfileSync
import com.mikubox.mihomo.profile.MihomoProfileStore
import com.mikubox.mihomo.profile.MihomoTrafficStore

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
     * The original tunnel identifier, retained only as a connected-state marker.
     * It is closed after startup; the native listener owns a duplicate.
     * [MihomoCore.NO_TUN] while nothing is connected.
     */
    @Volatile
    private var tunFd: Int = MihomoCore.NO_TUN
    private var lastTunError: Throwable? = null

    /** Core startup (config parse + GeoSite loads) can take seconds; keep it off the main thread. */
    private val startExecutor = CoreServiceRuntime.executor
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    private var starting = false

    /**
     * Set for the whole teardown, cleared when the next start begins. Stop
     * reaches this service twice — the caller and then onDestroy through
     * stopSelf — and the second pass must not repeat it: it would double the
     * traffic controller's shutdown, advance the generation under the queued
     * core-stop task and churn the connection status again.
     */
    @Volatile
    private var stopping = false

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

    /**
     * Live notification content: speed, session totals and the exit IP.
     *
     * [notificationStatsUpdate] samples the core's counters on the executor,
     * derives a rate against the previous sample, and re-posts the foreground
     * notification. The exit IP is probed far less often - it costs a network
     * round trip per endpoint - and is cached between beats.
     */
    private val statsScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    @Volatile
    private var lastUpload = 0L

    @Volatile
    private var lastDownload = 0L

    @Volatile
    private var lastSampleAt = 0L

    @Volatile
    private var exitIpDisplay: String? = null

    private var exitIpFetchedAt = 0L

    /** Consecutive failed probes; drives the retry backoff while IP is unknown. */
    private var exitIpFailures = 0

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
            vpnAction(packageName, ACTION_STOP) -> {
                startRequested = false
                startReplayQueued = false
                stopVpn()
                return START_NOT_STICKY
            }

            vpnAction(packageName, ACTION_RESTART) -> {
                restartVpn()
                return START_STICKY
            }

            vpnAction(packageName, ACTION_REFRESH) -> {
                // Settings the running service can adopt without touching the core.
                applyRuntimeSettings()
                return START_STICKY
            }
        }
        if (!running && !starting) {
            startRequested = true
            startVpn()
        } else if (!running) {
            // A start is already in flight (typically the automatic reconnect
            // after the process was killed with the tunnel up). Remember the
            // request instead of dropping it: the user's tap must survive the
            // in-flight attempt, including its failure — a silent drop here is
            // what made the connect button look dead right after a kill.
            startRequested = true
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
                LogUtil.i(message = "start deferred, tunnel still registered (running=$running)")
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
        stopping = false
        ConnectionStatus.update(this, ConnectionStatus.Phase.CONNECTING)
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
            var descriptorClosed = false
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
                val startResult = CoreServiceRuntime.start(this, { MihomoTrafficStore.finish(this) }) {
                    com.mikubox.mihomo.core.AndroidNetworkBridge.start(this)
                    MihomoCore.start(
                    this,
                    config,
                    fd,
                    MihomoDnsSettings.effectiveOverride(this, config),
                    MihomoCoreSettings.overridesJson(
                        this, AndroidVpnSettings.mtu(this),
                        "${AndroidVpnSettings.interfaceAddress(this).ipv4Client}/$PRIVATE_VLAN4_PREFIX",
                        if (AndroidVpnSettings.ipv6Inbound(this)) "${AndroidVpnSettings.interfaceAddress(this).ipv6Client}/$PRIVATE_VLAN6_PREFIX" else null,
                        profileId = profile?.id,
                    ),
                ) }
                closeDetachedTun(fd)
                descriptorClosed = true
                if (startResult.isFailure) {
                    val failure = startResult.exceptionOrNull()
                    // The message alone is not enough to place a Kotlin failure;
                    // keep the trace in the log for the diagnostics export.
                    runCatching { failure?.let { LogUtil.e(message = "core start threw", throwable = it) } }
                    reportStartFailure(failure?.message.orEmpty())
                    checkpointHandler.post { if (generation.get() == request) stopVpn() }
                    return@execute
                }
                // Native startup cannot be interrupted. Its result may already be obsolete.
                if (generation.get() != request) {
                    CoreServiceRuntime.stop(this)
                    return@execute
                }
                com.mikubox.mihomo.core.MikuRayRoutingMode.onStarted(this, profile?.id)
                MihomoTrafficStore.begin(profile)
                checkpointHandler.post {
                    if (generation.get() != request) return@post
                    starting = false
                    tunFd = fd
                    running = true
                    ConnectionStatus.update(this@MikuVpnService, ConnectionStatus.Phase.CONNECTED)
                    startedAtMillis = System.currentTimeMillis()
                    // What the tunnel was actually built from: the settings that
                    // reach it come from the ported screens, so the line is what
                    // tells a switch apart from a switch that does nothing.
                    val address = AndroidVpnSettings.interfaceAddress(this@MikuVpnService)
                    LogUtil.i(
                        message =
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
                    TrafficController.start()
                    // Fresh baselines for the notification's speed line, and the
                    // first beat lands immediately so the readout is not blank.
                    lastUpload = 0L
                    lastDownload = 0L
                    lastSampleAt = 0L
                    exitIpDisplay = null
                    exitIpFetchedAt = 0L
                    exitIpFailures = 0
                    checkpointHandler.post(notificationStatsUpdate)
                    checkpointHandler.post(rowTrafficRefresh)
                    checkpointHandler.post(trafficCheckpoint)
                    acquireWakeLock()
                }
            } catch (error: Throwable) {
                if (!descriptorClosed && fd != MihomoCore.NO_TUN) closeDetachedTun(fd)
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

    /**
     * Re-posts the foreground notification with live numbers.
     *
     * Collapsed, it shows the current up/down speed; expanded (InboxStyle) it
     * lists the speed, the session totals and the exit IP on their own lines,
     * which wrap and truncate far better than one packed sentence. The JNI
     * counter read runs on the executor, the re-post on the main thread; a
     * generation change between the two means the tunnel these numbers
     * describe is already gone, and the update is dropped.
     */
    private val notificationStatsUpdate = object : Runnable {
        override fun run() {
            if (!running) return
            val request = generation.get()
            startExecutor.execute {
                if (generation.get() != request) return@execute
                runCatching {
                    val traffic = MihomoCore.traffic()
                    maybeRefreshExitIp(request)
                    checkpointHandler.post {
                        if (generation.get() != request || !running) return@post
                        updateStatsNotification(traffic, request)
                    }
                }
            }
            checkpointHandler.postDelayed(this, NOTIFICATION_STATS_INTERVAL_MS)
        }
    }

    private fun maybeRefreshExitIp(request: Int) {
        val now = SystemClock.elapsedRealtime()
        // While no reading exists the probe retries with exponential backoff
        // (15 s, 30 s, 60 s, capped at the normal 5-minute refresh) so a slow
        // start - the core still warming up, one endpoint hanging - recovers
        // on its own instead of leaving the IP line blank for five minutes.
        val cooldown = if (exitIpDisplay == null) {
            minOf(EXIT_IP_RETRY_MS shl exitIpFailures.coerceAtMost(4), EXIT_IP_REFRESH_MS)
        } else {
            EXIT_IP_REFRESH_MS
        }
        if (now - exitIpFetchedAt < cooldown) return
        exitIpFetchedAt = now
        statsScope.launch {
            // Through the tunnel: the probe exits where user traffic exits, and
            // it already knows how to race endpoints and tolerate one hanging.
            // A failed probe keeps the previous reading instead of blanking it.
            val fetched = runCatching { SpeedtestManager.getRemoteIPInfo(direct = false) }.getOrNull()
            if (generation.get() != request) return@launch
            if (fetched != null) {
                exitIpDisplay = fetched
                exitIpFailures = 0
            } else {
                exitIpFailures++
            }
        }
    }

    private fun updateStatsNotification(traffic: MihomoCore.Traffic, request: Int) {
        val now = SystemClock.elapsedRealtime()
        val sample = notificationSpeed(
            lastUpload, lastDownload, lastSampleAt,
            traffic.uploadTotal, traffic.downloadTotal, now,
        )
        if (generation.get() != request) return
        lastUpload = traffic.uploadTotal
        lastDownload = traffic.downloadTotal
        lastSampleAt = now
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(
                contentText = getString(
                    R.string.vpn_notification_speeds,
                    sample.upBps.toSpeedString(),
                    sample.downBps.toSpeedString(),
                ),
                statsLines = listOf(
                    getString(R.string.vpn_notification_session,
                        traffic.uploadTotal.toTrafficString(),
                        traffic.downloadTotal.toTrafficString()),
                    getString(R.string.vpn_notification_exit_ip, exitIpDisplay ?: "…"),
                ),
            ),
        )
    }

    private fun reportStartFailure(detail: String) {
        val message = detail.ifBlank { getString(R.string.mihomo_start_failed) }
        LogUtil.e(message = "VPN start failed: $message")
        sendBroadcast(
            Intent(vpnAction(packageName, ACTION_VPN_START_FAILED))
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
        LogUtil.i(message = "restarting the core for changed settings")
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
        val stopGeneration = generation.incrementAndGet()
        val stoppingRevision = ConnectionStatus.update(this, ConnectionStatus.Phase.DISCONNECTING)
        starting = false
        running = false
        startedAtMillis = 0L
        // Nothing left to poll once the core is going down, and a beat that
        // outlived the tunnel would keep asking a stopped core for counters.
        checkpointHandler.removeCallbacks(rowTrafficRefresh)
        checkpointHandler.removeCallbacks(notificationStatsUpdate)
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
                if (generation.get() == stopGeneration) com.mikubox.mihomo.core.AndroidNetworkBridge.stop(this)
                checkpointHandler.post {
                    if (generation.get() == stopGeneration && ConnectionStatus.revision == stoppingRevision) ConnectionStatus.update(this, ConnectionStatus.Phase.DISCONNECTED)
                }
            }
        }
    }

    private fun stopVpn() {
        // Reached twice per teardown — the caller and then onDestroy through
        // stopSelf — and the second pass must be a no-op (see [stopping]).
        if (stopping) return
        stopping = true
        LogUtil.i(message = "stop requested")
        TrafficController.stop()
        stopCore()
        stopForegroundCompat()
        stopSelf()
    }

    /**
     * Establishes the tunnel and lends its detached descriptor during startup.
     * The native listener duplicates it before adoption; Java closes the original
     * after startup regardless of the result. Thus failure cleanup and stop can
     * never close a descriptor still owned by the other runtime.
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
        if (AndroidVpnSettings.allowBypass(this)) builder.allowBypass()
        // Without a resolver of its own Android answers from the underlying
        // network (or refuses to answer at all, depending on the vendor), so the
        // tunnel advertises an address inside itself and the core hijacks port 53.
        // The setting may name resolvers instead; they are reached through the
        // tunnel either way, so the hijack still answers them.
        AndroidVpnSettings.tunDnsServers(this).takeIf { it.isNotEmpty() }
            ?.forEach { builder.addDnsServer(it) }
            ?: builder.addDnsServer(if (CoreOverrides.dnsHijack(this).isBlank()) "1.1.1.1" else address.ipv4Router)
        com.mikubox.mihomo.core.VpnRoutes.selected(this,
            MihomoProfileStore.selected(this)?.config.orEmpty(), AndroidVpnSettings.ipv6Inbound(this)).forEach { route ->
            builder.addRoute(route.substringBefore('/'), route.substringAfter('/').toInt())
        }
        if (AndroidVpnSettings.ipv6Inbound(this)) {
            builder.addAddress(address.ipv6Client, PRIVATE_VLAN6_PREFIX)
            val v6Dns = AndroidVpnSettings.tunDnsServers(this).filter { it.contains(':') }
            if (v6Dns.isEmpty()) builder.addDnsServer(if (CoreOverrides.dnsHijack(this).isBlank()) "2606:4700:4700::1111" else address.ipv6Router) else v6Dns.forEach { builder.addDnsServer(it) }
        }
        // Mixed/system TCP uses an Android kernel listener whose replies must
        // return through the TUN. Excluding our UID also excludes those replies
        // and silently blackholes TCP. Protect only actual outbound sockets via
        // AndroidNetworkBridge; keep the internal forwarder inside the VPN.
        when (appMode) {
            AndroidVpnSettings.PerAppMode.ALL -> Unit

            AndroidVpnSettings.PerAppMode.ONLY_SELECTED -> {
                val packages = AndroidVpnSettings.perAppPackages(this)
                if (packages.isEmpty()) {
                    // A list that selects nothing would tunnel everything, so fall
                    // back to excluding only this app and letting the rest through.
                    // No allow-list means all applications use the VPN.
                } else {
                    (packages + packageName).forEach { runCatching { builder.addAllowedApplication(it) } }
                }
            }

            AndroidVpnSettings.PerAppMode.BYPASS_SELECTED -> {
                AndroidVpnSettings.perAppPackages(this).filter { it != packageName }.forEach { runCatching { builder.addDisallowedApplication(it) } }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && AndroidVpnSettings.appendHttpProxy(this)) {
            runCatching {
                builder.setHttpProxy(
                    android.net.ProxyInfo.buildDirectProxy(
                        HTTP_PROXY_HOST,
                        com.miku.ray.handler.SettingsManager.getHttpPort(),
                        AndroidVpnSettings.proxyExclusions(this),
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
     * Closes the original descriptor borrowed during native startup. [ParcelFileDescriptor.adoptFd]
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

    private fun buildNotification(
        contentText: String = getString(R.string.vpn_notification_running),
        statsLines: List<String>? = null,
    ): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, MikuVpnService::class.java).setAction(vpnAction(packageName, ACTION_STOP)),
            pendingFlags(),
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            // The monochrome silhouette renders cleanly in the status bar; the
            // full-colour launcher icon becomes an opaque blob there.
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setContentIntent(configurePendingIntent())
            .addAction(0, getString(R.string.vpn_action_stop), stopIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (statsLines != null) {
            // BigTextStyle wraps long lines; InboxStyle's rows are single-line
            // by design, so a long exit-IP line was always cut off with an
            // ellipsis no matter how the lines were split. The speed line leads
            // the body so the expanded view keeps showing it too.
            val style = NotificationCompat.BigTextStyle()
                .bigText((listOf(contentText) + statsLines).joinToString("\n"))
            builder.setStyle(style)
        }
        return builder.build()
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
        // Suffixes only; the full action string is derived from the
        // applicationId at the call site so a package rename cannot desync
        // senders and receivers.
        const val ACTION_STOP = "STOP_VPN"

        /** Reloads the core in place so changed settings apply immediately. */
        const val ACTION_RESTART = "RESTART_VPN"

        /** Picks up settings the running tunnel can adopt as is. */
        const val ACTION_REFRESH = "REFRESH_VPN"

        const val ACTION_VPN_START_FAILED = "VPN_START_FAILED"

        fun vpnAction(packageName: String, suffix: String) = "$packageName.action.$suffix"

        const val EXTRA_FAILURE_DETAIL = "failure_detail"

        private const val CHANNEL_ID = "miku_vpn_status"
        private const val NOTIFICATION_ID = 1

        /** How often the running session's traffic is written into the profile's totals. */
        private const val CHECKPOINT_INTERVAL_MS = 30_000L

        /** How often the running profile's row re-reads its totals. */
        private const val ROW_REFRESH_INTERVAL_MS = 3_000L

        /** How often the foreground notification is re-posted with live numbers. */
        private const val NOTIFICATION_STATS_INTERVAL_MS = 3_000L

        /** Exit IP is a network probe; re-fetching it every beat would be wasteful. */
        private const val EXIT_IP_REFRESH_MS = 5 * 60_000L

        /** First retry after a failed exit-IP probe; backs off from there. */
        private const val EXIT_IP_RETRY_MS = 15_000L

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
            if (running) return
            ConnectionStatus.update(context, ConnectionStatus.Phase.CONNECTING)
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, MikuVpnService::class.java),
            )
        }

        fun stop(context: Context) {
            ConnectionStatus.update(context, ConnectionStatus.Phase.DISCONNECTING)
            // Background start restrictions (API 26+) can reject a startService
            // from a non-visual entry point; a stop request that never lands
            // would leave the tunnel up, so swallow the rejection rather than
            // crash the caller — the notification stop button uses a
            // PendingIntent and is exempt from the restriction.
            val delivered = runCatching {
                context.startService(
                    Intent(context, MikuVpnService::class.java).setAction(vpnAction(context.packageName, ACTION_STOP))
                )
            }.isSuccess
            if (!delivered) {
                // Nobody will process the stop (usually there is no service at
                // all), so leave the phase consistent: the quick-settings tile
                // refuses taps while it shows a transition.
                ConnectionStatus.update(context, ConnectionStatus.Phase.DISCONNECTED)
            }
        }
    }
}

internal data class NotificationSpeedSample(val upBps: Long, val downBps: Long)

/**
 * Samples the core's cumulative counters against the previous beat to derive a
 * per-second rate. Top level (not a method) so the arithmetic is unit-testable
 * without standing up a Service. The first beat after connect reports zeroes
 * because there is no baseline to diff against yet.
 */
internal fun notificationSpeed(
    prevUp: Long, prevDown: Long, prevAt: Long,
    nowUp: Long, nowDown: Long, nowAt: Long,
): NotificationSpeedSample {
    if (prevAt == 0L) return NotificationSpeedSample(0, 0)
    val dtMs = (nowAt - prevAt).coerceAtLeast(1)
    fun rate(delta: Long) = ((delta * 1000) / dtMs).coerceAtLeast(0)
    return NotificationSpeedSample(rate(nowUp - prevUp), rate(nowDown - prevDown))
}
