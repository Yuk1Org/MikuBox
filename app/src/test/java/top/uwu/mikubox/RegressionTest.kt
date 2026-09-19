package top.uwu.mikubox

import android.app.Application
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.uwu.mikubox.core.BackupManager
import top.uwu.mikubox.core.MihomoCoreSettings
import top.uwu.mikubox.core.MikuRayProfileDescription
import top.uwu.mikubox.core.MikuRayRoutingBridge
import top.uwu.mikubox.core.MikuRayBridgeContext
import top.uwu.mikubox.core.MikuRaySubscriptions
import top.uwu.mikubox.core.MikuRaySettings
import com.miku.ray.dto.entities.RulesetItem
import top.uwu.mikubox.profile.MihomoProfileStore
import top.uwu.mikubox.profile.MihomoTrafficStore
import top.uwu.mikubox.profile.MihomoSubscriptionDecoder
import top.uwu.mikubox.service.CoreOwnership

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RegressionTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test fun malformedLaterStoreDoesNotOverwriteEarlierStore() {
        val prefs = context.getSharedPreferences("miku_app_settings", 0)
        prefs.edit().putString("sentinel", "original").commit()
        val stores = JSONObject()
            .put("miku_app_settings", JSONObject().put("sentinel", entry("s", "replacement")))
            .put("mihomo_profiles", JSONObject().put("profiles", entry("unknown", "bad")))
        assertThrows(IllegalStateException::class.java) {
            BackupManager.import(context, JSONObject().put("version", 1).put("stores", stores).toString())
        }
        assertEquals("original", prefs.getString("sentinel", null))
    }

    @Test fun unsupportedBackupVersionDoesNotChangePreferences() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupManager.import(context, """{"version":99,"stores":{}}""")
        }
    }

    @Test fun backupRoundTripPreservesPreferenceTypes() {
        val prefs = context.getSharedPreferences("miku_app_settings", 0)
        prefs.edit().putString("s", "value").putInt("i", 8).putLong("l", 9000000000L)
            .putFloat("f", 1.5f).putBoolean("b", true).putStringSet("ss", setOf("a", "b")).commit()
        val original = prefs.all
        val backup = BackupManager.export(context)
        prefs.edit().clear().commit()
        BackupManager.import(context, backup)
        assertEquals(original, prefs.all)
    }

    @Test fun subscriptionRefreshPreservesConcurrentMetadataChanges() {
        val source = seedProfile()
        seedProfile(pinned = true, name = "renamed")
        MihomoProfileStore.updateSubscription(context, source, "new config")
        val result = MihomoProfileStore.profiles(context).single()
        assertTrue(result.pinned)
        assertEquals("renamed", result.name)
        assertEquals("new config", result.config)
    }

    @Test fun subscriptionRefreshRejectsChangedContentAndDeletedProfiles() {
        val source = seedProfile()
        seedProfile(config = "edited")
        assertThrows(IllegalStateException::class.java) {
            MihomoProfileStore.updateSubscription(context, source, "stale download")
        }
        assertEquals("edited", MihomoProfileStore.profiles(context).single().config)
        context.getSharedPreferences("mihomo_profiles", 0).edit().putString("profiles", "[]").commit()
        assertThrows(IllegalStateException::class.java) {
            MihomoProfileStore.updateSubscription(context, source, "stale download")
        }
        assertTrue(MihomoProfileStore.profiles(context).isEmpty())
    }

    @Test fun inactiveProfileDoesNotReadLiveNativeTraffic() {
        context.getSharedPreferences("mihomo_traffic", 0).edit()
            .putString("inactive.proxy", """{"node":{"upload":12,"download":34}}""").commit()
        // Loading the Android JNI core on this JVM would fail: inactive profiles
        // must return their own persisted counters without consulting it.
        assertEquals(mapOf("node" to MihomoTrafficStore.Totals(12, 34)),
            MihomoTrafficStore.proxyTotals(context, "inactive"))
    }

    @Test fun directOnlyConfigImportsWithoutProxyNodes() {
        val config = "mode: rule\nrules:\n  - MATCH,DIRECT"
        assertEquals(config, MihomoSubscriptionDecoder.toMihomoConfig(context, config))
        val encoded = android.util.Base64.encodeToString(config.toByteArray(), android.util.Base64.NO_WRAP)
        assertEquals(config, MihomoSubscriptionDecoder.toMihomoConfig(context, encoded))
    }

    @Test fun commentMentioningProxiesIsNotAConfiguration() {
        assertThrows(IllegalStateException::class.java) {
            MihomoSubscriptionDecoder.toMihomoConfig(context, "# proxies: not a config")
        }
    }

    /**
     * The routing screen lists the selected profile's own rules, and the ones it
     * has switched off must not reach the core — while everything else keeps the
     * profile's order, which is what leaves its `MATCH` last.
     */
    @Test fun profileRulesKeepTheirOrderAndDropWhatIsSwitchedOff() {
        val items = mutableListOf(
            rulesetItem("DOMAIN-SUFFIX,google.com,PROXY", enabled = true),
            rulesetItem("AND,((NETWORK,udp),(DST-PORT,443)),REJECT", enabled = false),
            rulesetItem("MATCH,PROXY", enabled = true),
        )
        assertEquals(
            listOf("DOMAIN-SUFFIX,google.com,PROXY", "MATCH,PROXY"),
            MikuRayRoutingBridge.rulesFor(items, listOf("MATCH,DIRECT")),
        )
    }

    /**
     * A rule written in the routing screen still wins over the profile's own, and
     * the profile's list is not appended twice when the screen already holds it.
     */
    @Test fun authoredRulesPrecedeProfileRules() {
        val authored = RulesetItem(
            remarks = "DOMAIN-SUFFIX",
            domain = listOf("example.test"),
            outboundTag = "block",
        )
        val fromProfile = rulesetItem("MATCH,PROXY", enabled = true)
        assertEquals(
            listOf("DOMAIN-SUFFIX,example.test,REJECT", "MATCH,PROXY"),
            MikuRayRoutingBridge.rulesFor(mutableListOf(authored, fromProfile), listOf("MATCH,DIRECT")),
        )
        // Nothing from a profile in the store: the profile's rules are appended.
        assertEquals(
            listOf("DOMAIN-SUFFIX,example.test,REJECT", "MATCH,DIRECT"),
            MikuRayRoutingBridge.rulesFor(mutableListOf(authored), listOf("MATCH,DIRECT")),
        )
    }

    /**
     * The mappings behind the ported VPN/core screens, checked where they can be:
     * the settings store itself is a native MMKV and only exists on a device, so
     * the store reads are verified there and the parsing here.
     */
    @Test fun portedSettingsMappings() {
        assertEquals(1400, MikuRaySettings.mtuFrom("1400"))
        assertEquals(1500, MikuRaySettings.mtuFrom(null))
        assertEquals(1280, MikuRaySettings.mtuFrom("900"))   // below what a TUN accepts
        assertEquals(9000, MikuRaySettings.mtuFrom("65535")) // and above

        assertTrue("only \"1\" bypasses the LAN", MikuRaySettings.bypassLanFrom("1"))
        assertFalse(MikuRaySettings.bypassLanFrom("2"))
        assertFalse("follow config leaves the decision to the profile", MikuRaySettings.bypassLanFrom("0"))
        assertFalse(MikuRaySettings.bypassLanFrom(null))
        assertTrue("public ranges are the bypass routes", MikuRaySettings.publicRoutes().isNotEmpty())

        assertEquals(MikuRaySettings.PerAppMode.ALL, MikuRaySettings.perAppModeFrom(false, true))
        assertEquals(
            MikuRaySettings.PerAppMode.BYPASS_SELECTED,
            MikuRaySettings.perAppModeFrom(true, true),
        )
        assertEquals(
            MikuRaySettings.PerAppMode.ONLY_SELECTED,
            MikuRaySettings.perAppModeFrom(true, false),
        )
    }

    /** The DNS section the core is started with carries the ported resolvers. */
    @Test fun dnsOverrideUsesThePortedResolvers() {
        val dns = MikuRaySettings.applyDnsOverrides(
            dns = JSONObject(),
            remote = listOf("https://dns.example/dns-query"),
            domestic = listOf("223.5.5.5"),
            localDns = true,
            fakeDns = true,
            fakePool = "198.19.0.1/16",
            preferIpv6 = true,
        )
        assertEquals("https://dns.example/dns-query", dns.getJSONArray("nameserver").getString(0))
        assertEquals(
            listOf("system", "223.5.5.5"),
            (0 until dns.getJSONArray("direct-nameserver").length())
                .map { dns.getJSONArray("direct-nameserver").getString(it) },
        )
        assertEquals("fake-ip", dns.getString("enhanced-mode"))
        assertEquals("198.19.0.1/16", dns.getString("fake-ip-range"))
        assertTrue(dns.getBoolean("ipv6"))
    }

    /**
     * A configuration with one proxy is described as that server — the badge, the
     * address and the transport — and everything else stays a custom config. That
     * is what the row shows, so the parse decides what the user sees.
     */
    @Test fun singleProxyConfigurationsAreDescribedAsServers() {
        val oneNode = """
            mixed-port: 7890
            proxies:
              - name: oracle
                type: vless
                server: 104.17.3.81
                port: 443
                network: ws
                tls: true
            proxy-groups:
              - name: PROXY
                type: select
            rules:
              - MATCH,PROXY
        """.trimIndent()
        val node = requireNotNull(MikuRayProfileDescription.singleNode(oneNode))
        assertEquals(com.miku.ray.enums.EConfigType.VLESS, node.configType)
        assertEquals("104.17.3.81", node.server)
        assertEquals("443", node.port)
        assertEquals("ws", node.network)
        assertEquals("tls", node.security)

        // A flow-style entry reads the same way.
        val flow = "proxies:\n  - {name: a, type: trojan, server: a.example, port: 8443}\n"
        assertEquals(com.miku.ray.enums.EConfigType.TROJAN,
            MikuRayProfileDescription.singleNode(flow)?.configType)

        // Several proxies, none, or a provider means "custom configuration".
        val twoNodes = "proxies:\n  - {name: a, type: vless, server: a.example, port: 1}\n" +
            "  - {name: b, type: ss, server: b.example, port: 2}\n"
        assertNull(MikuRayProfileDescription.singleNode(twoNodes))
        assertNull(MikuRayProfileDescription.singleNode("mixed-port: 7890\nrules:\n  - MATCH,DIRECT"))
        assertNull(MikuRayProfileDescription.singleNode("proxy-providers:\n  a:\n    url: x\n"))
        assertNull("a proxy without a server is not a server", 
            MikuRayProfileDescription.singleNode("proxies:\n  - {name: a, type: direct}\n"))
    }

    /**
     * The subscription screen's own model, over this app's profile store: adding
     * one has to produce the profile the tunnel would use, with the URL, the
     * schedule and the proxy flag the screen shows.
     */
    @Test fun portedSubscriptionScreenWritesRealProfiles() {
        val bridge = MikuRaySubscriptionsForTest()
        val direct = MihomoProfileStore.createSubscription(
            context = context,
            name = "direct",
            url = "https://example.test/direct",
            intervalMinutes = 60,
            updateThroughProxy = false,
        )
        assertEquals("direct", MihomoProfileStore.profiles(context).single { it.id == direct.id }.name)
        MihomoProfileStore.remove(context, direct.id)

        val id = requireNotNull(
            bridge.upsert(
                id = null,
                name = "Oracle",
                url = "https://example.test/sub",
                autoUpdate = true,
                intervalMinutes = 120,
                throughProxy = true,
            ),
        )

        val listed = bridge.list().single { it.id == id }
        assertEquals("Oracle", listed.name)
        assertEquals("https://example.test/sub", listed.url)
        assertTrue(listed.autoUpdate)
        assertEquals(120L, listed.intervalMinutes)
        assertTrue(listed.throughProxy)

        // Editing keeps the same profile; turning automatic updates off is stored
        // as "no interval", which is what the scheduler keys off.
        assertEquals(
            id,
            bridge.upsert(id, "Oracle 2", "https://example.test/sub2", false, 120, false),
        )
        val edited = bridge.list().single { it.id == id }
        assertEquals("Oracle 2", edited.name)
        assertFalse(edited.autoUpdate)
        assertEquals(0L, edited.intervalMinutes)
        assertFalse(edited.throughProxy)

        bridge.remove(id)
        assertTrue(bridge.list().none { it.id == id })
    }

    /** The bridge needs an application context, which the test application is. */
    private inner class MikuRaySubscriptionsForTest : com.miku.ray.MikuSubscriptions.Impl {
        private val delegate = MikuRaySubscriptions

        init {
            MikuRayBridgeContext.attach(context)
        }

        override fun list() = delegate.list()

        override fun upsert(
            id: String?,
            name: String,
            url: String,
            autoUpdate: Boolean,
            intervalMinutes: Long,
            throughProxy: Boolean,
        ) = delegate.upsert(id, name, url, autoUpdate, intervalMinutes, throughProxy)

        override fun remove(id: String) = delegate.remove(id)

        override fun refresh(id: String): Boolean = true
    }

    private fun rulesetItem(line: String, enabled: Boolean): RulesetItem = RulesetItem(
        remarks = line.substringBefore(','),
        domain = listOf(line),
        outboundTag = line.substringAfterLast(','),
        enabled = enabled,
        locked = true,
        rawRule = line,
    )

    @Test fun obsoleteServiceCannotStopSuccessorAndStopIsIdempotent() {
        val ownership = CoreOwnership()
        val old = Any()
        val current = Any()
        var stops = 0
        ownership.claim(old)
        ownership.claim(current)
        ownership.release(old) { stops++ }
        assertEquals(0, stops)
        ownership.release(current) { stops++ }
        ownership.release(current) { stops++ }
        assertEquals(1, stops)
    }

    @Test fun coreHandoverFinishesPreviousSessionOnlyOnce() {
        val ownership = CoreOwnership()
        val old = Any()
        val next = Any()
        val events = mutableListOf<String>()
        ownership.claim(old)
        ownership.releaseCurrent { events += "finish old" }
        events += "start next"
        ownership.claim(next)
        ownership.release(old) { events += "late old stop" }
        ownership.release(next) { events += "finish next" }
        ownership.releaseCurrent { events += "duplicate stop" }
        assertEquals(listOf("finish old", "start next", "finish next"), events)
    }

    private fun entry(type: String, value: Any) = JSONObject().put("t", type).put("v", value)

    private fun seedProfile(pinned: Boolean = false, name: String = "original", config: String = "old config"):
        MihomoProfileStore.Profile {
        val profile = JSONObject().put("id", "test").put("name", name).put("config", config)
            .put("subscriptionUrl", "https://example.test/sub").put("updatedAtMillis", 1)
            .put("pinned", pinned)
        context.getSharedPreferences("mihomo_profiles", 0).edit()
            .putString("profiles", JSONArray().put(profile).toString()).commit()
        return MihomoProfileStore.profiles(context).single()
    }
}
