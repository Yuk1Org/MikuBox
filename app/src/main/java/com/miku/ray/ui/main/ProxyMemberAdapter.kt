package com.miku.ray.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.miku.ray.MikuRouting
import com.miku.ray.R
import com.miku.ray.databinding.ItemProxyMemberBinding
import com.miku.ray.remixicon.R as RemixR
import com.miku.ray.util.Utils

/**
 * The member list of one strategy group.
 *
 * A row is the home list's row in miniature: a `colorCard` that takes the
 * primary stroke and a check when it is the member in use, a type chip, the
 * ping-coloured latency, and the same 40dp action button the home rows use —
 * here it probes that member alone.
 */
class ProxyMemberAdapter(
    private val onPick: (name: String) -> Unit,
    private val onTest: (name: String) -> Unit,
) : RecyclerView.Adapter<ProxyMemberAdapter.Holder>() {

    /** One line of the list: a member, or the entry that hands choice back to the core. */
    data class Member(
        val name: String,
        val type: String = "",
        val group: Boolean = false,
        /** The core's last measurement, -1 when it has none. */
        val delay: Int = -1,
        /** This screen's own probe: [TESTING], a value, or -1 for a failure. */
        val probed: Int? = null,
        /** The automatic entry of an automatic group: clearing the pin. */
        val automatic: Boolean = false,
    )

    private var members: List<Member> = emptyList()
    private var selected: String? = null

    /** name → region code, read once per screen: it walks the MMKV store. */
    private var regions: Map<String, String> = emptyMap()

    fun submit(members: List<Member>, selected: String?, regions: Map<String, String>) {
        this.members = members
        this.selected = selected
        this.regions = regions
        notifyDataSetChanged()
    }

    /** Repaints one row after its probe returned. */
    fun updateProbe(name: String, value: Int) {
        val index = members.indexOfFirst { it.name == name }
        if (index >= 0) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemProxyMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = members.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(members[position])

    inner class Holder(private val binding: ItemProxyMemberBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(member: Member) {
            val context = binding.root.context
            val isSelected = member.automatic || member.name == selected

            binding.memberName.text = if (member.automatic) context.getString(R.string.mihomo_groups_auto)
            else member.name.replace(LEADING_FLAG, "")
            binding.memberName.isSelected = isSelected
            // The home rows mark the profile in use with the primary stroke;
            // the same mark says which member this group is on.
            binding.memberCard.strokeWidth = if (isSelected)
                (context.resources.displayMetrics.density * 2).toInt() else 0
            binding.memberCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
            ViewCompat.setStateDescription(
                binding.root,
                if (isSelected) context.getString(R.string.a11y_selected) else null,
            )

            binding.memberType.visibility = if (member.type.isBlank()) View.GONE else View.VISIBLE
            binding.memberType.text = member.type

            bindDelay(member)
            bindLead(member)

            // Wiping a pin is a choice about the group, not a node to probe.
            binding.memberTest.visibility = if (member.automatic) View.INVISIBLE else View.VISIBLE
            binding.memberTest.setOnClickListener { onTest(member.name) }
            binding.root.setOnClickListener { onPick(if (member.automatic) "" else member.name) }
        }

        /**
         * This screen's probe wins — a failure reads as a dash, a running one as
         * an ellipsis. Otherwise it is the core's history, and only a positive
         * reading is shown: the core reports 0 for groups and DIRECT, which is
         * "no number", not "instant".
         */
        private fun bindDelay(member: Member) {
            val value = member.probed ?: member.delay.takeIf { it > 0 }
            val context = binding.root.context
            binding.memberDelay.visibility = if (value != null) View.VISIBLE else View.GONE
            binding.memberDelay.text = when {
                value == null -> ""
                value == TESTING -> "…"
                value < 0 -> "—"
                else -> context.getString(R.string.proxy_delay_ms, value)
            }
        }

        /** Flag of the node, the group icon for a nested group, "auto" for the pin entry. */
        private fun bindLead(member: Member) {
            val context = binding.root.context
            val icon = when {
                member.automatic -> RemixR.drawable.rmx_refresh_line
                member.group -> RemixR.drawable.rmx_group_line
                else -> 0
            }
            binding.memberLeadIcon.visibility = if (icon != 0) View.VISIBLE else View.GONE
            if (icon != 0) binding.memberLeadIcon.setImageResource(icon)

            val code = if (icon == 0) ExitRegion.resolve(member.name, regions) else ""
            binding.memberFlag.visibility = if (code.isEmpty()) View.GONE else View.VISIBLE
            if (code.isNotEmpty()) binding.memberFlag.text = Utils.countryCodeToFlag(code)
        }
    }

    companion object {
        /** Marker for a probe that is still running; no real delay is negative. */
        const val TESTING = -3

        /** A flag emoji pair at the start of a node name (code-point regex). */
        private val LEADING_FLAG = Regex("^[\\x{1F1E6}-\\x{1F1FF}]{2}\\s*")
    }
}
