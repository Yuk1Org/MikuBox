package top.uwu.mikubox.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-level overrides applied on top of the active profile at core start, plus a
 * few connection knobs. Config keys (log level, mode, allow-lan, tun stack) are
 * merged into the Mihomo config by the bridge; IPv6 and the latency-test options
 * are consumed directly by the Android layer.
 */
object MihomoCoreSettings {

    /** [FOLLOW] leaves the profile's own value untouched. */
    enum class ProxyMode(val value: String?) { FOLLOW(null), RULE("rule"), GLOBAL("global"), DIRECT("direct") }
    enum class LogLevel(val value: String) { SILENT("silent"), WARNING("warning"), INFO("info"), DEBUG("debug") }

    /**
     * [value] null means the profile's (or the core's default) stack is kept.
     * The app defaults to [GVISOR]: it is Mihomo's own default and the only
     * stack that stays entirely in user space, which Android's VPN file
     * descriptor cannot always support for the kernel-assisted alternatives.
     */
    enum class TunStack(val value: String?) {
        GVISOR("gvisor"), MIXED("mixed"), SYSTEM("system"), FOLLOW(null)
    }

    private const val PREFS = "mihomo_core_settings"
    private const val KEY_LOG = "log_level"
    private const val KEY_MODE = "mode"
    private const val KEY_ALLOW_LAN = "allow_lan"
    private const val KEY_TUN_STACK = "tun_stack"
    private const val KEY_IPV6 = "ipv6"
    private const val KEY_TEST_URL = "test_url"
    private const val KEY_TEST_TIMEOUT = "test_timeout"
    private const val KEY_AUTOCONNECT = "autoconnect_on_start"
    private const val KEY_UNIFIED_DELAY = "unified_delay"
    private const val KEY_TCP_CONCURRENT = "tcp_concurrent"

    const val DEFAULT_TEST_URL = "https://cp.cloudflare.com"
    const val DEFAULT_TEST_TIMEOUT = 5000

    fun logLevel(context: Context): LogLevel = enumOr(prefs(context).getString(KEY_LOG, null), LogLevel.INFO)
    fun setLogLevel(context: Context, value: LogLevel) = putString(context, KEY_LOG, value.name)

    fun mode(context: Context): ProxyMode = enumOr(prefs(context).getString(KEY_MODE, null), ProxyMode.FOLLOW)
    fun setMode(context: Context, value: ProxyMode) = putString(context, KEY_MODE, value.name)

    /**
     * `allow-lan` follows the ported screen's "LAN sharing" switch. The app's own
     * key is kept as the fallback for a device where that switch was never touched
     * but the value was set here.
     */
    fun allowLan(context: Context): Boolean =
        MikuRaySettings.allowLan(context) || prefs(context).getBoolean(KEY_ALLOW_LAN, false)

    fun setAllowLan(context: Context, value: Boolean) = putBool(context, KEY_ALLOW_LAN, value)

    fun tunStack(context: Context): TunStack = enumOr(prefs(context).getString(KEY_TUN_STACK, null), TunStack.GVISOR)
    fun setTunStack(context: Context, value: TunStack) = putString(context, KEY_TUN_STACK, value.name)

    /** The ported VPN screen's IPv6 switch decides, as it does for the TUN. */
    fun ipv6(context: Context): Boolean = MikuRaySettings.ipv6Enabled(context)

    fun testUrl(context: Context): String =
        prefs(context).getString(KEY_TEST_URL, null)?.ifBlank { null } ?: DEFAULT_TEST_URL

    fun setTestUrl(context: Context, value: String) = putString(context, KEY_TEST_URL, value.trim())

    fun testTimeout(context: Context): Int =
        prefs(context).getInt(KEY_TEST_TIMEOUT, DEFAULT_TEST_TIMEOUT).coerceIn(1000, 30_000)

    fun setTestTimeout(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_TEST_TIMEOUT, value.coerceIn(1000, 30_000)).commit().let {}

    fun autoConnectOnStart(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTOCONNECT, false)
    fun setAutoConnectOnStart(context: Context, value: Boolean) = putBool(context, KEY_AUTOCONNECT, value)

    fun unifiedDelay(context: Context): Boolean = prefs(context).getBoolean(KEY_UNIFIED_DELAY, false)
    fun setUnifiedDelay(context: Context, value: Boolean) = putBool(context, KEY_UNIFIED_DELAY, value)

    fun tcpConcurrent(context: Context): Boolean = prefs(context).getBoolean(KEY_TCP_CONCURRENT, false)
    fun setTcpConcurrent(context: Context, value: Boolean) = putBool(context, KEY_TCP_CONCURRENT, value)

    /**
     * Config-key overrides merged into the profile by the bridge, as a JSON
     * object. [tunMtu] mirrors the MTU of the interface the VPN service created:
     * the core otherwise defaults its stack to 9000, which cannot survive the
     * Android tunnel and stalls large transfers.
     */
    fun overridesJson(context: Context, tunMtu: Int? = null): String = JSONObject().apply {
        put("log-level", logLevel(context).value)
        put("allow-lan", allowLan(context))
        put("unified-delay", unifiedDelay(context))
        put("tcp-concurrent", tcpConcurrent(context))
        mode(context).value?.let { put("mode", it) }

        // "tun" and "dns" are merged into the profile's own sections by the
        // bridge, so the structured panels only touch what they list.
        val tun = CoreOverrides.tunJson(context)
        tunStack(context).value?.let { tun.put("stack", it) }
        tunMtu?.let { tun.put("mtu", it) }
        if (tun.length() > 0) put("tun", tun)

        // The DNS section is the app's model with the ported core screen's own
        // resolvers layered on top: its "remote" resolver is what proxied names
        // use (the main nameserver), its "domestic" one is what direct names use,
        // and its local-DNS switch asks the system resolver first — which is
        // exactly the core's `direct-nameserver`.
        val dns = MikuRaySettings.applyDnsOverrides(
            dns = DnsOverrides.json(context),
            remote = MikuRaySettings.remoteDnsServers(context),
            domestic = MikuRaySettings.domesticDnsServers(context),
            localDns = MikuRaySettings.localDnsEnabled(context),
            fakeDns = MikuRaySettings.fakeDnsEnabled(context),
            fakePool = MikuRaySettings.fakeDnsPool(context),
            preferIpv6 = MikuRaySettings.preferIpv6(context),
        )
        if (dns.length() > 0) put("dns", dns)

        val sniffer = CoreOverrides.snifferJson(context)
        if (sniffer.length() > 0) put("sniffer", sniffer)

        // The port the core listens on is the one the ported screens talk to:
        // their address lookup, subscription updates over the proxy and asset
        // downloads all dial the port in MikuRay's own settings, so the core opens
        // it. An override with a value of its own still wins, because it is
        // applied after this one (see `CoreOverrides.coreJson`).
        put("mixed-port", com.miku.ray.handler.SettingsManager.getSocksPort())

        // Core-wide extras (find-process-mode, geodata loader, TLS fingerprint,
        // keep-alive) are top-level keys.
        CoreOverrides.coreJson(context).also { core ->
            core.keys().forEach { key -> put(key, core.get(key)) }
        }

        // The vendored routing screen's rules, in front of the profile's own. The
        // bridge treats a flat key as a replacement, so the profile's rules have
        // to travel with them (see MikuRayRoutingBridge).
        val portedRules = MikuRayRoutingBridge.enabledRulesets()
        if (portedRules.isNotEmpty()) {
            val profileRules = MikuRayRoutingBridge.profileRules(MihomoConfigStore.activeConfig(context))
            put("rules", org.json.JSONArray(MikuRayRoutingBridge.rulesFor(portedRules, profileRules)))
        }
    }.toString()

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        runCatching { enumValueOf<T>(name!!) }.getOrDefault(fallback)

    private fun putString(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).commit()
    }

    private fun putBool(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).commit()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
