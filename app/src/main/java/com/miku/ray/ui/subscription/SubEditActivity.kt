package com.miku.ray.ui.subscription

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.widget.AppCompatEditText
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.miku.ray.MikuSubscriptions
import com.miku.ray.R
import com.miku.ray.ui.base.HelperBaseActivity

/**
 * Add or edit a Clash subscription.
 *
 * MikuRay's editor asks for a lot this app cannot honour — user agent, request
 * headers, node filters, profile chaining, TLS options — because those describe
 * a per-protocol server record rather than a Clash subscription. A Clash
 * subscription needs four things, and those are the four this screen shows: the
 * URL, whether to refresh on a schedule, the interval, and whether the refresh
 * goes through the tunnel. The remaining rows are hidden rather than left to
 * store values nothing reads.
 *
 * The layout and its labels stay MikuRay's; only the field set is narrowed, and
 * the values are handed to the app through [MikuSubscriptions].
 */
class SubEditActivity : HelperBaseActivity() {

    /** MikuRay's callers pass the entry id as `subId`; this screen also accepts `profileId`. */
    private val subscriptionId: String?
        get() = intent.getStringExtra("subId")?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra("profileId")?.takeIf { it.isNotBlank() }

    private var saving = false

    private lateinit var name: AppCompatEditText
    private lateinit var url: AppCompatEditText
    private lateinit var interval: AppCompatEditText
    private lateinit var autoUpdate: MaterialAutoCompleteTextView
    private lateinit var throughProxy: MaterialAutoCompleteTextView

    private val boolEntries: Array<out String> by lazy { resources.getStringArray(R.array.bool_dropdown_entries) }
    private val boolValues: Array<out String> by lazy { resources.getStringArray(R.array.bool_dropdown_values) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString("savedSubscriptionId")?.let { intent.putExtra("subId", it) }
        setContentView(R.layout.activity_sub_edit)

        setupToolbar(
            findViewById<MaterialToolbar>(R.id.toolbar),
            showHomeAsUp = true,
            title = getString(R.string.title_sub_setting),
            subtitle = getString(R.string.subtitle_server_config),
        )

        name = findViewById(R.id.et_remarks)
        url = findViewById(R.id.et_url)
        interval = findViewById(R.id.et_update_interval)
        autoUpdate = findViewById(R.id.chk_enable)
        throughProxy = findViewById(R.id.auto_update_check)

        val entries = ArrayAdapter(this, android.R.layout.simple_list_item_1, boolEntries)
        autoUpdate.setAdapter(entries)
        labelOf(autoUpdate)?.hint = getString(R.string.sub_auto_update)
        throughProxy.setAdapter(entries)
        // The second dropdown is MikuRay's own "auto update" switch; here it
        // carries the one Clash flag its editor has no other place for. Its wording
        // is app-specific, so it is set here rather than taken from the vendored
        // strings (which have no equivalent).
        labelOf(throughProxy)?.apply {
            hint = getString(R.string.subscription_update_through_proxy)
            helperText = getString(R.string.subscription_update_through_proxy_summary)
        }

        hide(
            R.id.til_tab_icon,
            R.id.et_user_agent,
            R.id.et_request_headers,
            R.id.et_filter,
            R.id.et_network_filter,
            R.id.et_protocol_filter,
            R.id.et_pre_profile,
            R.id.et_next_profile,
            R.id.allow_insecure_url,
        )

        load()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_SAVE, Menu.NONE, android.R.string.ok)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_SAVE) {
            save()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun load() {
        val existing = subscriptionId
            ?.let { id -> MikuSubscriptions.list().firstOrNull { it.id == id } }
            ?: run {
                autoUpdate.setText(entryForBool(false), false)
                throughProxy.setText(entryForBool(false), false)
                return
            }
        name.setText(existing.name)
        url.setText(existing.url)
        interval.setText(existing.intervalMinutes.toString())
        autoUpdate.setText(entryForBool(existing.autoUpdate), false)
        throughProxy.setText(entryForBool(existing.throughProxy), false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("savedSubscriptionId", subscriptionId)
        super.onSaveInstanceState(outState)
    }

    private fun save() {
        if (saving) return
        val address = url.text?.toString()?.trim().orEmpty()
        if (address.isEmpty()) {
            // MikuRay's own copy for an unusable subscription; the vendored
            // resources are what this screen has to draw from.
            url.error = getString(R.string.empty_subscription_list_title)
            return
        }
        val minutes = interval.text?.toString()?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val title = name.text?.toString()?.trim().orEmpty()
        val automatic = boolValue(autoUpdate)
        val proxy = boolValue(throughProxy)
        saving = true
        showLoading()
        lifecycleScope.launch {
            try {
                val fetched = MikuSubscriptions.saveAndRefresh(
                    subscriptionId, title, address, automatic, minutes, proxy,
                    onSaved = { intent.putExtra("subId", it) },
                )
                if (fetched) finish()
                else android.widget.Toast.makeText(this@SubEditActivity,
                    R.string.subscription_initial_fetch_failed, android.widget.Toast.LENGTH_LONG).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                url.error = error.message ?: getString(R.string.title_alerter_error)
            } finally {
                saving = false
                hideLoading()
            }
        }
    }

    // region field helpers

    private fun hide(vararg ids: Int) {
        ids.forEach { id ->
            val field = findViewById<View>(id) ?: return@forEach
            // Hiding the field alone leaves its card behind, so the whole
            // TextInputLayout goes.
            var container: View = field
            var parent = field.parent
            while (parent is View) {
                if (parent is TextInputLayout) {
                    container = parent
                    break
                }
                parent = parent.parent
            }
            container.visibility = View.GONE
        }
    }

    private fun labelOf(field: View): TextInputLayout? {
        var parent = field.parent
        while (parent is View) {
            if (parent is TextInputLayout) return parent
            parent = parent.parent
        }
        return null
    }

    private fun entryForBool(value: Boolean): String {
        val index = boolValues.indexOf(value.toString())
        return boolEntries.getOrElse(if (index >= 0) index else 1) { value.toString() }
    }

    private fun boolValue(field: MaterialAutoCompleteTextView): Boolean {
        val index = boolEntries.indexOf(field.text?.toString().orEmpty())
        return boolValues.getOrElse(if (index >= 0) index else 1) { "false" } == "true"
    }

    // endregion

    private companion object {
        const val MENU_SAVE = 0x3001
    }
}
