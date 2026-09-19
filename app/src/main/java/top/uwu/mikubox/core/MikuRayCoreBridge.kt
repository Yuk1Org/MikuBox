package top.uwu.mikubox.core

import com.miku.ray.MikuCoreBridge
import top.uwu.mikubox.profile.MihomoProfileStore
import top.uwu.mikubox.profile.MihomoSubscriptionUpdater
import top.uwu.mikubox.service.VpnController

/**
 * MikuBox's side of the bridge the vendored MikuRay layer talks to.
 *
 * The vendored screens call `CoreServiceManager`/`CoreNativeManager`, which now
 * route here through [MikuCoreBridge]; this is where those calls meet the mihomo
 * core and the app's own VPN service. It is installed once from `MikuApp`.
 */
object MikuRayCoreBridge : MikuCoreBridge.Impl {

    override fun isRunning(): Boolean = VpnController.isRunning

    /**
     * Starts the tunnel. The Xray config MikuRay would pass is not used: MikuBox
     * starts the core from the profile the user selected, and the Android VPN
     * consent/foreground-service flow belongs to MikuBox's own service — running
     * it from here would be a second tunnel.
     */
    override fun start(config: String): Boolean {
        val context = MikuRayBridgeContext.application ?: return false
        // A new tunnel means new counters in the core, so the deltas reported to
        // the traffic loop start over with them.
        MihomoTrafficDelta.reset()
        // The selection made on MikuRay's list is a MikuBox profile id (see
        // MikuRayProfileSync), so the profile the user tapped is the one that
        // gets connected.
        MikuRayProfileSync.selectedProfileId()?.let { guid ->
            MihomoProfileStore.select(context, guid)
        }
        VpnController.connect(context)
        return VpnController.isRunning
    }

    override fun stop(): Boolean {
        val context = MikuRayBridgeContext.application ?: return false
        MihomoTrafficDelta.reset()
        VpnController.disconnect(context)
        return !VpnController.isRunning
    }

    override fun version(): String = runCatching { MihomoCore.version() }.getOrDefault("")

    /**
     * MikuRay measures a delay from a *server config*; the mihomo core measures
     * one from a proxy name, and a profile here is a whole configuration rather
     * than one server. So an arbitrary config cannot be measured without
     * connecting it, and this reports "never measured" (zero, which the rows show
     * as nothing) instead of a failure the profile has not actually had.
     */
    override fun measureDelay(config: String, testUrl: String): Long = 0L

    /**
     * The delay of the node the tunnel is carrying traffic through — the reading
     * that means something while the core is running, and what the rows and the
     * connect bar show.
     */
    override fun currentNodeDelay(testUrl: String): Long {
        if (!VpnController.isRunning) return -1L
        val node = currentNodeName() ?: return -1L
        return runCatching { MihomoCore.delay(node, testUrl).toLong() }.getOrDefault(-1L)
    }

    /** The node the core is carrying traffic through, as the core names it. */
    private fun currentNodeName(): String? = runCatching {
        MihomoCore.proxies().values
            .firstOrNull { group -> group.isGroup && !group.now.isNullOrBlank() }
            ?.now
    }.getOrNull()

    /**
     * `tag,direction,bytes` lines. MikuRay's traffic loop adds what it reads to
     * the totals behind its rows, so these are deltas — see [MihomoTrafficDelta].
     */
    override fun outboundTrafficStats(): String = MihomoTrafficDelta.take()
}

/** The application instance the bridge needs to reach the service. */
object MikuRayBridgeContext {
    @Volatile
    var application: android.content.Context? = null
        private set

    fun attach(context: android.content.Context) {
        application = context.applicationContext
    }
}

/**
 * The subscription table the vendored subscription screens read and write,
 * backed by MikuBox's own profile store.
 *
 * This is what makes those screens honest: the row a user adds there is the
 * profile the tunnel uses, with the same URL, schedule and proxy flag — not a
 * copy in a second store that nothing connects with.
 */
object MikuRaySubscriptions : com.miku.ray.MikuSubscriptions.Impl {

    private val store: MihomoProfileStore get() = MihomoProfileStore

    override fun list(): List<com.miku.ray.MikuSubscriptions.Subscription> {
        val context = MikuRayBridgeContext.application ?: return emptyList()
        return store.profiles(context)
            .filter { it.isSubscription }
            .map { profile ->
                com.miku.ray.MikuSubscriptions.Subscription(
                    id = profile.id,
                    name = profile.name,
                    url = profile.subscriptionUrl.orEmpty(),
                    autoUpdate = profile.updateIntervalMinutes > 0,
                    intervalMinutes = profile.updateIntervalMinutes,
                    throughProxy = profile.updateThroughProxy,
                    lastUpdatedMillis = profile.updatedAtMillis,
                )
            }
    }

    override fun upsert(
        id: String?,
        name: String,
        url: String,
        autoUpdate: Boolean,
        intervalMinutes: Long,
        throughProxy: Boolean,
    ): String? {
        val context = MikuRayBridgeContext.application ?: return null
        // "Auto update off" is stored as no interval: the scheduler keys off it.
        val minutes = if (autoUpdate) intervalMinutes.coerceAtLeast(15) else 0L
        val existing = id?.let { wanted -> store.profiles(context).firstOrNull { it.id == wanted } }
        return if (existing == null) {
            runCatching {
                store.createSubscription(
                    context = context,
                    name = name,
                    url = url,
                    intervalMinutes = minutes,
                    updateThroughProxy = throughProxy,
                ).id
            }.getOrNull()
        } else {
            store.update(
                context,
                existing.copy(
                    name = name.ifBlank { existing.name },
                    subscriptionUrl = url,
                    updateIntervalMinutes = minutes,
                    updateThroughProxy = throughProxy,
                ),
            )
            MihomoSubscriptionUpdater.reconfigure(context)
            existing.id
        }
    }

    override fun remove(id: String) {
        val context = MikuRayBridgeContext.application ?: return
        store.remove(context, id)
        MihomoSubscriptionUpdater.reconfigure(context)
    }

    override fun refresh(id: String): Boolean {
        val context = MikuRayBridgeContext.application ?: return false
        val profile = store.profiles(context).firstOrNull { it.id == id } ?: return false
        return runCatching { MihomoSubscriptionUpdater.update(context, profile) }.isSuccess
    }
}
