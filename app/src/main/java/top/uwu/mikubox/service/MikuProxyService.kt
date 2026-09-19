package top.uwu.mikubox.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import top.uwu.mikubox.R
import top.uwu.mikubox.core.MihomoConfigStore
import top.uwu.mikubox.core.MihomoCore
import top.uwu.mikubox.core.MihomoCoreSettings
import top.uwu.mikubox.core.MihomoDnsSettings

/** Starts Mihomo's local mixed proxy listener without creating a VPN interface. */
class MikuProxyService : Service() {

    /** Core startup (config parse + GeoSite loads) can take seconds; keep it off the main thread. */
    private val startExecutor = CoreServiceRuntime.executor
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    private var starting = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProxy()
            return START_NOT_STICKY
        }
        if (!running && !starting) {
            starting = true
            val request = generation.incrementAndGet()
            VpnController.disconnect(this)
            ensureChannel()
            startForeground(NOTIFICATION_ID, notification())
            startExecutor.execute {
                if (generation.get() != request) return@execute
                try {
                    val config = MihomoConfigStore.activeConfig(this)
                    val result = CoreServiceRuntime.start(this) { MihomoCore.start(
                        this,
                        config,
                        MihomoCore.NO_TUN,
                        MihomoDnsSettings.effectiveOverride(this, config),
                        MihomoCoreSettings.overridesJson(this),
                    ) }
                    if (result.isFailure) {
                        Log.e(TAG, "proxy start failed: ${result.exceptionOrNull()?.message.orEmpty()}")
                        mainHandler.post { if (generation.get() == request) stopProxy() }
                        return@execute
                    }
                    if (generation.get() != request) {
                        CoreServiceRuntime.stop(this)
                        return@execute
                    }
                    mainHandler.post {
                        if (generation.get() != request) return@post
                        starting = false
                        running = true
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "proxy start failed", error)
                    mainHandler.post { if (generation.get() == request) stopProxy() }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopProxy() {
        generation.incrementAndGet()
        starting = false
        running = false
        // Unconditional and off the main thread: a stop racing the start task has
        // to reach the core even though [running] is not set yet, and the native
        // side stops idempotently.
        runCatching { startExecutor.execute { runCatching { CoreServiceRuntime.stop(this) } } }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, "miku_vpn_status")
        .setSmallIcon(R.mipmap.ic_launcher_monochrome)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.local_proxy_notification_running))
        .setOngoing(true)
        .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel("miku_vpn_status") == null) {
            manager.createNotificationChannel(
                NotificationChannel("miku_vpn_status", getString(R.string.vpn_channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val ACTION_STOP = "top.uwu.mikubox.action.STOP_PROXY"
        private const val NOTIFICATION_ID = 3
        private const val TAG = "MikuBox"

        @Volatile
        var running = false
            private set

        fun start(context: Context) = ContextCompat.startForegroundService(
            context,
            Intent(context, MikuProxyService::class.java),
        )

        fun stop(context: Context) {
            context.startService(Intent(context, MikuProxyService::class.java).setAction(ACTION_STOP))
        }
    }
}
