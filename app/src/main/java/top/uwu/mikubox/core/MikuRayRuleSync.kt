package top.uwu.mikubox.core

import android.content.Context
import com.miku.ray.dto.entities.RulesetItem
import com.miku.ray.handler.MmkvManager

/**
 * Keeps the routing screen's list in step with the selected profile.
 *
 * MikuRay's routing screen manages rules the screen itself authored, in its own
 * store. Here the rules that matter are the ones a profile already carries, so
 * this reads them out of the active configuration and writes them into that store
 * — the same shape the screen renders, with the same per-rule switch. What the
 * user toggles is remembered per rule, and what the core gets is the enabled
 * subset of the profile's own rules, in the profile's own order.
 *
 * Rules authored in the screen (they carry no [RulesetItem.rawRule]) are kept
 * after the profile's own and still take effect; they were the only kind before
 * this sync existed.
 */
object MikuRayRuleSync {

    /** Marks that the store was filled from a profile, so the bridge can tell. */
    private const val SOURCE_KEY = "rule_store_source"
    private const val SOURCE_CONFIG = "config"

    fun sync(context: Context) {
        val config = MihomoConfigStore.activeConfig(context)
        val fromConfig = MikuRayRoutingBridge.profileRules(config)

        val existing = MmkvManager.decodeRoutingRulesets().orEmpty()
        // A rule's state belongs to the rule, not to the list: matching on the
        // line plus where it sits keeps a switch where the user left it, and
        // survives a subscription that only reorders the list.
        val previous = existing.associateBy { it.id }
        val authored = existing.filter { it.rawRule.isNullOrBlank() }

        val synced = fromConfig.mapIndexed { index, line ->
            val id = idFor(index, line)
            val carried = previous[id]
            describe(id, line).apply {
                // The profile owns these: editing or deleting them here would be
                // undone by the next sync, so the screen locks them and shows the
                // switch it can honour.
                locked = true
                enabled = carried?.enabled ?: true
            }
        }.toMutableList()

        synced += authored
        MmkvManager.encodeRoutingRulesets(synced)
        MmkvManager.encodeSettings(SOURCE_KEY, SOURCE_CONFIG)
    }

    /** True when the store holds rules read out of the selected profile. */
    fun isConfigDerived(): Boolean =
        MmkvManager.decodeSettingsString(SOURCE_KEY) == SOURCE_CONFIG

    /**
     * A stable id for a rule line: its position in the profile's list plus the
     * line itself, so two identical rules stay distinguishable and a line that
     * moves keeps its own switch only when it really is the same rule.
     */
    private fun idFor(index: Int, line: String): String =
        "profile-${index}-${line.hashCode()}"

    /**
     * The screen shows a rule by its type, payload and target; all three come out
     * of the line, and the line itself is kept for the core.
     */
    private fun describe(id: String, line: String): RulesetItem {
        val parts = line.split(',').map { it.trim() }
        val type = parts.firstOrNull().orEmpty()
        // MATCH carries no payload, and a logic rule keeps its parentheses.
        val payload = parts.drop(1).dropLast(1).joinToString(",").ifBlank { null }
        val outbound = parts.lastOrNull().takeIf { parts.size > 1 }.orEmpty()

        return RulesetItem(
            remarks = type,
            domain = payload?.let { listOf(it) },
            outboundTag = outbound,
            id = id,
            rawRule = line,
        )
    }
}
