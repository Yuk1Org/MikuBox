package com.mikubox.mihomo.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.miku.ray.AppConfig
import com.miku.ray.util.LogUtil
import com.miku.ray.util.MessageUtil

/**
 * Public entry point for starting/stopping the VPN.
 *
 * The UI layer (built separately) only needs to call [connect] / [disconnect];
 * the consent handshake ([VpnService.prepare]) is handled transparently via
 * [VpnRequestActivity] when needed.
 */
object VpnController {

    val isRunning: Boolean
        get() = MikuVpnService.running

    /**
     * Start the VPN. If the user has not yet granted VPN consent, this routes
     * through [VpnRequestActivity] to show the system consent dialog first.
     *
     * A request that lands while the previous tunnel is still shutting down is
     * *queued*, not dropped: the teardown is short, and a tap that vanished
     * looked exactly like a dead connect button (the screen waited forever for
     * an outcome). The queued request starts the moment the service reports
     * DISCONNECTED; any stop issued in the meantime cancels it. [source] is
     * diagnostic, see MikuVpnService.start.
     */
    fun connect(context: Context, source: String = "controller") {
        val app = context.applicationContext
        // Checked before isRunning: the stop request marks the phase first, so
        // during the drain the old tunnel is still "running" for a moment.
        if (ConnectionStatus.phase.value == ConnectionStatus.Phase.DISCONNECTING) {
            queueConnectAfterTeardown(app, source)
            return
        }
        if (isRunning) return
        startNow(app, source)
    }

    fun disconnect(context: Context) {
        cancelPendingConnect()
        MikuVpnService.stop(context)
    }

    /** Forgets a connect that was waiting for the previous tunnel to drain. */
    fun cancelPendingConnect() {
        pendingConnect?.let(handler::removeCallbacks)
        pendingConnect = null
    }

    /**
     * Waits out a teardown and then performs the request that arrived during
     * it. Polling the phase is what keeps this from starting a second tunnel
     * over one that is still closing; the timeout only exists so a teardown
     * that never finishes cannot leave a request pending forever.
     */
    private fun queueConnectAfterTeardown(context: Context, source: String) {
        LogUtil.i(message = "VPN connect queued until the previous tunnel finishes shutting down (source=$source)")
        pendingConnect?.let(handler::removeCallbacks)
        pendingConnect = null
        val queuedAt = SystemClock.elapsedRealtime()
        val task = object : Runnable {
            override fun run() {
                if (pendingConnect !== this) return
                when (ConnectionStatus.phase.value) {
                    ConnectionStatus.Phase.DISCONNECTING -> {
                        if (SystemClock.elapsedRealtime() - queuedAt >= CONNECT_TEARDOWN_TIMEOUT_MS) {
                            pendingConnect = null
                            LogUtil.w(
                                message = "VPN connect queued behind a teardown that did not finish; giving up (source=$source)",
                            )
                            startFailed(context)
                        } else {
                            handler.postDelayed(this, CONNECT_POLL_MS)
                        }
                    }

                    ConnectionStatus.Phase.DISCONNECTED -> {
                        pendingConnect = null
                        startNow(context, source)
                    }

                    // Something else already brought it up (or is bringing it
                    // up): the request is satisfied, not lost.
                    else -> pendingConnect = null
                }
            }
        }
        pendingConnect = task
        handler.postDelayed(task, CONNECT_POLL_MS)
    }

    private fun startNow(context: Context, source: String) {
        // This is a connect the user asked for (UI, tile, Tasker, widget).
        ConnectionStatus.update(context, ConnectionStatus.Phase.CONNECTING)
        if (VpnService.prepare(context) != null) {
            context.startActivity(
                Intent(context, VpnRequestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val accepted = try {
            MikuVpnService.start(context, source = source)
        } catch (error: Exception) {
            // Tasker/widget callers run with no background-start exemption:
            // the platform rejects the service start, and both the crash it
            // would raise here and a phase stuck on CONNECTING (the tile
            // refuses taps then) are worse than reporting the failure.
            LogUtil.w(message = "VPN start request rejected", throwable = error)
            startFailed(context)
            return
        }
        if (!accepted) {
            // The request could not be handed to the platform. The requester
            // has to hear about it, or its button stays "connecting" waiting
            // for an outcome that never comes.
            LogUtil.i(message = "VPN start was refused (source=$source)")
            startFailed(context)
        }
    }

    /** Restores the disconnected state and tells the screen its request ended. */
    private fun startFailed(context: Context) {
        ConnectionStatus.update(context, ConnectionStatus.Phase.DISCONNECTED)
        MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_START_FAILURE, "")
    }

    /**
     * Reloads the core so settings that are read at start apply right away.
     *
     * Changing one setting is a reload, and changing five in a row is still only
     * one: requests are collected for a moment so a run through the settings page
     * does not restart the tunnel five times. The running check happens again
     * when the debounce fires, so a disconnect made during the wait stays a
     * disconnect instead of being resurrected by the stale request.
     */
    fun restart(context: Context) {
        if (!MikuVpnService.running) {
            // The start task reads the settings the moment it runs, so a change
            // made while the tunnel is connecting would silently apply to
            // nothing. Queue it: the service calls [onTunnelConnected] once the
            // tunnel is up and the request is replayed. While fully
            // disconnected nothing is queued — the next start re-reads every
            // setting anyway, so a replay would only bounce the tunnel.
            if (MikuVpnService.starting) pendingRestartWhenConnected = true
            return
        }
        val app = context.applicationContext
        pendingRestart?.let(handler::removeCallbacks)
        val request = Runnable {
            pendingRestart = null
            if (!MikuVpnService.running) return@Runnable
            runCatching {
                app.startService(
                    // The full, package-qualified action: the service matches
                    // vpnAction(...), so the bare suffix constant would be
                    // delivered to a `when` that silently drops it.
                    Intent(app, MikuVpnService::class.java)
                        .setAction(MikuVpnService.vpnAction(app.packageName, MikuVpnService.ACTION_RESTART)),
                )
            }.onFailure {
                // A rejected start used to vanish here, leaving the switch
                // looking applied while the core kept the old configuration.
                LogUtil.w(message = "Reload request could not reach the service", throwable = it)
            }
        }
        pendingRestart = request
        handler.postDelayed(request, RESTART_DEBOUNCE_MS)
    }

    /** Applies settings the running tunnel can adopt without reloading the core. */
    fun refresh(context: Context) {
        if (!MikuVpnService.running) return
        val app = context.applicationContext
        runCatching {
            app.startService(
                Intent(app, MikuVpnService::class.java)
                    .setAction(MikuVpnService.vpnAction(app.packageName, MikuVpnService.ACTION_REFRESH)),
            )
        }
    }

    /**
     * The service reached CONNECTED: replay a restart request that landed
     * while the tunnel was connecting. [MikuVpnService.starting] is re-checked
     * inside [restart] via the debounce callback, so a disconnect that raced
     * the replay still stays a disconnect.
     */
    fun onTunnelConnected(context: Context) {
        if (!pendingRestartWhenConnected) return
        pendingRestartWhenConnected = false
        restart(context)
    }

    /** A new start or a teardown invalidates a queued mid-connect restart. */
    fun clearPendingRestart() {
        pendingRestartWhenConnected = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingRestart: Runnable? = null

    /** A connect waiting for the previous tunnel's teardown to finish. */
    @Volatile
    private var pendingConnect: Runnable? = null

    @Volatile
    private var pendingRestartWhenConnected = false

    private const val RESTART_DEBOUNCE_MS = 1200L

    /** How often a queued connect re-checks whether the teardown finished. */
    private const val CONNECT_POLL_MS = 100L

    /** How long a connect may wait for a teardown before it is reported failed. */
    private const val CONNECT_TEARDOWN_TIMEOUT_MS = 5_000L
}
