package com.mikubox.mihomo.core

import android.content.Context
import com.miku.ray.MikuRouting
import com.miku.ray.util.LogUtil
import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor
import com.mikubox.mihomo.profile.MihomoProfileStore

/** Offline state is scoped to the complete profile, never to a node's display name. */
object MikuRayRoutingMode : MikuRouting.Impl {
    private val context get() = checkNotNull(MikuRayBridgeContext.application)
    @Volatile private var activeProfileId: String? = null
    private fun prefs(context: Context) = context.getSharedPreferences("mihomo_routing_choices", 0)

    fun configuredOptions(config: String): List<MikuRouting.Exit> {
        val root = parse(config)
        fun entries(key: String, group: Boolean) = (root[key] as? List<*>).orEmpty().mapNotNull {
            val name = (it as? Map<*, *>)?.get("name") as? String
            name?.takeIf { it.isNotBlank() && it != "GLOBAL" }?.let { MikuRouting.Exit(it, group) }
        }
        val options = (entries("proxy-groups", true) + entries("proxies", false) + MikuRouting.Exit("DIRECT", false)).distinctBy { it.name }
        val global = (root["proxy-groups"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.firstOrNull { it["name"] == "GLOBAL" }
            ?: return options
        val explicit = (global["proxies"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        val includeNodes = global["include-all"] == true || global["include-all-proxies"] == true
        return options.filter { it.name in explicit || (includeNodes && !it.group && it.name != "DIRECT") }
    }

    private fun parse(config: String): Map<*, *> = runCatching {
        Yaml(SafeConstructor(LoaderOptions())).load<Any>(config) as? Map<*, *> ?: emptyMap<Any, Any>()
    }.getOrDefault(emptyMap<Any, Any>())

    /**
     * The strategy groups of a profile, live from the core or from the stored
     * YAML when it is not the one running. `GLOBAL` is not one of them: it is
     * the exit of global mode, and the mode bar's own row already selects it.
     */
    override fun groups(profileId: String?): List<MikuRouting.Group> {
        val target = profileOf(profileId) ?: return emptyList()
        val live = target.id == activeProfileId && com.miku.ray.MikuCoreBridge.isRunning()
        return if (live) liveGroups() else offlineGroups(target)
    }

    /**
     * The profile a screen asked for, or the selected one when none was named.
     * An id that no longer exists is *not* an alias for the selection: showing
     * another profile's groups would be worse than showing none.
     */
    private fun profileOf(profileId: String?): MihomoProfileStore.Profile? =
        if (profileId == null) MihomoProfileStore.selected(context)
        else MihomoProfileStore.profiles(context).firstOrNull { it.id == profileId }

    override fun activeProfileId(): String? = activeProfileId

    private fun liveGroups(): List<MikuRouting.Group> {
        val proxies = MihomoCore.proxies()
        // The core reports its own document order; anything it did not list
        // (a group added by a provider) keeps the map's order behind it.
        val ordered = (MihomoCore.groupOrder() + proxies.keys).distinct()
        return ordered.mapNotNull { name ->
            val proxy = proxies[name] ?: return@mapNotNull null
            if (name == MihomoCore.GLOBAL_GROUP || !proxy.isGroup) return@mapNotNull null
            val pinned = proxy.fixed?.takeIf { it.isNotBlank() }
            MikuRouting.Group(
                name = name,
                type = proxy.type,
                members = proxy.all.mapNotNull { member ->
                    proxies[member]?.takeIf { member != name }
                        ?.let { MikuRouting.Exit(it.name, it.isGroup, it.delay, it.type) }
                },
                selected = (pinned ?: proxy.now)?.takeIf { it.isNotBlank() },
                automatic = !proxy.isSelector,
                pinned = pinned != null,
            )
        }
    }

    private fun offlineGroups(profile: MihomoProfileStore.Profile): List<MikuRouting.Group> {
        val declared = declaredGroups(profile.config)
        val groupNames = declared.map { it.name }.toSet()
        return declared.map { group ->
            // A stored choice is per profile and per group; it is what the
            // service re-applies on the next start, so it is the selection the
            // panel has to show while the core is stopped.
            val stored = prefs(context)
                .getString(groupKey(profile.id, group.name), null)
                ?.takeIf { member -> member.isNotEmpty() && member in group.members }
            val automatic = group.type.lowercase() in AUTOMATIC_TYPES
            MikuRouting.Group(
                name = group.name,
                type = group.type,
                members = group.members.map { member ->
                    MikuRouting.Exit(member, group = member in groupNames)
                },
                // Without a stored choice a selector uses its first member, which
                // is what the core itself would do with the config.
                selected = stored ?: group.members.firstOrNull()?.takeIf { !automatic },
                automatic = automatic,
                pinned = stored != null,
            )
        }
    }

    private data class DeclaredGroup(val name: String, val type: String, val members: List<String>)

    /**
     * `proxy-groups` as the profile declares them. `use:` pulls members from
     * proxy providers, which only a running core can resolve, so those groups
     * list no members offline — the panel says so rather than inventing names.
     */
    private fun declaredGroups(config: String): List<DeclaredGroup> =
        (parse(config)["proxy-groups"] as? List<*>).orEmpty().mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val name = (map["name"] as? String)?.takeIf { it.isNotBlank() && it != MihomoCore.GLOBAL_GROUP }
                ?: return@mapNotNull null
            DeclaredGroup(
                name = name,
                type = (map["type"] as? String).orEmpty(),
                members = (map["proxies"] as? List<*>).orEmpty().mapNotNull { it as? String }
                    .filter { it.isNotBlank() },
            )
        }

    override fun selectGroupMember(group: String, member: String, profileId: String?): Boolean {
        val target = profileOf(profileId) ?: return false
        val known = groups(target.id).firstOrNull { it.name == group } ?: return false
        // An empty member means "back to automatic", which only an automatic
        // group can honour: a selector always has a chosen member.
        if (member.isEmpty() && !known.automatic) return false
        if (member.isNotEmpty() && known.members.isNotEmpty() && known.members.none { it.name == member }) return false
        val live = target.id == activeProfileId && com.miku.ray.MikuCoreBridge.isRunning()
        if (live && !MihomoCore.selectProxy(group, member)) return false
        val key = groupKey(target.id, group)
        val stored = if (member.isEmpty()) prefs(context).edit().remove(key).commit()
        else prefs(context).edit().putString(key, member).commit()
        if (live) notifyTunnelRechosen(context)
        // Live the core already carries the change; otherwise the stored choice
        // is the whole of it and the service applies it when this profile starts.
        return if (live) true else stored
    }

    override fun delay(name: String): Int {
        if (!com.miku.ray.MikuCoreBridge.isRunning()) return -1
        return MihomoCore.delay(name, MihomoCoreSettings.testUrl(context), DELAY_TIMEOUT_MS)
    }

    private fun groupKey(profileId: String, group: String) = "group:$profileId:$group"

    /**
     * Re-applies the member each group was left on. The core starts from the
     * profile's own defaults, so without this a choice made while stopped would
     * look saved but never reach the tunnel. Groups or members that are gone
     * with the new config drop their stale choice instead of failing silently.
     */
    private fun applyStoredGroupChoices(profileId: String) {
        prefs(context).all.forEach { (key, value) ->
            val prefix = "group:$profileId:"
            if (!key.startsWith(prefix)) return@forEach
            val group = key.removePrefix(prefix)
            val member = value as? String ?: return@forEach
            if (member.isEmpty()) return@forEach
            if (!MihomoCore.selectProxy(group, member)) {
                prefs(context).edit().remove(key).apply()
                LogUtil.w(message = "Saved group member is no longer available: $group → $member")
            }
        }
    }

    override fun state(): MikuRouting.State {
        val profile = MihomoProfileStore.selected(context)
        val config = profile?.config.orEmpty()
        val live = profile != null && profile.id == activeProfileId && com.miku.ray.MikuCoreBridge.isRunning()
        val options = if (live) RoutingMode.globalExitOptions().map { MikuRouting.Exit(it.name, it.isGroup, it.delay, it.type) }
            .also { saveCache(profile.id, config, it) } else {
            val cached = runCatching { JSONObject(prefs(context).getString("cache:${profile?.id}", "{}")!!) }.getOrDefault(JSONObject())
            // Provider nodes are usable offline only when discovered for this exact config.
            if (cached.optString("config") == config) {
                val array = cached.optJSONArray("options") ?: JSONArray()
                (0 until array.length()).map { i -> array.getJSONObject(i).let { MikuRouting.Exit(it.getString("name"), it.getBoolean("group")) } }
            } else configuredOptions(config)
        }
        val selected = if (live) RoutingMode.globalExit() else prefs(context).getString("exit:${profile?.id}", null)
        val stored = MihomoCoreSettings.mode(context).value
        val mode = stored ?: (parse(config)["mode"] as? String)?.lowercase()?.takeIf { it in listOf("rule", "global", "direct") } ?: "rule"
        return MikuRouting.State(mode, selected?.takeIf { name -> options.any { it.name == name } }, options)
    }

    private fun saveCache(id: String, config: String, options: List<MikuRouting.Exit>) {
        val value = JSONObject().put("config", config).put("options", JSONArray().apply {
            options.forEach { put(JSONObject().put("name", it.name).put("group", it.group)) }
        }).toString()
        val prefs = prefs(context)
        if (prefs.getString("cache:$id", null) != value) prefs.edit().putString("cache:$id", value).apply()
    }

    override fun mode(value: String): Boolean {
        val mode = MihomoCoreSettings.ProxyMode.entries.firstOrNull { it.value == value } ?: return false
        val runningAtCheck = com.miku.ray.MikuCoreBridge.isRunning()
        val appliedLive = runningAtCheck && MihomoCore.setMode(value)
        if (runningAtCheck && !appliedLive) return false
        MihomoCoreSettings.setMode(context, mode)
        // Connect race: onStarted (executor thread) can read the stored value
        // before the persist above lands, and the live-apply check ran while
        // the core was still down. If the core is up now, apply the
        // just-persisted value once or the core keeps the previous mode until
        // the next reconnect.
        val applied = appliedLive ||
            (com.miku.ray.MikuCoreBridge.isRunning() && MihomoCore.setMode(value))
        if (applied) notifyTunnelRechosen(context)
        return true
    }

    override fun exit(name: String): Boolean {
        val profile = MihomoProfileStore.selected(context) ?: return false
        if (state().options.none { it.name == name }) return false
        val runningAtCheck = profile.id == activeProfileId && com.miku.ray.MikuCoreBridge.isRunning()
        if (runningAtCheck && !RoutingMode.selectGlobalExit(name)) return false
        val stored = prefs(context).edit().putString("exit:${profile.id}", name).commit()
        // Same connect race as mode(): re-select once when the core is up now
        // but the live selection above did not happen.
        val applied = runningAtCheck ||
            (profile.id == activeProfileId && com.miku.ray.MikuCoreBridge.isRunning() && RoutingMode.selectGlobalExit(name))
        if (applied) notifyTunnelRechosen(context)
        return stored
    }

    /**
     * A live switch moves traffic to a different exit without touching the
     * core's counters, so nothing else tells the notification: it kept quoting
     * the previous node's exit until the five-minute TTL ran out. The refresh
     * drops the cached reading and re-probes; the home screen's own measure
     * path is already re-run by its selection-changed hook.
     */
    private fun notifyTunnelRechosen(context: Context) {
        com.mikubox.mihomo.service.VpnController.refresh(context)
    }

    /** Called on the service's serialized core executor, before publishing connected state. */
    fun onStarted(context: Context, profileId: String?) {
        MihomoCoreSettings.mode(context).value?.let { MihomoCore.setMode(it) }
        activeProfileId = profileId
        if (profileId == null) return
        applyStoredGroupChoices(profileId)
        val name = prefs(context).getString("exit:$profileId", null) ?: return
        if (!RoutingMode.selectGlobalExit(name)) {
            // Removed/changed provider nodes must not leave a stale apparent selection.
            prefs(context).edit().remove("exit:$profileId").apply()
            LogUtil.w(message = "Saved global exit is no longer available: $name")
        }
    }

    /** Groups whose member is chosen by the core unless it is pinned. */
    private val AUTOMATIC_TYPES = setOf("url-test", "fallback", "load-balance", "relay")

    /** One probe's budget: the app's own default for a node measurement. */
    private const val DELAY_TIMEOUT_MS = 5000
}
