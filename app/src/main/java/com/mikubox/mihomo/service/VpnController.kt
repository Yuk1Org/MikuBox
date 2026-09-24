package com.mikubox.mihomo.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import com.miku.ray.util.LogUtil

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
     */
    fun connect(context: Context) {
        if (isRunning) return
        ConnectionStatus.update(context, ConnectionStatus.Phase.CONNECTING)
        if (VpnService.prepare(context) != null) {
            context.startActivity(
                Intent(context, VpnRequestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            runCatching { MikuVpnService.start(context) }.onFailure { error ->
                // Tasker/widget callers run with no background-start exemption:
                // the platform rejects the service start, and both the crash it
                // would raise here and a phase stuck on CONNECTING (the tile
                // refuses taps then) are worse than reporting the failure.
                ConnectionStatus.update(context, ConnectionStatus.Phase.DISCONNECTED)
                LogUtil.w(message = "VPN start request rejected", throwable = error)
                com.miku.ray.util.MessageUtil.sendMsg2UI(context, com.miku.ray.AppConfig.MSG_STATE_START_FAILURE, "")
            }
        }
    }

    fun disconnect(context: Context) {
        MikuVpnService.stop(context)
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
        if (!MikuVpnService.running) return
        val app = context.applicationContext
        pendingRestart?.let(handler::removeCallbacks)
        val request = Runnable {
            pendingRestart = null
            if (!MikuVpnService.running) return@Runnable
            runCatching {
                app.startService(
                    Intent(app, MikuVpnService::class.java).setAction(MikuVpnService.ACTION_RESTART),
                )
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
                Intent(app, MikuVpnService::class.java).setAction(MikuVpnService.ACTION_REFRESH),
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingRestart: Runnable? = null

    private const val RESTART_DEBOUNCE_MS = 1200L
}
