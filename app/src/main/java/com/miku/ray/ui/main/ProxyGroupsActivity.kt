package com.miku.ray.ui.main

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.miku.ray.AppConfig
import com.miku.ray.MikuCoreBridge
import com.miku.ray.MikuRouting
import com.miku.ray.R
import com.miku.ray.databinding.ActivityProxyGroupsBinding
import com.miku.ray.extension.applyEdgeToEdgeListInsets
import com.miku.ray.extension.snackbarError
import com.miku.ray.util.getColorAttr
import com.miku.ray.handler.MmkvManager
import com.miku.ray.ui.base.HelperBaseActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * The strategy groups of one profile: what a subscription's `proxy-groups`
 * declare, which member each group is on, and the latency of its members.
 *
 * It is a screen of its own rather than a second row under the mode bar: the
 * mode bar decides *how* traffic is routed, while this is where the config's
 * own groups are managed, and it belongs to the profile the user opened it
 * from — not to the current mode. Global mode keeps its own exit row, which
 * selects the built-in `GLOBAL` selector.
 *
 * The screen is assembled from the home screen's own parts: the group strip is
 * its tab bar (same item layout, same indicator), the rows are its cards in
 * miniature, and search and the latency actions sit in the toolbar as they do
 * on the log screen.
 */
class ProxyGroupsActivity : HelperBaseActivity() {

    private lateinit var binding: ActivityProxyGroupsBinding
    private val impl: MikuRouting.Impl? get() = MikuRouting.impl

    private var profileId: String? = null
    private var groups: List<MikuRouting.Group> = emptyList()
    private var current = 0
    private var sortByDelay = false
    private var query = ""

    private var probeJob: Job? = null

    /** This screen's own probe results, keyed by member name; empty when none ran. */
    private val probes = HashMap<String, Int>()

    private val adapter = ProxyMemberAdapter(onPick = ::pick, onTest = ::probe)

    /** Whether the running core carries this profile: the only live state. */
    private val live: Boolean get() = profileId != null && impl?.activeProfileId() == profileId

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        binding = ActivityProxyGroupsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupToolbar(
            binding.toolbar,
            showHomeAsUp = true,
            title = getString(R.string.mihomo_groups_entry),
            subtitle = getString(R.string.mihomo_groups_subtitle),
        )
        binding.memberList.applyEdgeToEdgeListInsets()
        binding.memberList.layoutManager = LinearLayoutManager(this)
        binding.memberList.adapter = adapter
        load()
    }

    override fun onDestroy() {
        probeJob?.cancel()
        super.onDestroy()
    }

    /** Groups come from the core (JNI + a JSON walk) or the stored YAML: off the frame. */
    private fun load() {
        val impl = impl ?: return render()
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { impl.groups(profileId) }.getOrDefault(emptyList()) }
            groups = loaded
            current = current.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
            render()
        }
    }

    // region rendering

    private fun render() {
        renderTabs()
        renderMembers()
        renderNote()
        invalidateOptionsMenu()
    }

    /** The group strip is the home screen's tab bar, item for item. */
    private fun renderTabs() {
        val tab = binding.groupTab
        tab.removeAllTabs()
        binding.groupTabCard.visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
        groups.forEachIndexed { index, group ->
            val view = layoutInflater.inflate(R.layout.item_tab_group, tab, false)
            // A strategy group carries no icon of its own; the home tabs do.
            view.findViewById<ImageView>(R.id.tab_icon).visibility = View.GONE
            view.findViewById<TextView>(R.id.tab_label).text = group.name
            // The badge counts members the way the home tabs count profiles.
            setBadge(view.findViewById(R.id.tab_badge), group.members.size)
            tab.addTab(tab.newTab().setCustomView(view).also { it.tag = index })
        }
        if (tab.tabCount > 0) tab.getTabAt(current)?.select()
        tab.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(selected: TabLayout.Tab) {
                val index = (selected.tag as? Int) ?: return
                if (index == current) return
                current = index
                probes.clear()
                renderMembers()
                invalidateOptionsMenu()
            }

            override fun onTabUnselected(selected: TabLayout.Tab) = Unit
            override fun onTabReselected(selected: TabLayout.Tab) = Unit
        })
    }

    private fun renderMembers() {
        val group = groups.getOrNull(current)
        val text = query.trim().lowercase()
        val visible = group?.members.orEmpty().filter { text.isEmpty() || it.name.lowercase().contains(text) }
        val rows = mutableListOf<ProxyMemberAdapter.Member>()
        // "Automatic" is a choice about the whole group, so a search hides it.
        if (group != null && group.automatic && text.isEmpty()) {
            rows += ProxyMemberAdapter.Member(name = "", type = group.type, automatic = true)
        }
        rows += visible.map { member ->
            ProxyMemberAdapter.Member(
                name = member.name,
                type = member.type,
                group = member.group,
                delay = member.delay,
                probed = probes[member.name],
            )
        }
        if (sortByDelay) {
            rows.sortBy { row -> row.probed?.takeIf { it >= 0 } ?: row.delay.takeIf { it >= 0 } ?: Int.MAX_VALUE }
        }
        adapter.submit(
            rows,
            // An automatic group that is not pinned is "on automatic", which the
            // extra row stands for; its empty name is also what picking it sends.
            // A selector is never "automatic": it always has a chosen member.
            selected = if (group?.automatic == true && !group.pinned) "" else group?.selected,
            regions = ExitRegion.measuredRegions(),
        )

        binding.memberList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        binding.groupsEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.groupsEmpty.setText(
            when {
                groups.isEmpty() -> R.string.mihomo_groups_empty
                query.isNotBlank() -> R.string.mihomo_exit_empty
                else -> R.string.mihomo_groups_unlisted
            },
        )
    }

    /** Says why the rows are the declared members rather than the core's data. */
    private fun renderNote() {
        binding.groupsNote.visibility = if (groups.isNotEmpty() && !live) View.VISIBLE else View.GONE
        binding.groupsNote.setText(
            if (MikuCoreBridge.isRunning()) R.string.mihomo_groups_inactive
            else R.string.mihomo_groups_offline,
        )
    }

    private fun setBadge(badge: TextView, count: Int) {
        if (count <= 0) {
            badge.visibility = View.GONE
            return
        }
        val limit = MmkvManager.decodeSettingsString(AppConfig.PREF_TAB_BADGE_LIMIT)?.toIntOrNull() ?: 0
        badge.text = if (limit > 0 && count > limit) "$limit+" else count.toString()
        badge.visibility = View.VISIBLE
    }

    // endregion

    // region actions

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_proxy_groups, menu)
        (menu.findItem(R.id.action_search).actionView as? SearchView)?.apply {
            queryHint = getString(R.string.mihomo_exit_search)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(text: String?): Boolean = false
                override fun onQueryTextChange(text: String?): Boolean {
                    // Qualified: inside the SearchView's apply block the bare
                    // name resolves to its own private field.
                    this@ProxyGroupsActivity.query = text.orEmpty()
                    renderMembers()
                    return true
                }
            })
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // Latency is whatever the core measured, so the actions only exist for
        // the profile it is carrying; the note on screen says why otherwise.
        menu.findItem(R.id.action_test_delay)?.isVisible = live
        menu.findItem(R.id.action_sort_delay)?.isVisible = live
        tintSort(menu.findItem(R.id.action_sort_delay))
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_test_delay -> { testGroup(); true }
            R.id.action_sort_delay -> {
                sortByDelay = !sortByDelay
                invalidateOptionsMenu()
                renderMembers()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** An engaged toggle reads as the accent colour, the way the chips do. */
    private fun tintSort(item: MenuItem?) {
        item ?: return
        item.icon?.setTint(
            getColorAttr(
                if (sortByDelay) androidx.appcompat.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOnSurfaceVariant,
            ),
        )
    }

    private fun pick(name: String) {
        val group = groups.getOrNull(current) ?: return
        if (impl?.selectGroupMember(group.name, name, profileId) != true) {
            snackbarError(R.string.mihomo_mode_failed, title = getString(R.string.title_alerter_error))
            return
        }
        // Re-read instead of patching: live, the core is the truth about the
        // selection (an automatic group may have re-chosen while we asked).
        lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) { runCatching { impl?.groups(profileId) }.getOrNull() }
                ?.firstOrNull { it.name == group.name }
            if (fresh != null) groups = groups.toMutableList().also { it[current] = fresh }
            renderMembers()
        }
    }

    /** Probes every member of the visible group, streaming results in as they land. */
    private fun testGroup() {
        val group = groups.getOrNull(current) ?: return
        val impl = impl ?: return
        if (probeJob?.isActive == true) return
        val targets = group.members.map { it.name }
        targets.forEach { probes[it] = ProxyMemberAdapter.TESTING }
        renderMembers()
        probeJob = lifecycleScope.launch {
            val gate = Semaphore(PROBE_CONCURRENCY)
            try {
                targets.map { name ->
                    async(Dispatchers.IO) { gate.withPermit { name to impl.delay(name) } }
                }.awaitAll().forEach { (name, delay) ->
                    probes[name] = delay
                    adapter.updateProbe(name, delay)
                }
            } finally {
                if (sortByDelay) renderMembers()
            }
        }
    }

    /** Probes one member, from its row's action button. */
    private fun probe(name: String) {
        val impl = impl ?: return
        if (name.isBlank()) return
        probes[name] = ProxyMemberAdapter.TESTING
        adapter.updateProbe(name, ProxyMemberAdapter.TESTING)
        lifecycleScope.launch {
            val delay = withContext(Dispatchers.IO) { runCatching { impl.delay(name) }.getOrDefault(-1) }
            probes[name] = delay
            adapter.updateProbe(name, delay)
        }
    }

    // endregion

    companion object {
        /** The profile whose groups are listed; the selected one when omitted. */
        const val EXTRA_PROFILE_ID = "profileId"

        /** Matches the phone-side batch probes: enough to keep a group responsive. */
        private const val PROBE_CONCURRENCY = 16
    }
}
