package top.uwu.mikubox.core

import com.miku.ray.dto.entities.RulesetItem
import com.miku.ray.handler.MmkvManager

/**
 * Turns the routing rules the vendored routing screen holds into mihomo rules.
 *
 * Most of what the screen lists is the *profile's own* rule list, read out of the
 * active configuration by [MikuRayRuleSync] so each rule can be switched on or
 * off. This hands the core what is switched on. Three decisions are worth stating
 * because they are behaviour, not plumbing:
 *
 * - Rules that came from a profile are passed through **verbatim**: the screen's
 *   model cannot reproduce a rule it did not author (the rule's type is not part
 *   of it), and a config's rules are already mihomo syntax. That is also why the
 *   profile's rules are not appended when the store is config-derived — they are
 *   already the list, in their own order, minus what the user switched off.
 * - Rules authored in the screen are **prepended** to the profile's own. mihomo
 *   matches top-down, so rules the user wrote win over the profile's, and a
 *   profile that carries its own routing keeps working.
 * - One authored entry becomes one rule: mihomo takes a single condition per plain
 *   rule, while MikuRay's model packs several into an entry and means them
 *   together, so a combined entry is emitted as an `AND` (with `OR` for its
 *   alternatives) instead of as separate rules that would each match on their own.
 * - The `private` geography code becomes mihomo's `lan`, which tests the address
 *   itself. Looking the code up in the database instead would match the fake-ip
 *   range, and every domain-based connection would then be routed direct.
 *
 * The bridge applies a flat `rules` key as a replacement, which is why the
 * profile's own rules have to be carried across explicitly — either as themselves
 * (config-derived store) or appended after the authored ones.
 */
object MikuRayRoutingBridge {

    fun enabledRulesets(): List<RulesetItem> =
        runCatching { MmkvManager.decodeRoutingRulesets().orEmpty().filter { it.enabled } }
            .getOrDefault(emptyList())

    /** The complete rule list for a profile whose own rules are [profileRules]. */
    fun rulesFor(items: List<RulesetItem>, profileRules: List<String>): List<String> {
        // Switched-off rules are dropped here rather than by the caller, so the
        // list this returns is exactly what the core will be given.
        val live = items.filter { it.enabled }
        val authored = live.filter { it.rawRule.isNullOrBlank() }
        val fromProfile = live.filter { !it.rawRule.isNullOrBlank() }
        // Rules authored in the screen are matched first, so they win; then the
        // profile's own, in their own order — which is also what keeps the
        // profile's `MATCH` at the end, where it belongs.
        val authoredRules = authored.flatMap(::toRules)
        val profileRulesOut = fromProfile.flatMap(::toRules)
        return if (fromProfile.isEmpty()) {
            authoredRules + profileRules
        } else {
            // The profile's rules are the list; its disabled ones simply drop out,
            // so appending it again would double them.
            authoredRules + profileRulesOut
        }
    }

    private fun toRules(item: RulesetItem): List<String> {
        // A rule that came out of a profile goes back to the core exactly as it
        // was written there.
        item.rawRule?.takeIf { it.isNotBlank() }?.let { return listOf(it) }

        val outbound = outboundFor(item.outboundTag)

        // One entry's fields belong together: Xray reads a rule's conditions as a
        // conjunction — "UDP to port 443" — while mihomo takes one condition per
        // rule. Emitting them as separate rules would turn that entry into "all
        // UDP" plus "all port 443", which is how a QUIC block ended up blocking
        // every connection. Each field therefore becomes one *group* of
        // alternatives, and the groups are combined below.
        val groups = mutableListOf<List<String>>()

        item.domain?.mapNotNull { entry ->
            val value = entry.trim()
            when {
                value.isEmpty() -> null
                value.startsWith("geosite:", true) -> "GEOSITE,${value.substringAfter(':')}"
                value.startsWith("keyword:", true) -> "DOMAIN-KEYWORD,${value.substringAfter(':')}"
                value.startsWith("regexp:", true) -> "DOMAIN-REGEX,${value.substringAfter(':')}"
                value.startsWith("full:", true) -> "DOMAIN,${value.substringAfter(':')}"
                else -> "DOMAIN-SUFFIX,$value"
            }
        }?.takeIf { it.isNotEmpty() }?.let { groups += it }

        item.ip?.mapNotNull { entry ->
            val value = entry.trim()
            when {
                value.isEmpty() -> null
                // "private" is the one geography code that must not be looked up
                // in the database: with fake-ip DNS every domain-based connection
                // carries a fake address, that range is recorded as private, and a
                // GEOIP match on it would send *everything* direct. mihomo's own
                // `lan` code tests the address itself (private, loopback, link
                // local, multicast) and so says the same thing without the trap.
                value.equals("geoip:private", true) || value.equals("geoip:lan", true) ->
                    "GEOIP,lan"

                value.startsWith("geoip:", true) -> "GEOIP,${value.substringAfter(':')}"
                // mihomo's IP-CIDR wants a prefix, while a routing rule may name a
                // single host ("223.5.5.5"), so the host form gets /32 or /128.
                else -> "IP-CIDR,${asCidr(value)},no-resolve"
            }
        }?.takeIf { it.isNotEmpty() }?.let { groups += it }

        item.process?.mapNotNull { entry ->
            entry.trim().takeIf { it.isNotEmpty() }?.let { "PROCESS-NAME,$it" }
        }?.takeIf { it.isNotEmpty() }?.let { groups += it }

        item.port?.split(',', ' ')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.map { "DST-PORT,$it" }
            ?.takeIf { it.isNotEmpty() }?.let { groups += it }

        item.network?.trim()?.takeIf { it.isNotEmpty() }?.let { network ->
            groups += listOf("NETWORK,${network.lowercase()}")
        }

        // `protocol` has no counterpart here: it names an Xray transport
        // (http/tls/quic/bittorrent) and mihomo matches on none of those.
        if (groups.isEmpty()) return emptyList()
        return listOf(combine(groups, outbound))
    }

    /**
     * Combines an entry's condition groups into one mihomo rule.
     *
     * A single group needs no logic rule at all; several become an `AND`, and a
     * group with several alternatives an `OR` inside it — which is the shape
     * mihomo's logic rules take: `AND,((NETWORK,udp),(DST-PORT,443)),REJECT`.
     */
    private fun combine(groups: List<List<String>>, outbound: String): String {
        if (groups.size == 1) return group(groups[0], outbound)
        val parts = groups.joinToString(",") { "(${group(it, outbound)})" }
        return "AND,($parts),$outbound"
    }

    private fun group(alternatives: List<String>, outbound: String): String =
        if (alternatives.size == 1) {
            "${alternatives[0]},$outbound"
        } else {
            "OR,(${alternatives.joinToString(",") { "($it)" }}),$outbound"
        }

    private fun outboundFor(tag: String): String = when (tag.trim().lowercase()) {
        "block" -> "REJECT"
        "direct", "" -> "DIRECT"
        else -> tag.trim().uppercase()
    }

    /** A bare address becomes a single-host prefix; a CIDR is left alone. */
    private fun asCidr(value: String): String = when {
        value.contains('/') -> value
        value.contains(':') -> "$value/128"
        else -> "$value/32"
    }

    private val RULE_LINE = Regex("""^\s*-\s*(.+?)\s*$""")

    /**
     * Reads the `rules:` block out of a profile. A line scan is enough because the
     * profiles here are either the ones MikuBox writes itself or ordinary Clash
     * configuration files, where rules are one `- TYPE,value,target` per line.
     */
    fun profileRules(configYaml: String): List<String> {
        val lines = configYaml.lineSequence().toList()
        val start = lines.indexOfFirst { it.trimStart().startsWith("rules:") }
        if (start < 0) return emptyList()

        val rules = mutableListOf<String>()
        for (index in start + 1 until lines.size) {
            val line = lines[index]
            // A key at column zero ends the section.
            if (line.isNotBlank() && !line.first().isWhitespace()) break
            RULE_LINE.find(line)?.let { rules += it.groupValues[1] }
        }
        return rules
    }
}
