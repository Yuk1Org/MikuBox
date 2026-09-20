package top.uwu.mikubox

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.miku.ray.AppConfig
import com.miku.ray.handler.MmkvManager
import com.miku.ray.ui.main.MainActivity
import com.miku.ray.ui.main.MainViewModel
import top.uwu.mikubox.core.*
import top.uwu.mikubox.profile.MihomoProfileStore
import top.uwu.mikubox.service.VpnController

/** Opt-in test on a disposable emulator; requires the local CONNECT fixture on port 18091. */
object QuickActionsSmoke {
    fun run(test: Instrumentation): Bundle {
        val ctx = test.targetContext
        val report = Bundle()
        fun waitFor(label: String, seconds: Int = 35, predicate: () -> Boolean) {
            val deadline = System.currentTimeMillis() + seconds * 1000
            while (!predicate()) {
                check(System.currentTimeMillis() < deadline) { "Timed out: $label" }
                Thread.sleep(100)
            }
        }
        fun ipUrl(country: String, ip: String): String = "https://httpbingo.org/base64/" +
            android.util.Base64.encodeToString("{\"country_code\":\"$country\",\"ip\":\"$ip\"}".toByteArray(), android.util.Base64.NO_WRAP)
        MmkvManager.encodeSettings("pref_mikubox_welcome_completed", true)
        MmkvManager.encodeSettings(AppConfig.PREF_SHOW_SPLASH, false)
        MmkvManager.encodeSettings(AppConfig.PREF_SHOW_QUICK_ACTIONS, true)
        MmkvManager.encodeSettings(AppConfig.PREF_SHOW_REALTIME_TRAFFIC_IP, false)
        MmkvManager.encodeSettings(AppConfig.PREF_VPN_BYPASS_LAN, "2")
        MmkvManager.encodeSettings(AppConfig.PREF_IP_API_URL, ipUrl("HK", "103.30.78.42"))
        MmkvManager.encodeSettings(AppConfig.PREF_DELAY_TEST_URL, "https://httpbingo.org/status/204")
        CoreOverrides.setExtra(ctx, "script.enabled", "false")
        val config = """
            mode: rule
            proxies:
              - {name: QA-HK, type: http, server: 10.0.2.2, port: 18091}
              - {name: QA-JP, type: http, server: 10.0.2.2, port: 18091}
            proxy-groups:
              - {name: Test, type: select, proxies: [QA-HK, QA-JP]}
            rules:
              - MATCH,Test
        """.trimIndent()
        val url = "https://httpbingo.org/base64/" + android.util.Base64.encodeToString(config.toByteArray(), android.util.Base64.NO_WRAP)
        val profile = MihomoProfileStore.createSubscription(ctx, "Quick action QA", url, 0)
        // Let the quick-update button do the download; an empty config must become usable.
        MikuRayProfiles.sync()
        val activity = test.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        test.waitForIdleSync()
        fun click(id: Int) { test.runOnMainSync { check(activity.findViewById<View>(id).performClick()) } }
        test.runOnMainSync { androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java].ensureServerCacheReady() }
        click(com.miku.ray.R.id.btn_quick_sub_update)
        waitFor("subscription download") { MihomoProfileStore.profiles(ctx).first { it.id == profile.id }.updatedAtMillis > 0 }
        report.putString("subscription", "quick button downloads config with schedule disabled")
        test.runOnMainSync { androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java].reloadServerList() }
        Thread.sleep(800)
        click(com.miku.ray.R.id.btn_quick_tcping)
        waitFor("TCP result") { (MmkvManager.decodeServerAffiliationInfo(profile.id)?.testDelayMillis ?: 0) > 0 }
        report.putString("tcp", MmkvManager.decodeServerAffiliationInfo(profile.id)?.testDelayMillis.toString())
        Thread.sleep(1200)
        MmkvManager.encodeServerTestDelayMillis(profile.id, 0)
        click(com.miku.ray.R.id.btn_quick_real_ping)
        waitFor("URL-test result") { (MmkvManager.decodeServerAffiliationInfo(profile.id)?.testDelayMillis ?: 0) > 0 }
        report.putString("url_test", MmkvManager.decodeServerAffiliationInfo(profile.id)?.testDelayMillis.toString())
        Thread.sleep(1200)
        click(com.miku.ray.R.id.btn_quick_country_code)
        waitFor("country result") { MmkvManager.decodeServerAffiliationInfo(profile.id)?.countryCode == "HK" }
        report.putString("country", "HK from HTTPS through profile proxy")
        Thread.sleep(1000)
        MihomoProfileStore.select(ctx, profile.id)
        VpnController.connect(ctx)
        waitFor("VPN connected") { VpnController.isRunning }
        fun ipText(): String { var result = ""; test.runOnMainSync { result = activity.findViewById<TextView>(com.miku.ray.R.id.tv_ip_state).text.toString() }; return result }
        waitFor("initial IP") { ipText().contains("103.30.78.42") }
        MmkvManager.encodeSettings(AppConfig.PREF_IP_API_URL, ipUrl("JP", "45.143.235.225"))
        test.runOnMainSync {
            val root = activity.findViewById<android.view.ViewGroup>(com.miku.ray.R.id.routing_mode)
            fun find(v: View): TextView? {
                if (v is TextView && v.text == ctx.getString(com.miku.ray.R.string.mihomo_mode_global)) return v
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                return null
            }
            check(find(root)?.performClick() == true)
        }
        waitFor("mode switch IP") { ipText().contains("45.143.235.225") }
        report.putString("mode_ip", "HK to JP refreshed through mode button")
        Thread.sleep(1000)
        MmkvManager.encodeSettings(AppConfig.PREF_IP_API_URL, ipUrl("HK", "103.30.78.42"))
        test.runOnMainSync {
            val root = activity.findViewById<android.view.ViewGroup>(com.miku.ray.R.id.routing_mode)
            val column = root.getChildAt(0) as android.view.ViewGroup
            check(column.getChildAt(1).performClick())
        }
        test.waitForIdleSync()
        waitFor("exit picker") { test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("QA-JP")?.isNotEmpty() == true }
        var node = test.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("QA-JP").first()
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        waitFor("node switch IP") { ipText().contains("103.30.78.42") }
        report.putString("node_ip", "JP to HK refreshed through global exit picker")
        check(VpnController.isRunning)
        MmkvManager.encodeServerTestDelayMillis(profile.id, 0)
        click(com.miku.ray.R.id.btn_quick_real_ping)
        waitFor("connected batch probe") { (MmkvManager.decodeServerAffiliationInfo(profile.id)?.testDelayMillis ?: 0) > 0 }
        check(VpnController.isRunning) { "Probe stopped the active VPN" }
        report.putString("isolation", "batch probe succeeded while VPN remained connected")
        return report
    }
}
