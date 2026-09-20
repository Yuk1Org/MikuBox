package com.miku.ray.ui.routing

import android.content.Intent
import android.os.Bundle
import com.miku.ray.MikuSettings
import com.miku.ray.ui.base.BaseActivity
import com.miku.ray.ui.server.ServerCustomConfigActivity

/** Route editing uses the selected mihomo profile and its native rules syntax. */
class RoutingSettingActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            startActivity(Intent(this, ServerCustomConfigActivity::class.java).apply {
                MikuSettings.impl?.selectedProfileId()?.let { putExtra("guid", it) }
            })
        }
        finish()
    }
}
