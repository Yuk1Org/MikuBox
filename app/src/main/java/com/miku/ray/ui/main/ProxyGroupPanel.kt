package com.miku.ray.ui.main

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.miku.ray.MikuCoreBridge
import com.miku.ray.MikuRouting
import com.miku.ray.R
import com.miku.ray.remixicon.R as RemixR
import com.miku.ray.util.Utils
import com.miku.ray.util.getColorAttr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * The body of the strategy-group picker: the active profile's proxy groups as
 * pills, their members as rows, and the selection inside each group.
 *
 * This is where a subscription's `proxy-groups` become visible and switchable.
 * Global mode has its own exit row; every other mode routes by the config's own
 * rules, so the groups they name are what a user has to reach — the reason this
 * panel exists rather than only the GLOBAL picker.
 *
 * Offline the rows are the members the YAML declares (provider-backed groups
 * cannot be listed without the core) and a choice is remembered for the next
 * start; live they come from the core, carry its measured latencies and are
 * applied immediately.
 */
class ProxyGroupPanel(context: Context) : LinearLayout(context) {

    private enum class Sort { DECLARED, DELAY }

    private sealed class Row {
        /** The entry of an automatic group that hands member choice back to the core. */
        object Auto : Row()
        class Item(val member: MikuRouting.Exit, val region: String) : Row()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // Host palette, resolved once — the dialog overlay can tint differently.
    private val accent = context.getColorAttr(androidx.appcompat.R.attr.colorPrimary)
    private val onAccent = context.getColorAttr(com.google.android.material.R.attr.colorOnPrimary)
    private val onSurface = context.getColorAttr(com.google.android.material.R.attr.colorOnSurface)
    private val variant = context.getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val fieldColor = context.getColorAttr(com.google.android.material.R.attr.colorSurfaceContainerHighest)
    private val delayGreen = ContextCompat.getColor(context, R.color.colorPing)

    private var groups: List<MikuRouting.Group> = emptyList()
    private var current = 0
    private var sort = Sort.DECLARED
    private var testing = false
    private var testJob: Job? = null

    /** Probe results of this panel's own test runs, keyed by member name. */
    private val measured = HashMap<String, Int>()

    /** name → measured country code, read once per open: it walks the MMKV store. */
    private var regions: Map<String, String> = emptyMap()

    private val rows = mutableListOf<Row>()
    private val groupsRow = LinearLayout(context)
    private val groupsScroll = HorizontalScrollView(context)
    private val list = RecyclerView(context)
    private val empty = TextView(context)
    private val hint = TextView(context)
    private val testButton = actionButton(RemixR.drawable.rmx_media_speed_up_line, R.string.mihomo_groups_test)
    private val sortButton = actionButton(RemixR.drawable.rmx_sort_desc, R.string.mihomo_groups_sort)
    private val adapter = Adapter()

    /** Whether the core is up: the only state in which members carry latencies. */
    private val live get() = MikuCoreBridge.isRunning()

    private val search = EditText(context)
    private val leadId = View.generateViewId()
    private val nameId = View.generateViewId()
    private val delayId = View.generateViewId()
    private val checkId = View.generateViewId()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(4), dp(12), 0)

        search.apply {
            hint = context.getString(R.string.mihomo_exit_search)
            setSingleLine()
            textSize = 15f
            setTextColor(onSurface)
            setHintTextColor(variant)
            background = fill(fieldColor, 22)
            val icon = ContextCompat.getDrawable(context, RemixR.drawable.rmx_search_line)?.mutate()
            TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(variant))
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
            compoundDrawablePadding = dp(10)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = render()
            })
        }
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(8), 0, dp(8))
        })

        groupsScroll.apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        groupsRow.orientation = LinearLayout.HORIZONTAL
        groupsScroll.addView(groupsRow)
        addView(groupsScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(8))
        })

        addView(actionRow(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(4))
        })

        // The offline note sits above the list: below it, the dialog's height
        // ceiling cuts the last row and the note reads as if it overlapped it.
        hint.apply {
            setText(R.string.mihomo_groups_offline)
            textSize = 12f
            setTextColor(variant)
            setPadding(dp(4), 0, dp(4), dp(6))
            visibility = GONE
        }
        addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        list.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@ProxyGroupPanel.adapter
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        empty.apply {
            textSize = 14f
            setTextColor(variant)
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, dp(32))
            visibility = GONE
        }
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        load()
    }

    /**
     * Groups come from the core (JNI + a JSON walk) or from the stored YAML, so
     * both readings stay off the frame that opens the dialog.
     */
    private fun load() {
        val impl = MikuRouting.impl
        if (impl == null) {
            apply(emptyList())
            return
        }
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { impl.groups() }.getOrDefault(emptyList()) }
            apply(loaded)
        }
    }

    private fun apply(loaded: List<MikuRouting.Group>) {
        groups = loaded
        current = current.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
        render()
    }

    private fun render() {
        rebuildGroupPills()
        regions = ExitRegion.measuredRegions()
        val group = groups.getOrNull(current)
        rows.clear()
        if (group != null) {
            val text = search.text?.toString()?.trim()?.lowercase().orEmpty()
            val members = group.members.filter { text.isEmpty() || it.name.lowercase().contains(text) }
            // "Automatic" is only offered while the whole group is listed: it is
            // about the group, not about one of the filtered rows.
            if (group.automatic && text.isEmpty()) rows += Row.Auto
            rows += members.map { Row.Item(it, ExitRegion.resolve(it.name, regions)) }
            if (sort == Sort.DELAY) {
                rows.sortBy { row ->
                    when (row) {
                        is Row.Auto -> if (group.pinned) Int.MAX_VALUE else Int.MIN_VALUE
                        is Row.Item -> delayOf(row.member).takeIf { it >= 0 } ?: Int.MAX_VALUE
                    }
                }
            }
        }
        adapter.notifyDataSetChanged()
        list.layoutManager?.scrollToPosition(0)

        val hasGroups = groups.isNotEmpty()
        groupsScroll.visibility = if (hasGroups) VISIBLE else GONE
        // Latency is whatever the core last measured, so both actions only mean
        // something while it runs; offline the note above the list says why.
        val live = live
        testButton.visibility = if (hasGroups && live) VISIBLE else GONE
        sortButton.visibility = if (hasGroups && live) VISIBLE else GONE
        list.visibility = if (rows.isEmpty()) GONE else VISIBLE
        empty.visibility = if (rows.isEmpty()) VISIBLE else GONE
        empty.setText(
            when {
                !hasGroups -> R.string.mihomo_groups_empty
                search.text?.isNotBlank() == true -> R.string.mihomo_exit_empty
                else -> R.string.mihomo_groups_unlisted
            },
        )
        testButton.isEnabled = !testing
        hint.visibility = if (hasGroups && !live) VISIBLE else GONE
        sortButton.isSelected = sort == Sort.DELAY
        tintAction(sortButton, sort == Sort.DELAY)
    }

    private fun rebuildGroupPills() {
        groupsRow.removeAllViews()
        groups.forEachIndexed { index, group ->
            groupsRow.addView(pill(group.name, index == current) {
                if (index == current) return@pill
                current = index
                search.setText("")
                render()
            })
        }
    }

    private fun actionRow(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sortButton.setOnClickListener {
            sort = if (sort == Sort.DECLARED) Sort.DELAY else Sort.DECLARED
            render()
        }
        testButton.setOnClickListener { testGroup() }
        row.addView(testButton)
        row.addView(sortButton)
        return row
    }

    /**
     * Probes every member of the visible group and streams the numbers in. The
     * cores' own history only has what was tested before, so an untested group
     * shows nothing until this runs — the same contract upstream's node screen
     * had.
     */
    private fun testGroup() {
        val group = groups.getOrNull(current) ?: return
        val impl = MikuRouting.impl ?: return
        if (testing) return
        testing = true
        // TESTING marks "probe running" so a row shows it is being measured
        // rather than sitting at an unknown value.
        group.members.forEach { measured[it.name] = TESTING }
        render()
        testJob = scope.launch {
            val gate = Semaphore(16)
            try {
                group.members.map { member ->
                    async(Dispatchers.IO) {
                        gate.withPermit { member.name to impl.delay(member.name) }
                    }
                }.awaitAll().forEach { (name, delay) ->
                    measured[name] = delay
                    // One row at a time: a 100-member group would otherwise
                    // rebuild the whole list on every returning probe.
                    updateDelay(name)
                }
            } finally {
                testing = false
                if (sort == Sort.DELAY) render() else testButton.isEnabled = true
            }
        }
    }

    /** Repaints the single row of [name] after its probe returned. */
    private fun updateDelay(name: String) {
        val index = rows.indexOfFirst { it is Row.Item && it.member.name == name }
        if (index >= 0) adapter.notifyItemChanged(index)
    }

    private fun delayOf(member: MikuRouting.Exit): Int = measured[member.name] ?: member.delay

    private fun select(member: String) {
        val group = groups.getOrNull(current) ?: return
        val impl = MikuRouting.impl
        if (impl?.selectGroupMember(group.name, member) != true) {
            Toast.makeText(context, R.string.mihomo_mode_failed, Toast.LENGTH_SHORT).show()
            return
        }
        // Re-read instead of patching: live, the core is the truth about the
        // selection (an automatic group may have re-chosen while we asked).
        val updated = group.copy(selected = member.takeIf { it.isNotEmpty() }, pinned = member.isNotEmpty())
        groups = groups.toMutableList().also { it[current] = impl.groups().firstOrNull { g -> g.name == group.name } ?: updated }
        render()
    }

    private fun pill(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            isClickable = true
            isFocusable = true
            isSelected = selected
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.create(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (selected) onAccent else variant)
            background = RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 31)),
                StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_selected), fill(accent, 17))
                    addState(intArrayOf(), fill(fieldColor, 17))
                },
                null,
            )
            setOnClickListener { onClick() }
            // The group the list belongs to is only implied by colour otherwise;
            // carry it as a real state, the way the mode tabs do.
            ViewCompat.setStateDescription(this, if (selected) context.getString(R.string.a11y_selected) else null)
            // Long group names must not push the following pill off-screen.
            maxWidth = dp(220)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(34)).apply { marginEnd = dp(8) }
        }

    private fun actionButton(iconRes: Int, labelRes: Int): TextView =
        TextView(context).apply {
            text = context.getString(labelRes)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(variant)
            val icon = ContextCompat.getDrawable(context, iconRes)?.mutate()
            TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(variant))
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
            compoundDrawablePadding = dp(6)
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 31)),
                ColorDrawable(android.graphics.Color.TRANSPARENT),
                null,
            )
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(34)).apply { marginEnd = dp(8) }
        }

    /** An engaged toggle reads as the accent colour, the way the pills do. */
    private fun tintAction(button: TextView, active: Boolean) {
        val color = if (active) accent else variant
        button.setTextColor(color)
        TextViewCompat.setCompoundDrawableTintList(button, ColorStateList.valueOf(color))
    }

    private fun fill(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = ItemHolder()

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder as ItemHolder).bind(rows[position])
        }
    }

    private fun makeItemRow(): LinearLayout {
        val lead = FrameLayout(context).apply { id = leadId }
        val name = TextView(context).apply {
            id = nameId
            textSize = 15f
            setTextColor(onSurface)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val delay = TextView(context).apply {
            id = delayId
            textSize = 12f
        }
        val check = ImageView(context).apply {
            id = checkId
            setImageResource(RemixR.drawable.rmx_checkbox_circle_line)
            setColorFilter(accent)
        }
        val r = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            minimumHeight = dp(52)
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 31)),
                ColorDrawable(android.graphics.Color.TRANSPARENT),
                null,
            )
        }
        r.addView(lead, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) })
        r.addView(name, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        delay.layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
        r.addView(delay)
        r.addView(check, LinearLayout.LayoutParams(dp(20), dp(20)))
        return r
    }

    /** One member entry: lead slot (flag / group icon), name, latency, selection. */
    private inner class ItemHolder : RecyclerView.ViewHolder(makeItemRow()) {
        private val row = itemView as LinearLayout
        private val lead = itemView.findViewById<FrameLayout>(leadId)
        private val name = itemView.findViewById<TextView>(nameId)
        private val delay = itemView.findViewById<TextView>(delayId)
        private val check = itemView.findViewById<ImageView>(checkId)

        fun bind(entry: Row) {
            val group = groups.getOrNull(current)
            val member = (entry as? Row.Item)?.member
            val isAuto = entry is Row.Auto
            val selected = if (isAuto) group?.pinned != true && group?.selected != null
            else member?.name != null && member.name == group?.selected
            name.text = when {
                isAuto -> context.getString(R.string.mihomo_groups_auto)
                else -> member?.name.orEmpty().replace(LEADING_FLAG, "")
            }
            name.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            name.setTextColor(if (selected) accent else onSurface)
            check.visibility = if (selected) VISIBLE else GONE
            bindDelay(member)
            row.isSelected = selected
            ViewCompat.setStateDescription(row, if (selected) context.getString(R.string.a11y_selected) else null)
            check.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            lead.removeAllViews()
            when {
                isAuto -> lead.addView(icon(RemixR.drawable.rmx_refresh_line))
                member?.group == true -> lead.addView(icon(RemixR.drawable.rmx_group_line))
                else -> {
                    val region = (entry as? Row.Item)?.region.orEmpty()
                    if (region.isNotEmpty()) lead.addView(TextView(context).apply {
                        text = Utils.countryCodeToFlag(region)
                        textSize = 17f
                        gravity = Gravity.CENTER
                    }, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                    else lead.addView(icon(RemixR.drawable.rmx_global_line))
                }
            }
            row.setOnClickListener { select(if (isAuto) "" else member?.name.orEmpty()) }
        }

        /**
         * The number shown for a member: this panel's own probe first, else the
         * core's history. Outside a live core there is nothing measured, so the
         * row stays clean instead of claiming an unreachable node; -3 is a probe
         * this panel is running right now.
         */
        private fun bindDelay(member: MikuRouting.Exit?) {
            val value = member?.let { measured[it.name] ?: it.delay.takeIf { _ -> live } }
            delay.visibility = if (value != null) VISIBLE else GONE
            delay.text = when {
                value == null -> ""
                value == TESTING -> "…"
                value < 0 -> "—"
                else -> "${value}ms"
            }
            delay.setTextColor(if (value != null && value >= 0) delayGreen else variant)
        }

        private fun icon(res: Int) = ImageView(context).apply {
            setImageResource(res)
            setColorFilter(variant)
        }
    }

    override fun onDetachedFromWindow() {
        testJob?.cancel()
        scope.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        /** Marker for a probe that is still running; no real delay is negative. */
        const val TESTING = -3

        /** A flag emoji pair at the start of a node name (code-point regex). */
        val LEADING_FLAG = Regex("^[\\x{1F1E6}-\\x{1F1FF}]{2}\\s*")
    }
}
