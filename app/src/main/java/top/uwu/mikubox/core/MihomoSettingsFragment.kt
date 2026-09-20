package top.uwu.mikubox.core

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.preference.*
import com.miku.ray.R
import com.miku.ray.extension.applyEdgeToEdgeListInsets
import com.miku.ray.ui.preference.CategoryStyleHelper
import com.miku.ray.ui.preference.SearchPreferenceHighlighter

/** Native settings retain the existing Core / VPN organization. */
class MihomoSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_core_settings)
        MihomoPreferenceBindings(this).bindCore()
        CategoryStyleHelper.applyToFragment(this)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        SearchPreferenceHighlighter.applyFromIntent(this)
        applyEdgeToEdgeListInsets()
    }
}

class MihomoVpnSettingsFragment : com.miku.ray.ui.preference.activity.VpnSettingsActivity.VpnSettingsFragment() {
    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        super.onCreatePreferences(bundle, rootKey)
        MihomoPreferenceBindings(this).bindVpn()
    }
}

private class MihomoPreferenceBindings(private val fragment: PreferenceFragmentCompat) {
    private val ctx get() = fragment.requireContext()
    private fun getString(id: Int) = fragment.getString(id)

    fun bindCore() {
        toggle(com.miku.ray.AppConfig.PREF_DYNAMIC_SOCKS_PORT,
            com.miku.ray.handler.MmkvManager.decodeSettingsBool(com.miku.ray.AppConfig.PREF_DYNAMIC_SOCKS_PORT, false)) {
            com.miku.ray.handler.MmkvManager.encodeSettings(com.miku.ray.AppConfig.PREF_DYNAMIC_SOCKS_PORT, it)
        }
        fragment.findPreference<ListPreference>(com.miku.ray.AppConfig.PREF_GEO_FILES_SOURCES)?.apply {
            isPersistent = false
            value = com.miku.ray.handler.MmkvManager.decodeSettingsString(com.miku.ray.AppConfig.PREF_GEO_FILES_SOURCES)
                ?: com.miku.ray.AppConfig.GEO_FILES_SOURCES.first()
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, newValue ->
                com.miku.ray.handler.MmkvManager.encodeSettings(com.miku.ray.AppConfig.PREF_GEO_FILES_SOURCES, newValue as String)
                true
            }
        }

        toggle("allow-lan", MihomoCoreSettings.allowLan(ctx)) { MihomoCoreSettings.setAllowLan(ctx, it) }
        toggle("unified-delay", MihomoCoreSettings.unifiedDelay(ctx)) { MihomoCoreSettings.setUnifiedDelay(ctx, it) }
        toggle("tcp-concurrent", MihomoCoreSettings.tcpConcurrent(ctx)) { MihomoCoreSettings.setTcpConcurrent(ctx, it) }
        text("mixed-port", MihomoCoreSettings.mixedPort(ctx).toString(), { it.toIntOrNull() in 1..65535 }) { CoreOverrides.setMixedPort(ctx, it.toIntOrNull() ?: 0) }
        choice("log-level", MihomoCoreSettings.LogLevel.entries.map { it.name }, MihomoCoreSettings.LogLevel.entries.map { it.value ?: getString(R.string.mihomo_follow_profile) }, MihomoCoreSettings.logLevel(ctx).name) { MihomoCoreSettings.setLogLevel(ctx, MihomoCoreSettings.LogLevel.valueOf(it)) }
        choice("find-process-mode", CoreOverrides.FindProcess.entries.map { it.name }, CoreOverrides.FindProcess.entries.map { it.value ?: getString(R.string.mihomo_follow_profile) }, CoreOverrides.findProcess(ctx).name) { CoreOverrides.setFindProcess(ctx, CoreOverrides.FindProcess.valueOf(it)) }
        choice("geodata-loader", CoreOverrides.GeodataLoader.entries.map { it.name }, CoreOverrides.GeodataLoader.entries.map { it.value ?: getString(R.string.mihomo_follow_profile) }, CoreOverrides.geodataLoader(ctx).name) { CoreOverrides.setGeodataLoader(ctx, CoreOverrides.GeodataLoader.valueOf(it)) }
        choice("sniffer.enable", listOf("-1", "1", "0"), listOf(getString(R.string.mihomo_follow_profile), "true", "false"), CoreOverrides.sniffing(ctx).toString()) { CoreOverrides.setSniffing(ctx, it.toInt()) }
        choice("sniffer.override-destination", listOf("-1", "1", "0"), listOf(getString(R.string.mihomo_follow_profile), "true", "false"), CoreOverrides.sniffOverrideDestination(ctx).toString()) { CoreOverrides.setSniffOverrideDestination(ctx, it.toInt()) }
        choice("geodata-mode", listOf("-1", "1", "0"), listOf(getString(R.string.mihomo_follow_profile), "true", "false"), CoreOverrides.geodataMode(ctx).toString()) { CoreOverrides.setGeodataMode(ctx, it.toInt()) }
        choice("dns.cache-algorithm", DnsOverrides.CacheAlgorithm.entries.map { it.name }, DnsOverrides.CacheAlgorithm.entries.map { it.value ?: getString(R.string.mihomo_follow_profile) }, DnsOverrides.cacheAlgorithm(ctx).name) { DnsOverrides.setCacheAlgorithm(ctx, DnsOverrides.CacheAlgorithm.valueOf(it)) }
        text("dns.nameserver", DnsOverrides.nameserver(ctx), { true }) { DnsOverrides.setNameserver(ctx, it) }
        text("dns.default-nameserver", DnsOverrides.defaultNameserver(ctx), { true }) { DnsOverrides.setDefaultNameserver(ctx, it) }
        text("dns.proxy-server-nameserver", DnsOverrides.proxyServerNameserver(ctx), { true }) { DnsOverrides.setProxyServerNameserver(ctx, it) }
        text("dns.direct-nameserver", DnsOverrides.directNameserver(ctx), { true }) { DnsOverrides.setDirectNameserver(ctx, it) }
        text("dns.fallback", DnsOverrides.fallback(ctx), { true }) { DnsOverrides.setFallback(ctx, it) }
        choice("dns.respect-rules", listOf("-1", "1", "0"), listOf(getString(R.string.mihomo_follow_profile), "true", "false"), DnsOverrides.respectRules(ctx).toString()) { DnsOverrides.setRespectRules(ctx, it.toInt()) }
        choice("dns.use-hosts", listOf("-1", "1", "0"), listOf(getString(R.string.mihomo_follow_profile), "true", "false"), DnsOverrides.useHosts(ctx).toString()) { DnsOverrides.setUseHosts(ctx, it.toInt()) }
        choice("dns.use-system-hosts", listOf("-1", "1", "0"), listOf(getString(R.string.mihomo_follow_profile), "true", "false"), DnsOverrides.useSystemHosts(ctx).toString()) { DnsOverrides.setUseSystemHosts(ctx, it.toInt()) }
        choice("dns.prefer-h3", listOf("-1", "1", "0"), listOf(getString(R.string.mihomo_follow_profile), "true", "false"), DnsOverrides.preferH3(ctx).toString()) { DnsOverrides.setPreferH3(ctx, it.toInt()) }
        choice("dns.direct-nameserver-follow-policy", listOf("-1", "1", "0"), listOf(getString(R.string.mihomo_follow_profile), "true", "false"), DnsOverrides.directFollowPolicy(ctx).toString()) { DnsOverrides.setDirectFollowPolicy(ctx, it.toInt()) }
    }

    fun bindVpn() {
        text("keep-alive-idle", CoreOverrides.keepAliveIdle(ctx).takeIf { it > 0 }?.toString().orEmpty(),
            { it.isEmpty() || it.toIntOrNull() in 1..86400 }) { CoreOverrides.setKeepAliveIdle(ctx, it.toIntOrNull() ?: 0) }
        toggle("ipv6", MihomoCoreSettings.ipv6(ctx)) { MihomoCoreSettings.setIpv6(ctx, it) }
        choice("tun.stack", MihomoCoreSettings.TunStack.entries.map { it.name }, MihomoCoreSettings.TunStack.entries.map { it.value ?: getString(R.string.mihomo_follow_profile) }, MihomoCoreSettings.tunStack(ctx).name) { MihomoCoreSettings.setTunStack(ctx, MihomoCoreSettings.TunStack.valueOf(it)) }
        choice("disable-keep-alive", listOf("-1", "1", "0"), listOf(getString(R.string.mihomo_follow_profile), "true", "false"), CoreOverrides.disableKeepAlive(ctx).toString()) { CoreOverrides.setDisableKeepAlive(ctx, it.toInt()) }
        text("keep-alive-interval", CoreOverrides.keepAliveInterval(ctx).takeIf { it > 0 }?.toString().orEmpty(), { it.isEmpty() || it.toIntOrNull() in 1..86400 }) { CoreOverrides.setKeepAliveInterval(ctx, it.toIntOrNull() ?: 0) }
        choice("dns.enhanced-mode", DnsOverrides.EnhancedMode.entries.map { it.name }, DnsOverrides.EnhancedMode.entries.map { it.value ?: getString(R.string.mihomo_follow_profile) }, DnsOverrides.enhancedMode(ctx).name) { DnsOverrides.setEnhancedMode(ctx, DnsOverrides.EnhancedMode.valueOf(it)) }
        text("dns.fake-ip-range", DnsOverrides.fakeIpRange(ctx), { true }) { DnsOverrides.setFakeIpRange(ctx, it) }
        text("dns.fake-ip-filter", DnsOverrides.fakeIpFilter(ctx), { true }) { DnsOverrides.setFakeIpFilter(ctx, it) }
        choice("dns.ipv6", listOf("-1", "1", "0"), listOf(getString(R.string.mihomo_follow_profile), "true", "false"), DnsOverrides.ipv6(ctx).toString()) { DnsOverrides.setIpv6(ctx, it.toInt()) }
        toggle(com.miku.ray.AppConfig.PREF_KEEP_AWAKE, AndroidVpnSettings.keepAwake(ctx)) {
            AndroidVpnSettings.setKeepAwake(ctx, it)
        }
    }

    private fun toggle(key: String, value: Boolean, save: (Boolean) -> Unit) {
        requireNotNull(fragment.findPreference<SwitchPreferenceCompat>(key)).apply {
            isPersistent = false
            isChecked = value
            setOnPreferenceChangeListener { _, newValue -> save(newValue as Boolean); true }
        }
    }

    private fun choice(key: String, values: List<String>, labels: List<String>, current: String, save: (String) -> Unit) {
        requireNotNull(fragment.findPreference<ListPreference>(key)).apply {
            isPersistent = false
            entries = labels.toTypedArray()
            entryValues = values.toTypedArray()
            value = current
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, newValue -> save(newValue as String); true }
        }
    }

    private fun text(key: String, current: String, valid: (String) -> Boolean, save: (String) -> Unit) {
        requireNotNull(fragment.findPreference<EditTextPreference>(key)).apply {
            isPersistent = false
            text = current
            summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                it.text?.takeIf(String::isNotBlank) ?: getString(R.string.mihomo_follow_profile)
            }
            dialogMessage = getString(R.string.mihomo_settings_hint)
            setOnPreferenceChangeListener { _, newValue ->
                val value = (newValue as String).trim()
                if (valid(value)) { save(value); true } else {
                    Toast.makeText(context, R.string.mihomo_invalid_value, Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }
    }

}
