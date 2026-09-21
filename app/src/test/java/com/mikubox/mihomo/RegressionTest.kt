package com.mikubox.mihomo

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
import com.mikubox.mihomo.core.BackupManager
import com.mikubox.mihomo.core.MihomoCoreSettings
import com.mikubox.mihomo.core.MikuRayProfileDescription
import com.mikubox.mihomo.core.MikuRayBridgeContext
import com.mikubox.mihomo.core.MikuRaySubscriptions
import com.mikubox.mihomo.core.AndroidVpnSettings
import com.mikubox.mihomo.profile.MihomoProfileStore
import com.mikubox.mihomo.profile.MihomoTrafficStore
import com.mikubox.mihomo.profile.MihomoSubscriptionDecoder
import com.mikubox.mihomo.service.CoreOwnership

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RegressionTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test fun countryNameDoesNotHideValidCountryCode() {
        val info = com.miku.ray.dto.IPAPIInfo(country = "Hong Kong", countryCode = "hk")
        assertEquals("HK", com.miku.ray.handler.SpeedtestManager.countryCode(info))
        assertNull(com.miku.ray.handler.SpeedtestManager.countryCode(info.copy(countryCode = null)))
    }

    @Test fun nativeProbeRemovesListenersAndKeepsProxyDefinition() {
        val original = """
            mixed-port: 7890
            allow-lan: true
            authentication: ["user:secret"]
            external-controller: 0.0.0.0:9090
            listeners: [{name: leaked, type: http, port: 1234}]
            proxies: [{name: Test, type: socks5, server: example.com, port: 1080}]
            proxy-groups: [{name: Choose, type: select, proxies: [Test]}]
            rules: [MATCH,DIRECT]
        """.trimIndent()
        val (prepared, target) = com.mikubox.mihomo.core.NativeProfileProbe.prepare(original, 18090)
        val raw = org.yaml.snakeyaml.Yaml().load<Map<String, Any>>(prepared)
        assertEquals("Choose", target)
        assertEquals(18090, raw["mixed-port"])
        assertEquals(false, raw["allow-lan"])
        assertEquals(emptyList<String>(), raw["authentication"])
        assertFalse(raw.containsKey("listeners"))
        assertFalse(raw.containsKey("external-controller"))
        assertTrue(prepared.contains("example.com"))
    }

    @Test fun profileProbeRefusesToReplaceMainProcessCore() {
        MikuRayBridgeContext.attach(context)
        assertThrows(IllegalStateException::class.java) {
            com.mikubox.mihomo.core.NativeProfileProbe.delay("rules: [MATCH,DIRECT]", "https://example.com")
        }
    }

    @Test fun nativeQuickUpdateRefreshesSubscriptionsWithScheduleDisabled() = withSubscriptionServer { url, requests, _ ->
        MikuRayBridgeContext.attach(context)
        com.miku.ray.MikuSubscriptions.install(MikuRaySubscriptions)
        val profile = MihomoProfileStore.createSubscription(context, "Quick update", url, 0)
        val result = com.miku.ray.MikuSubscriptions.refreshAll()
        assertEquals(1, result.successCount)
        assertEquals(1, requests.get())
        assertTrue(MihomoProfileStore.profiles(context).first { it.id == profile.id }.config.isNotBlank())
    }

    private fun withSubscriptionServer(test: (String, java.util.concurrent.atomic.AtomicInteger, java.util.concurrent.atomic.AtomicInteger) -> Unit) {
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val status = java.util.concurrent.atomic.AtomicInteger(200)
        val server = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.net.SocketException) { break }
                socket.use {
                    it.soTimeout = 5000
                    val reader = it.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* consume request headers */ }
                    requests.incrementAndGet()
                    val body = "mode: rule\nrules:\n  - MATCH,DIRECT\n".toByteArray()
                    val header = "HTTP/1.1 ${status.get()} Response\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    it.getOutputStream().apply { write(header.toByteArray()); write(body); flush() }
                }
            }
        }.apply { isDaemon = true; start() }
        try { test("http://127.0.0.1:${server.localPort}/sub", requests, status) }
        finally { server.close(); worker.join(1000) }
    }

    @Test fun subscriptionEditorFetchesImmediatelyWithAutomaticUpdatesDisabled() = withSubscriptionServer { url, requests, status ->
        MikuRayBridgeContext.attach(context)
        com.miku.ray.MikuSubscriptions.install(MikuRaySubscriptions)
        kotlinx.coroutines.runBlocking {
            var id: String? = null
            assertTrue(com.miku.ray.MikuSubscriptions.saveAndRefresh(null, "New", url, false, 1440, false) { id = it })
            val saved = MihomoProfileStore.profiles(context).single { it.id == id }
            assertTrue(saved.config.contains("MATCH,DIRECT"))
            assertTrue(saved.updatedAtMillis > 0)
            assertEquals(0L, saved.updateIntervalMinutes)
            assertEquals(1, requests.get())
            assertTrue(com.miku.ray.MikuSubscriptions.saveAndRefresh(id, "Renamed", url, false, 0, false))
            assertEquals("Metadata-only save should not download again", 1, requests.get())
            assertTrue(com.miku.ray.MikuSubscriptions.saveAndRefresh(id, "New source", "$url?changed", false, 0, false))
            assertEquals(2, requests.get())
            status.set(503)
            assertFalse(com.miku.ray.MikuSubscriptions.saveAndRefresh(id, "Fail source", "$url?retry", false, 0, false))
            assertEquals(0L, MihomoProfileStore.profiles(context).single { it.id == id }.updatedAtMillis)
            status.set(200)
            assertTrue(com.miku.ray.MikuSubscriptions.saveAndRefresh(id, "Fail source", "$url?retry", false, 0, false))
            assertEquals(4, requests.get())
        }
    }

    @Test fun failedInitialFetchCanRetrySameSubscriptionWithoutEnablingSchedule() = withSubscriptionServer { url, requests, status ->
        MikuRayBridgeContext.attach(context)
        com.miku.ray.MikuSubscriptions.install(MikuRaySubscriptions)
        kotlinx.coroutines.runBlocking {
            status.set(503)
            var id: String? = null
            assertFalse(com.miku.ray.MikuSubscriptions.saveAndRefresh(null, "Retry", url, false, 0, false) { id = it })
            val before = MihomoProfileStore.profiles(context).size
            assertTrue(MihomoProfileStore.profiles(context).single { it.id == id }.config.isEmpty())
            status.set(200)
            assertTrue(com.miku.ray.MikuSubscriptions.saveAndRefresh(id, "Retry", url, false, 0, false))
            assertEquals(before, MihomoProfileStore.profiles(context).size)
            assertEquals(0L, MihomoProfileStore.profiles(context).single { it.id == id }.updateIntervalMinutes)
            assertEquals(2, requests.get())
        }
    }

    @Test fun subscriptionImportLinksDownloadBeforeReportingSuccess() = withSubscriptionServer { url, requests, _ ->
        MikuRayBridgeContext.attach(context)
        val importer = com.mikubox.mihomo.profile.MihomoProfileImporter
        val direct = importer.importSubscription(context, "Direct", url, intervalMinutes = 0)
        assertTrue(direct.config.contains("MATCH,DIRECT"))
        assertEquals(0L, direct.updateIntervalMinutes)
        for (scheme in listOf("clash://install-config", "sn://subscription")) {
            val link = "$scheme?url=${android.net.Uri.encode(url)}&name=Imported"
            assertEquals(0 to 1, com.mikubox.mihomo.core.MikuRayProfiles.importContent(link))
        }
        assertEquals(0 to 1, com.mikubox.mihomo.core.MikuRayProfiles.importContent(url))
        assertEquals(4, requests.get())
        assertTrue(MihomoProfileStore.profiles(context).filter { it.subscriptionUrl == url }.all { it.config.contains("MATCH,DIRECT") })
    }

    @Test fun scriptBindingsUseStableIdsAndExplicitDisableSurvivesDeletion() {
        val library = com.mikubox.mihomo.core.ScriptLibrary
        val extras = com.mikubox.mihomo.core.CoreOverrides
        extras.setExtra(context, library.KEY, "")
        extras.setExtra(context, "script.enabled", "true")
        extras.setExtra(context, "script.source", "function main(c){c.mode='rule';return c}")
        val script = library.save(context, null, "Work", "function main(c){c.mode='global';return c}")
        library.bind(context, "profile-a", script.id)
        assertEquals(script.source, library.source(context, "profile-a"))
        assertTrue(library.source(context, "profile-b")!!.contains("rule"))
        library.bind(context, "profile-b", library.NONE)
        assertNull(library.source(context, "profile-b"))
        extras.setExtra(context, "script.enabled", "false")
        assertEquals(script.source, library.source(context, "profile-a"))
        extras.setExtra(context, "script.enabled", "true")
        val renamed = library.save(context, script.id, "Office", script.source)
        assertEquals(script.id, renamed.id)
        assertEquals(script.source, library.source(context, "profile-a"))
        library.delete(context, script.id)
        assertEquals(library.NONE, library.read(context).bindings["profile-a"])
        assertNull(library.source(context, "profile-a"))
        library.bind(context, "profile-a", null)
        assertTrue(library.source(context, "profile-a")!!.contains("rule"))
        extras.setExtra(context, library.KEY, "")
    }

    @Test fun scriptLibraryRejectsDuplicatesAndMalformedBackupBeforeAnyStoreChanges() {
        val library = com.mikubox.mihomo.core.ScriptLibrary
        val extras = com.mikubox.mihomo.core.CoreOverrides
        extras.setExtra(context, library.KEY, "")
        val script = library.save(context, null, "Original", "function main(c){return c}")
        assertTrue(runCatching { library.save(context, null, "Original", script.source) }.isFailure)
        assertTrue(runCatching { library.bind(context, "a", "missing") }.isFailure)
        val backup = JSONObject(BackupManager.export(context))
        backup.getJSONObject("stores").getJSONObject("miku_core_overrides")
            .getJSONObject("extra.script.library").put("v", "{broken")
        assertTrue(runCatching { BackupManager.import(context, backup.toString()) }.isFailure)
        assertEquals(listOf(script), library.read(context).scripts)
        extras.setExtra(context, library.KEY, "")
    }

    @Test fun boundScriptSurvivesProfileUpdateAndBackupAndExplicitProfileSnapshot() {
        val library = com.mikubox.mihomo.core.ScriptLibrary
        val extras = com.mikubox.mihomo.core.CoreOverrides
        extras.setExtra(context, library.KEY, "")
        val a = MihomoProfileStore.create(context, "A", "rules: [MATCH,DIRECT]")
        val b = MihomoProfileStore.create(context, "B", "rules: [MATCH,DIRECT]")
        val script = library.save(context, null, "For A", "function main(c){c.mode='global';return c}")
        library.bind(context, a.id, script.id)
        library.bind(context, b.id, library.NONE)
        MihomoProfileStore.update(context, a.copy(name = "Updated A", config = "mode: rule"))
        MihomoProfileStore.select(context, b.id)
        assertEquals(script.source, JSONObject(MihomoCoreSettings.overridesJson(context, profileId = a.id)).getString("miku-override-script"))
        assertFalse(JSONObject(MihomoCoreSettings.overridesJson(context)).has("miku-override-script"))
        val backup = BackupManager.export(context)
        library.delete(context, script.id)
        BackupManager.import(context, backup)
        assertEquals(script.source, library.source(context, a.id))
        assertNull(library.source(context, b.id))
        MihomoProfileStore.remove(context, a.id)
        assertFalse(library.read(context).bindings.containsKey(a.id))
        extras.setExtra(context, library.KEY, "")
    }

    @Test fun onDemandDecisionsHandlePermissionsAndExactSsidMatching() {
        val settings = com.mikubox.mihomo.core.OnDemandSettings
        val wifi = com.mikubox.mihomo.core.OnDemandSettings.Transport.WIFI
        val cellular = com.mikubox.mihomo.core.OnDemandSettings.Transport.CELLULAR
        val allowed = setOf(wifi, cellular)
        val excluded = settings.parseSsids("Home Wi-Fi\n公司网络\nHome Wi-Fi\n")
        assertEquals(2, excluded.size)
        assertEquals(com.mikubox.mihomo.core.OnDemandSettings.Decision.DISCONNECT, settings.decide(null, allowed, excluded))
        assertEquals(com.mikubox.mihomo.core.OnDemandSettings.Decision.WAIT_FOR_SSID, settings.decide(com.mikubox.mihomo.core.OnDemandSettings.NetworkState(wifi), allowed, excluded))
        assertEquals(com.mikubox.mihomo.core.OnDemandSettings.Decision.DISCONNECT, settings.decide(com.mikubox.mihomo.core.OnDemandSettings.NetworkState(wifi, "Home Wi-Fi"), allowed, excluded))
        assertEquals(com.mikubox.mihomo.core.OnDemandSettings.Decision.CONNECT, settings.decide(com.mikubox.mihomo.core.OnDemandSettings.NetworkState(wifi, "home wi-fi"), allowed, excluded))
        assertEquals(com.mikubox.mihomo.core.OnDemandSettings.Decision.CONNECT, settings.decide(com.mikubox.mihomo.core.OnDemandSettings.NetworkState(cellular), allowed, excluded))
        assertEquals(com.mikubox.mihomo.core.OnDemandSettings.Decision.DISCONNECT, settings.decide(com.mikubox.mihomo.core.OnDemandSettings.NetworkState(cellular), setOf(wifi), excluded))
        assertTrue(runCatching { settings.parseSsids("中".repeat(11)) }.isFailure)
    }

    @Test fun automationSettingsRoundTripThroughBackupAndOnlyEnabledScriptIsSent() {
        val settings = com.mikubox.mihomo.core.OnDemandSettings
        val overrides = com.mikubox.mihomo.core.CoreOverrides
        settings.prefs(context).edit().clear().putBoolean("enabled", true).putString("excluded", "Home").commit()
        overrides.setExtra(context, "script.source", "function main(c) { return c; }")
        overrides.setExtra(context, "script.enabled", "false")
        assertFalse(JSONObject(MihomoCoreSettings.overridesJson(context)).has("miku-override-script"))
        overrides.setExtra(context, "script.enabled", "true")
        assertEquals("function main(c) { return c; }", JSONObject(MihomoCoreSettings.overridesJson(context)).getString("miku-override-script"))
        val backup = BackupManager.export(context)
        settings.prefs(context).edit().clear().commit()
        overrides.setExtra(context, "script.source", "")
        BackupManager.import(context, backup)
        assertTrue(settings.enabled(context))
        assertEquals(setOf("Home"), settings.excluded(context))
        assertEquals("function main(c) { return c; }", overrides.extra(context, "script.source"))
        settings.prefs(context).edit().clear().commit()
        overrides.setExtra(context, "script.enabled", "false")
    }

    @Test fun connectionClockTracksSuccessfulSessionsAndIgnoresWallClockChanges() {
        val status = com.mikubox.mihomo.service.ConnectionStatus
        status.update(context, com.mikubox.mihomo.service.ConnectionStatus.Phase.DISCONNECTED)
        status.update(context, com.mikubox.mihomo.service.ConnectionStatus.Phase.CONNECTING)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(5))
        assertEquals(0L, status.elapsedMillis())
        status.update(context, com.mikubox.mihomo.service.ConnectionStatus.Phase.CONNECTED)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(12))
        assertEquals(12000L, status.elapsedMillis())
        status.update(context, com.mikubox.mihomo.service.ConnectionStatus.Phase.CONNECTED)
        assertEquals(12000L, status.elapsedMillis())
        status.update(context, com.mikubox.mihomo.service.ConnectionStatus.Phase.DISCONNECTED)
        assertEquals(0L, status.elapsedMillis())
        status.update(context, com.mikubox.mihomo.service.ConnectionStatus.Phase.CONNECTED)
        assertEquals(0L, status.elapsedMillis())
        status.update(context, com.mikubox.mihomo.service.ConnectionStatus.Phase.DISCONNECTED)
    }

    @Test fun nativeControlsReachTheConfigAndDnsHijackCanBeDisabled() {
        val extras = com.mikubox.mihomo.core.CoreOverrides
        context.getSharedPreferences("miku_core_overrides", 0).edit().clear().commit()
        extras.setDnsHijack(context, "")
        extras.setFindProcess(context, com.mikubox.mihomo.core.CoreOverrides.FindProcess.ALWAYS)
        extras.setGeodataLoader(context, com.mikubox.mihomo.core.CoreOverrides.GeodataLoader.MEMORY)
        extras.setTlsVerification(context, 1)
        MihomoCoreSettings.setTcpConcurrent(context, true)
        MihomoCoreSettings.setUnifiedDelay(context, true)
        MihomoCoreSettings.setTunStack(context, MihomoCoreSettings.TunStack.MIXED)
        val config = JSONObject(MihomoCoreSettings.overridesJson(context, 1400))
        assertEquals(0, config.getJSONObject("tun").getJSONArray("dns-hijack").length())
        assertEquals("mixed", config.getJSONObject("tun").getString("stack"))
        assertTrue(config.getBoolean("tcp-concurrent"))
        assertTrue(config.getBoolean("unified-delay"))
        assertTrue(config.getBoolean("miku-tls-verify"))
        assertEquals("always", config.getString("find-process-mode"))
        assertEquals("memconservative", config.getString("geodata-loader"))
        extras.setDnsHijack(context, "any:53\ntcp://any:53")
        assertEquals(2, extras.tunJson(context).getJSONArray("dns-hijack").length())
        extras.setTlsVerification(context, -1)
        assertFalse(extras.coreJson(context).has("miku-tls-verify"))
    }

    @Test fun additionalDnsAndAndroidSettingsPreserveNativeValues() {
        val extras = com.mikubox.mihomo.core.CoreOverrides
        extras.setExtra(context, "dns.fallback-filter.geoip-code", "CN")
        extras.setExtra(context, "dns.fallback-filter.geosite", "gfw\ncategory-ads-all")
        extras.setExtra(context, "dns.fallback-filter.domain", "+.example.com")
        extras.setExtra(context, "dns.append-system", "true")
        extras.setExtra(context, "vpn.allow-bypass", "false")
        extras.setExtra(context, "vpn.ipv6-inbound", "true")
        extras.setExtra(context, "vpn.proxy-exclusions", "*.example.com,localhost")
        val config = JSONObject(MihomoCoreSettings.overridesJson(context, 1500, "10.10.14.1/30", "fdfe:dcba:9876::1/126"))
        val filter = config.getJSONObject("dns").getJSONObject("fallback-filter")
        assertEquals("CN", filter.getString("geoip-code"))
        assertEquals(2, filter.getJSONArray("geosite").length())
        assertEquals("+.example.com", filter.getJSONArray("domain").getString(0))
        assertTrue(config.getBoolean("miku-append-system-dns"))
        assertEquals("10.10.14.1/30", config.getString("miku-tun-ipv4"))
        assertFalse(AndroidVpnSettings.allowBypass(context))
        assertTrue(AndroidVpnSettings.ipv6Inbound(context))
        assertEquals(listOf("*.example.com", "localhost"), AndroidVpnSettings.proxyExclusions(context))
        extras.setExtra(context, "authentication", "test:password:with:colon")
        assertEquals("test" to "password:with:colon", extras.proxyCredentials(context))
    }

    @Test fun customVpnRoutesRejectHostnamesAndNonNetworkAddresses() {
        val routes = com.mikubox.mihomo.core.VpnRoutes
        assertEquals(listOf("10.0.0.0/8", "2000::/3"), routes.parse("10.0.0.0/8\n2000::/3"))
        for (bad in listOf("example.com/8", "10.1.2.3/8", "0.0.0.0/99", "::/129")) {
            assertTrue(bad, runCatching { routes.parse(bad) }.isFailure)
        }
    }

    @Test fun plainProxyLinksAreNotMistakenForBase64Subscriptions() {
        for (name in listOf("ModeSmoke", "AuditSOCKS", "Test")) {
            val config = MihomoSubscriptionDecoder.toMihomoConfig(context, "socks5://127.0.0.1:11080#$name")
            assertTrue(config.contains(name))
            assertTrue(config.contains("11080"))
        }
    }

    @Test fun offlineCustomGlobalOnlyOffersItsDeclaredMembers() {
        val options = com.mikubox.mihomo.core.MikuRayRoutingMode.configuredOptions("""
            proxies: [{name: Node}]
            proxy-groups:
              - {name: Group, type: select, proxies: [Node]}
              - {name: GLOBAL, type: select, proxies: [Group]}
        """.trimIndent())
        assertEquals(listOf("Group"), options.map { it.name })
    }

    @Test fun offlineGlobalExitIsScopedToProfileAndSurvivesReload() {
        MikuRayBridgeContext.attach(context)
        context.getSharedPreferences("mihomo_profiles", 0).edit().clear().commit()
        context.getSharedPreferences("mihomo_routing_choices", 0).edit().clear().commit()
        val yaml = """
            proxies:
              - {name: Tokyo, type: socks5, server: 127.0.0.1, port: 11080}
            proxy-groups:
              - {name: Auto, type: select, proxies: [Tokyo]}
            rules: [MATCH,Auto]
        """.trimIndent()
        val first = MihomoProfileStore.create(context, "First", yaml)
        val routing = com.mikubox.mihomo.core.MikuRayRoutingMode
        assertEquals(listOf("Auto", "Tokyo", "DIRECT"), routing.state().options.map { it.name })
        assertTrue(routing.exit("Tokyo"))
        assertEquals("Tokyo", routing.state().exit)
        assertTrue(routing.mode("global"))
        assertEquals("global", routing.state().mode)
        val second = MihomoProfileStore.create(context, "Second", yaml)
        MihomoProfileStore.select(context, second.id)
        assertNull(routing.state().exit)
        assertTrue(routing.exit("Auto"))
        MihomoProfileStore.select(context, first.id)
        assertEquals("Tokyo", routing.state().exit)
        assertFalse(routing.exit("Missing"))
        assertFalse(routing.mode("unknown"))
        MihomoProfileStore.update(context, first.copy(config = "proxies: []"))
        assertNull(routing.state().exit)
    }

    @Test fun offlineModeFollowsYamlAndJsonAndListsGroupsBeforeNodes() {
        MikuRayBridgeContext.attach(context)
        context.getSharedPreferences("mihomo_profiles", 0).edit().clear().commit()
        MihomoCoreSettings.setMode(context, MihomoCoreSettings.ProxyMode.FOLLOW)
        val profile = MihomoProfileStore.create(context, "JSON", """{"mode":"global","proxies":[{"name":"Node"}],"proxy-groups":[{"name":"Group"}]}""")
        val routing = com.mikubox.mihomo.core.MikuRayRoutingMode
        assertEquals("global", routing.state().mode)
        assertEquals(listOf("Group", "Node", "DIRECT"), routing.state().options.map { it.name })
        assertTrue(routing.state().options.first().group)
        MihomoProfileStore.update(context, profile.copy(config = "mode: 'direct' # comment"))
        assertEquals("direct", routing.state().mode)
        assertTrue(routing.mode("rule"))
        assertEquals("rule", routing.state().mode)
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
     * The Android VPN interface options, checked where they can be:
     * the settings store itself is a native MMKV and only exists on a device, so
     * the store reads are verified there and the parsing here.
     */
    @Test fun androidVpnInterfaceOptions() {
        assertEquals(1400, AndroidVpnSettings.mtuFrom("1400"))
        assertEquals(1500, AndroidVpnSettings.mtuFrom(null))
        assertEquals(1280, AndroidVpnSettings.mtuFrom("900"))   // below what a TUN accepts
        assertEquals(9000, AndroidVpnSettings.mtuFrom("65535")) // and above

        assertTrue("only \"1\" bypasses the LAN", AndroidVpnSettings.bypassLanFrom("1"))
        assertFalse(AndroidVpnSettings.bypassLanFrom("2"))
        assertFalse("follow config leaves the decision to the profile", AndroidVpnSettings.bypassLanFrom("0"))
        assertFalse(AndroidVpnSettings.bypassLanFrom(null))
        assertTrue("public ranges are the bypass routes", AndroidVpnSettings.publicRoutes().isNotEmpty())

        assertEquals(AndroidVpnSettings.PerAppMode.ALL, AndroidVpnSettings.perAppModeFrom(false, true))
        assertEquals(
            AndroidVpnSettings.PerAppMode.BYPASS_SELECTED,
            AndroidVpnSettings.perAppModeFrom(true, true),
        )
        assertEquals(
            AndroidVpnSettings.PerAppMode.ONLY_SELECTED,
            AndroidVpnSettings.perAppModeFrom(true, false),
        )
    }

    @Test fun nativeDnsOverridesKeepProfileDefaultsAndWriteExactMihomoKeys() {
        val dns = com.mikubox.mihomo.core.DnsOverrides
        context.getSharedPreferences("miku_dns_overrides", 0).edit().clear().commit()
        assertEquals(0, dns.json(context).length())
        dns.setNameserver(context, "https://dns.example/dns-query")
        dns.setDirectNameserver(context, "system,223.5.5.5")
        dns.setEnhancedMode(context, com.mikubox.mihomo.core.DnsOverrides.EnhancedMode.FAKE_IP)
        dns.setIpv6(context, com.mikubox.mihomo.core.DnsOverrides.OFF)
        val result = dns.json(context)
        assertEquals("https://dns.example/dns-query", result.getJSONArray("nameserver").getString(0))
        assertEquals("system", result.getJSONArray("direct-nameserver").getString(0))
        assertEquals("fake-ip", result.getString("enhanced-mode"))
        assertFalse(result.getBoolean("ipv6"))
        dns.setNameserver(context, "")
        dns.setIpv6(context, com.mikubox.mihomo.core.DnsOverrides.UNSET)
        assertFalse(dns.json(context).has("nameserver"))
        assertFalse(dns.json(context).has("ipv6"))
    }

    @Test fun sniffOverrideCanChangeWithoutOverridingProfileEnable() {
        val extras = com.mikubox.mihomo.core.CoreOverrides
        context.getSharedPreferences("miku_core_overrides", 0).edit().clear().commit()
        extras.setSniffOverrideDestination(context, com.mikubox.mihomo.core.CoreOverrides.OFF)
        val result = extras.snifferJson(context)
        assertFalse(result.has("enable"))
        assertFalse(result.getBoolean("override-destination"))
    }

    @Test fun nativeOverridesNeverInjectLegacyRulesIntoDirectOnlyProfiles() {
        val config = "mode: rule\nrules:\n  - MATCH,DIRECT"
        MihomoProfileStore.create(context, "Direct only", config)
        val overrides = JSONObject(MihomoCoreSettings.overridesJson(context, 1500))
        assertFalse(overrides.has("rules"))
        assertEquals(MihomoCoreSettings.mixedPort(context), overrides.getInt("mixed-port"))
        assertEquals(1500, overrides.getJSONObject("tun").getInt("mtu"))
    }

    @Test fun nativeCoreSettingsNeedNoXrayStoreAndUseNativePortAndKeepAlive() {
        context.getSharedPreferences("mihomo_core_settings", 0).edit().clear().commit()
        context.getSharedPreferences("miku_core_overrides", 0).edit().clear().commit()
        val extras = com.mikubox.mihomo.core.CoreOverrides
        assertEquals(10808, MihomoCoreSettings.mixedPort(context))
        extras.setMixedPort(context, 10809)
        extras.setKeepAliveInterval(context, 42)
        extras.setKeepAliveIdle(context, 30)
        MihomoCoreSettings.setIpv6(context, true)
        MihomoCoreSettings.setAllowLan(context, false)
        assertEquals(10809, MihomoCoreSettings.mixedPort(context))
        assertEquals(42, extras.coreJson(context).getInt("keep-alive-interval"))
        assertEquals(30, extras.coreJson(context).getInt("keep-alive-idle"))
        assertTrue(MihomoCoreSettings.ipv6(context))
        assertFalse(MihomoCoreSettings.allowLan(context))
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

    @Test fun newSubscriptionCanDisableAutomaticUpdates() {
        val bridge = MikuRaySubscriptionsForTest()
        val id = requireNotNull(bridge.upsert(null, "manual", "https://example.test/sub", false, 60, false))
        assertEquals(0L, MihomoProfileStore.profiles(context).single { it.id == id }.updateIntervalMinutes)
        assertFalse(bridge.list().single { it.id == id }.autoUpdate)
    }

    @Test fun localProfileIsNotMistakenForSubscription() {
        val profile = MihomoProfileStore.create(context, "local", "rules: [MATCH,DIRECT]")
        val stored = MihomoProfileStore.profiles(context).single { it.id == profile.id }
        assertNull(stored.subscriptionUrl)
        assertFalse(stored.isSubscription)
    }

    @Test fun uiProfileBridgePersistsEditsSelectionDeletionAndBackup() {
        MikuRayBridgeContext.attach(context)
        val bridge = com.mikubox.mihomo.core.MikuRayProfiles
        val first = bridge.save(null, "first", "rules:\n  - MATCH,DIRECT")
        val second = bridge.save(null, "second", "rules:\n  - MATCH,REJECT")
        bridge.select(second)
        bridge.save(second, "edited", "rules:\n  - DOMAIN,example.test,REJECT\n  - MATCH,DIRECT")
        assertEquals("edited", MihomoProfileStore.selected(context)?.name)
        assertTrue(MihomoProfileStore.selected(context)!!.config.contains("example.test"))
        val backup = bridge.exportBackup()
        bridge.remove(first)
        assertNull(bridge.get(first))
        bridge.restoreBackup(backup)
        assertNotNull(bridge.get(first))
        assertEquals(second, MihomoProfileStore.selected(context)?.id)
    }

    @Test fun malformedConfigurationDoesNotOverwriteWorkingProfile() {
        MikuRayBridgeContext.attach(context)
        val bridge = com.mikubox.mihomo.core.MikuRayProfiles
        val id = bridge.save(null, "working", "rules:\n  - MATCH,DIRECT")
        assertThrows(IllegalStateException::class.java) {
            bridge.save(id, "broken", "rules: [unterminated")
        }
        assertEquals("working", bridge.get(id)?.name)
    }

    @Test fun nativeJsonAndFullYamlKeepRulesAndGroups() {
        val json = """{"mode":"rule","rules":["MATCH,DIRECT"]}"""
        assertEquals(json, MihomoSubscriptionDecoder.toMihomoConfig(context, json))
        val yaml = "proxy-groups: []\nrules:\n  - DOMAIN,example.test,REJECT\n  - MATCH,DIRECT"
        assertEquals(yaml, MihomoSubscriptionDecoder.toMihomoConfig(context, yaml))
    }

    @Test fun backupPreflightDoesNotApplyWrites() {
        val prefs = context.getSharedPreferences("miku_app_settings", 0)
        prefs.edit().putString("value", "old").commit()
        val backup = BackupManager.export(context)
        prefs.edit().putString("value", "new").commit()
        BackupManager.validate(context, backup)
        assertEquals("new", prefs.getString("value", null))
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
