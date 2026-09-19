package com.miku.ray.core

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.miku.ray.AppConfig
import com.miku.ray.MikuCoreBridge
import com.miku.ray.R
import com.miku.ray.contracts.ServiceControl
import com.miku.ray.dto.OutboundTrafficStat
import com.miku.ray.handler.MmkvManager
import com.miku.ray.handler.SettingsManager
import com.miku.ray.handler.SpeedtestManager
import com.miku.ray.util.LogUtil
import com.miku.ray.util.MessageUtil
import com.miku.ray.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The service-side core loop, on mihomo.
 *
 * MikuRay's version drove the Xray core: it built a config from the selected
 * server, handed that and the TUN descriptor to the GoMobile controller, and
 * owned the service lifecycle around it. MikuBox's core is mihomo and its tunnel
 * is owned by MikuBox's own VPN service, so this object keeps the same surface —
 * the home screen, the notification, the tile and the widgets all call it — and
 * routes the operations to that service through [MikuCoreBridge].
 *
 * Two of the differences are behaviour rather than implementation detail, and are
 * worth stating: `startCoreLoop` starts whatever MikuBox has selected (the Xray
 * config string MikuRay would build has no meaning for this core), and
 * `getRunningServerName` reports MikuRay's own selected server, which stays empty
 * while the app is driven by MikuBox's profile list.
 */
object CoreServiceManager {

    @Volatile
    var serviceControl: ServiceControl? = null
        set(value) {
            field = value
            value?.let { LogUtil.i(AppConfig.TAG, "service control attached: ${it.javaClass.simpleName}") }
        }

    private val starting = AtomicBoolean(false)

    fun clearServiceControl(instance: ServiceControl) {
        if (serviceControl === instance) serviceControl = null
    }

    fun isRunning(): Boolean = MikuCoreBridge.isRunning()

    fun getRunningServerName(): String {
        val guid = MmkvManager.getSelectServer().orEmpty()
        return MmkvManager.decodeServerConfig(guid)?.remarks.orEmpty()
    }

    /**
     * Starts the tunnel. The descriptor is ignored: MikuBox's service creates its
     * own TUN through the platform, which is also what keeps the tunnel alive
     * across a service restart.
     */
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        if (!starting.compareAndSet(false, true)) return false
        return try {
            val started = MikuCoreBridge.start("")
            if (!started) reportStartFailure("the core did not start")
            started
        } catch (e: Exception) {
            reportStartFailure(e.message ?: e.javaClass.simpleName)
            false
        } finally {
            starting.set(false)
        }
    }

    fun stopCoreLoop(): Boolean = try {
        MikuCoreBridge.stop()
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "stopCoreLoop failed", e)
        false
    }

    fun reportStartFailure(message: String) {
        LogUtil.e(AppConfig.TAG, "start failed: $message")
    }

    /**
     * MikuRay's services report the failure together with the service that hit
     * it; the source is not used for anything beyond the log line.
     */
    fun reportStartFailure(source: Any?, message: String) {
        LogUtil.e(AppConfig.TAG, "start failed in ${source?.javaClass?.simpleName}: $message")
    }

    /** Per-proxy totals in the `tag,direction,bytes` shape MikuRay's reader expects. */
    fun queryAllOutboundTrafficStats(): List<OutboundTrafficStat> {
        val payload = runCatching { MikuCoreBridge.outboundTrafficStats() }.getOrDefault("")
        if (payload.isBlank()) return emptyList()

        val result = ArrayList<OutboundTrafficStat>()
        payload.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.split(',', limit = 3)
            if (parts.size < 3) return@forEach
            val value = parts[2].trim().toLongOrNull() ?: return@forEach
            result.add(
                OutboundTrafficStat(
                    tag = parts[0].trim(),
                    direction = parts[1].trim(),
                    value = value,
                ),
            )
        }
        return result
    }

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiverRegistered = false

    /**
     * Answers the messages the vendored screens send to whoever runs the tunnel.
     *
     * MikuRay's own service registered this while its core ran; the questions are
     * the same here — the home screen asks whether a tunnel is up the moment it
     * appears, and asks for the connection's delay and address when it wants them
     * — but the answers come from MikuBox's tunnel.
     */
    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val service = serviceControl?.getService() ?: return
            val requestId = intent?.getStringExtra(MessageUtil.EXTRA_REQUEST_ID).orEmpty()
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    val running = isRunning()
                    if (isOrderedBroadcast) {
                        resultCode = if (running) Activity.RESULT_OK else Activity.RESULT_CANCELED
                    }
                    MessageUtil.sendMsg2UI(
                        service,
                        if (running) AppConfig.MSG_STATE_RUNNING else AppConfig.MSG_STATE_NOT_RUNNING,
                        "",
                    )
                    if (running) measureConnection(service, requestId)
                }

                AppConfig.MSG_STATE_STOP -> serviceControl?.stopService()

                AppConfig.MSG_STATE_RESTART -> {
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
                    LauncherManager.restartService(service)
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    // The screen sends this as an ordered broadcast and treats a
                    // missing acknowledgement as "cancelled", which would drop the
                    // result that is about to be measured.
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
                    measureConnection(service, requestId)
                }

                AppConfig.MSG_MEASURE_IP -> measureIp(service, requestId)
            }
        }
    }

    /**
     * The tunnel is up: tell the UI, then measure what it is carrying.
     *
     * MikuBox's service calls this rather than starting the core itself, so the
     * announcement carries the state the service already reached.
     */
    fun announceTunnelStarted(service: Service) {
        registerControlReceiver(service)
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, false)
        measureConnection(service, "")
    }

    /** The tunnel is down: nothing left to answer, and the UI has to hear about it. */
    fun announceTunnelStopped(service: Service) {
        unregisterControlReceiver(service)
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
    }

    /**
     * The tunnel could not be started. Without this the UI keeps showing the
     * connection it asked for, because nothing else reports a failed start.
     */
    fun announceStartFailure(service: Service, message: String) {
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
        runCatching { service.stopSelf() }
    }

    private fun registerControlReceiver(service: Service) {
        if (receiverRegistered) return
        runCatching {
            ContextCompat.registerReceiver(
                service,
                controlReceiver,
                IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE),
                Utils.receiverFlags(),
            )
            receiverRegistered = true
        }.onFailure { LogUtil.e(AppConfig.TAG, "failed to register the control receiver", it) }
    }

    private fun unregisterControlReceiver(service: Service) {
        if (!receiverRegistered) return
        runCatching { service.unregisterReceiver(controlReceiver) }
            .onFailure { LogUtil.e(AppConfig.TAG, "failed to unregister the control receiver", it) }
        receiverRegistered = false
    }

    /**
     * Measures the connection as the home screen shows it: the delay of the node
     * the tunnel uses, and — when that succeeded — the address it exits with.
     */
    private fun measureConnection(service: Service, requestId: String) {
        if (!isRunning()) return
        backgroundScope.launch {
            val testUrl = SettingsManager.getDelayTestUrl()
            val delay = runCatching { MikuCoreBridge.currentNodeDelay(testUrl) }.getOrDefault(-1L)
            val result = if (delay >= 0L) {
                service.getString(R.string.connection_test_available, delay)
            } else {
                service.getString(R.string.connection_test_error, "")
            }
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, result, requestId)
            if (delay >= 0L) measureIp(service, requestId)
        }
    }

    /**
     * The address the tunnel exits with. The request goes through the core's own
     * inbound, so what comes back is what a browser would see.
     */
    private fun measureIp(service: Service, requestId: String) {
        backgroundScope.launch {
            val ip = runCatching { SpeedtestManager.getRemoteIPInfo() }.getOrNull()
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_IP_SUCCESS, ip.orEmpty(), requestId)
        }
    }
}
