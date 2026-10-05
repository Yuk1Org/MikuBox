package com.miku.ray.ui.main

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import com.miku.ray.R
import com.miku.ray.extension.applyEdgeToEdgeListInsets
import com.miku.ray.ui.base.HelperBaseActivity

/**
 * The strategy groups of one profile: what a subscription's `proxy-groups`
 * declare, which member each group is on, and the latency of the members.
 *
 * It is a screen of its own rather than a second row under the mode bar: the
 * mode bar decides *how* traffic is routed, while this is where the config's
 * own groups are managed, and it belongs to the profile the user opened it
 * from — not to the current mode. Global mode keeps its own exit row, which
 * selects the built-in `GLOBAL` selector.
 */
class ProxyGroupsActivity : HelperBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_proxy_groups)
        setupToolbar(
            findViewById(R.id.toolbar),
            showHomeAsUp = true,
            title = getString(R.string.mihomo_groups_entry),
            subtitle = getString(R.string.mihomo_groups_subtitle),
        )
        findViewById<View>(R.id.proxy_groups_content).applyEdgeToEdgeListInsets()
        findViewById<LinearLayout>(R.id.proxy_groups_content).addView(
            ProxyGroupPanel(this, intent.getStringExtra(EXTRA_PROFILE_ID)),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    companion object {
        /** The profile whose groups are listed; the selected one when omitted. */
        const val EXTRA_PROFILE_ID = "profileId"
    }
}
