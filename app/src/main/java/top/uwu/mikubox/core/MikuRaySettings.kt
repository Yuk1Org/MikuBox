package top.uwu.mikubox.core

import android.content.Context
import com.miku.ray.AppConfig
import com.miku.ray.handler.MmkvManager
import com.miku.ray.handler.SettingsManager
import com.miku.ray.util.Utils

/**
 * The settings MikuRay's screens own, as the tunnel reads them.
 *
 * Its VPN and core screens write their own keys, while MikuBox's tunnel was built
 * around its own stores — so a good part of the UI talked to nobody: the switch
 * moved, the value was written, and the TUN was built from something else. This is
 * where those keys are read, one place per setting, in the vocabulary the core and
 * the platform need.
 *
 * The mappings are not always literal, because the two stacks do not model DNS and
 * routing the same way; each one is stated where it happens.
 */
object MikuRaySettings {

    // ---------------------------------------------------------------- tunnel

    /** `pref_vpn_mtu`; MikuRay stores it as text. */
    fun mtu(context: Context): Int = mtuFrom(MmkvManager.decodeSettingsString(AppConfig.PREF_VPN_MTU))

    /**
     * `pref_vpn_dns` — the resolver the tunnel advertises to the apps inside it.
     *
     * The core hijacks port 53 regardless, so this only decides which address the
     * apps are told to ask. An empty setting keeps the tunnel's own address.
     */
    fun tunDnsServers(context: Context): List<String> =
        SettingsManager.getVpnDnsServers().filter { Utils.isPureIpAddress(it) }

    /**
     * `pref_vpn_bypass_lan` — "0" routes the LAN through the tunnel, anything else
     * leaves it on the underlying network. The reference client does that by
     * installing only the public ranges as routes instead of a default route, and
     * that is what [publicRoutes] is for.
     */
    fun bypassLan(context: Context): Boolean =
        bypassLanFrom(MmkvManager.decodeSettingsString(AppConfig.PREF_VPN_BYPASS_LAN, AppConfig.DEFAULT_VPN_BYPASS_LAN))

    /** The stored text behind [bypassLan], for the tunnel's own log line. */
    fun bypassLanRaw(context: Context): String =
        MmkvManager.decodeSettingsString(AppConfig.PREF_VPN_BYPASS_LAN, AppConfig.DEFAULT_VPN_BYPASS_LAN)
            ?: "unset"

    /** The routes to install when the LAN is left alone: everything but private space. */
    fun publicRoutes(): List<String> = AppConfig.ROUTED_IP_LIST

    /**
     * `pref_vpn_interface_address_config_index` — which address pair the tunnel
     * takes. The presets are MikuRay's own table, read through its own accessor so
     * the two cannot drift apart.
     */
    fun interfaceAddress(context: Context): com.miku.ray.enums.VpnInterfaceAddressConfig =
        SettingsManager.getCurrentVpnInterfaceAddressConfig()

    /**
     * The parse behind [bypassLan], separate so it can be checked without the
     * settings store (which is native and unavailable off-device).
     *
     * "1" is the only value that means bypass: "2" is an explicit *do not*, and
     * "0" — "follow config" — leaves the decision to the profile, which in this
     * app means the default route stays and the config's own rules decide where
     * private addresses go. That is how MikuRay reads the same setting in
     * `SettingsManager.routingRulesetsBypassLan`.
     */
    internal fun bypassLanFrom(stored: String?): Boolean = stored == "1"

    /** [mtu], clamped to what a TUN accepts. */
    internal fun mtuFrom(stored: String?): Int =
        (stored?.trim()?.toIntOrNull() ?: DEFAULT_MTU).coerceIn(MIN_MTU, MAX_MTU)

    /**
     * `pref_root_lan_sharing` — let other devices use this phone as a proxy, which
     * is the core's `allow-lan`.
     */
    fun allowLan(context: Context): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_ROOT_LAN_SHARING, false)

    /**
     * The per-app choice: `pref_per_app_proxy` gates the feature,
     * `pref_bypass_apps` says which way round the list is, and
     * `pref_per_app_proxy_set` holds the packages.
     */
    fun perAppMode(context: Context): PerAppMode = perAppModeFrom(
        enabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false),
        bypassSelected = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS, false),
    )

    /** The mapping behind [perAppMode], on its own for the same reason as [bypassLanFrom]. */
    internal fun perAppModeFrom(enabled: Boolean, bypassSelected: Boolean): PerAppMode = when {
        !enabled -> PerAppMode.ALL
        bypassSelected -> PerAppMode.BYPASS_SELECTED
        else -> PerAppMode.ONLY_SELECTED
    }

    fun perAppPackages(context: Context): Set<String> =
        MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)?.toSet().orEmpty()

    /**
     * `pref_append_http_proxy` hands the core's HTTP port to the platform, which
     * only honours it before Android 10.
     */
    fun appendHttpProxy(context: Context): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY, false)

    // ------------------------------------------------------------------ core

    fun ipv6Enabled(context: Context): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED, false)

    /** `pref_tcp_keepalive_idle`, in seconds; unset means the core's own default. */
    fun keepAliveSeconds(context: Context): Int =
        MmkvManager.decodeSettingsString(AppConfig.PREF_TCP_KEEPALIVE_IDLE, KEEP_ALIVE_DEFAULT)
            ?.trim()?.toIntOrNull()?.coerceIn(0, 3600) ?: 0

    // ------------------------------------------------------------------- dns

    /**
     * `pref_remote_dns` — the resolver for names that go through the proxy, which
     * is the core's main `nameserver`. MikuRay allows a DoH URL here, and so does
     * the core.
     */
    fun remoteDnsServers(context: Context): List<String> = SettingsManager.getRemoteDnsServers()

    /**
     * `pref_domestic_dns` — the resolver for names that are routed directly, which
     * the core calls `direct-nameserver`.
     */
    fun domesticDnsServers(context: Context): List<String> = SettingsManager.getDomesticDnsServers()

    /**
     * `pref_fake_dns_enabled` — the core's fake-ip mode. Off is MikuRay's default
     * and means "do not force it", not "force real addresses": a profile is
     * entitled to its own choice.
     */
    fun fakeDnsEnabled(context: Context): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED, false)

    /** `pref_fake_dns_ip_pool` — the range fake addresses are handed out from. */
    fun fakeDnsPool(context: Context): String? =
        MmkvManager.decodeSettingsString(AppConfig.PREF_FAKE_DNS_IP_POOL)?.trim()?.takeIf { it.isNotEmpty() }

    /** `pref_local_dns_enabled` — answer direct names from the system resolver first. */
    fun localDnsEnabled(context: Context): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED, false)

    /** `pref_prefer_ipv6` — ask for AAAA records as well. */
    fun preferIpv6(context: Context): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6, false)

    /**
     * Layers the ported core screen's resolvers onto a DNS section: the remote one
     * is what proxied names use (the core's main `nameserver`), the domestic one is
     * what direct names use, and the local-DNS switch asks the system resolver
     * first — which is the core's `direct-nameserver` doing exactly that.
     *
     * Separate from the store read so the mapping can be checked in a unit test;
     * the core is handed the result verbatim.
     */
    internal fun applyDnsOverrides(
        dns: org.json.JSONObject,
        remote: List<String>,
        domestic: List<String>,
        localDns: Boolean,
        fakeDns: Boolean,
        fakePool: String?,
        preferIpv6: Boolean,
    ): org.json.JSONObject {
        remote.takeIf { it.isNotEmpty() }?.let { dns.put("nameserver", org.json.JSONArray(it)) }
        val direct = domestic.toMutableList()
        if (localDns && !direct.contains("system")) direct.add(0, "system")
        if (direct.isNotEmpty()) dns.put("direct-nameserver", org.json.JSONArray(direct))
        if (fakeDns) {
            dns.put("enhanced-mode", "fake-ip")
            fakePool?.let { dns.put("fake-ip-range", it) }
        }
        if (preferIpv6) dns.put("ipv6", true)
        return dns
    }

    enum class PerAppMode { ALL, ONLY_SELECTED, BYPASS_SELECTED }

    private const val MIN_MTU = 1280
    private const val MAX_MTU = 9_000
    private const val DEFAULT_MTU = 1500
    private const val KEEP_ALIVE_DEFAULT = "30"
}
