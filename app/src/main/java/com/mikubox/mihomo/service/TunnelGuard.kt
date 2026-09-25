package com.mikubox.mihomo.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.miku.ray.AppConfig
import com.miku.ray.handler.MmkvManager
import com.miku.ray.util.LogUtil

/**
 * Brings the tunnel back when the process is killed with it up.
 *
 * START_STICKY is not enough on Android 12+: a killed process's foreground
 * service is not restarted, because the restart would need a background
 * foreground-service start the platform refuses (observed: the service record
 * keeps `startRequested=true` and the system never schedules anything). Alarms
 * live in the platform rather than in the process, so a short repeating check
 * survives the kill and re-launches the service within about a minute. The
 * expectation is persisted, so the guard only ever restores a tunnel the user
 * actually asked for: disconnecting clears it, and so does a failed start.
 */
object TunnelGuard {

    /**
     * How often the guard checks. Half a minute keeps the gap after a kill
     * short (observed end to end: the alarm fires, the service comes back and
     * the tunnel is up within the next check), while the check itself is a
     * broadcast that only reads a flag unless something is wrong. In Doze the
     * platform stretches allow-while-idle alarms to its own cadence, which is
     * still far better than not returning until the user opens the app.
     */
    private const val CHECK_INTERVAL_MS = 30_000L

    /** True while the tunnel is supposed to be up. */
    fun isExpected(context: Context): Boolean =
        runCatching { MmkvManager.decodeSettingsBool(AppConfig.PREF_TUNNEL_EXPECTED) }
            .getOrDefault(false)

    /**
     * Records whether a tunnel should be running, arming or disarming the
     * check with it. Called when the service reaches connected state and when
     * it tears down, so a deliberate disconnect never resurrects.
     */
    fun expectRunning(context: Context, expected: Boolean) {
        runCatching { MmkvManager.encodeSettings(AppConfig.PREF_TUNNEL_EXPECTED, expected) }
            .onFailure { LogUtil.w(message = "Could not record the tunnel expectation", throwable = it) }
        if (expected) schedule(context) else cancel(context)
    }

    /** Arms the next check; the receiver re-arms it after every firing. */
    fun schedule(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val at = SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS
        runCatching {
            // Allow-while-idle needs no exact-alarm permission; the platform
            // may defer it in Doze, which is the documented behaviour.
            manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(context))
        }.onFailure { LogUtil.w(message = "Could not arm the tunnel guard", throwable = it) }
    }

    fun cancel(context: Context) {
        runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(pending(context)) }
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        // Explicit component: matches the manifest entry without needing an
        // action filter, so nothing else can trigger the guard.
        Intent(context, TunnelGuardReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * The alarm's landing point. Runs even when the app's process is gone, because
 * the platform starts a fresh one for the broadcast.
 */
class TunnelGuardReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!TunnelGuard.isExpected(context)) {
            // Disconnected while the alarm was in flight; let it lapse.
            TunnelGuard.cancel(context)
            return
        }
        if (!MikuVpnService.running) {
            LogUtil.w(message = "Tunnel expected but not running; restarting it")
            // The start is refused outright when the platform does not allow a
            // background foreground-service start (no battery-optimization
            // exemption, device in Doze); say so instead of throwing out of a
            // broadcast, and keep the alarm armed so the next window retries.
            runCatching { MikuVpnService.start(context) }
                .onFailure { LogUtil.w(message = "Tunnel guard could not restart the service", throwable = it) }
        }
        TunnelGuard.schedule(context)
    }
}
