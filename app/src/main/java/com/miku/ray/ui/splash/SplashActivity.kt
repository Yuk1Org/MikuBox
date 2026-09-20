package com.miku.ray.ui.splash

import android.content.Intent
import android.os.Bundle
import com.miku.ray.AppConfig.PREF_SHOW_SPLASH
import com.miku.ray.handler.MmkvManager
import com.miku.ray.ui.base.BaseActivity
import com.miku.ray.ui.main.MainActivity

/** Routes the launcher to home; the artwork fades in the same window as home. */
class SplashActivity : BaseActivity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isTaskRoot && intent.action == Intent.ACTION_MAIN &&
            intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            finish()
            return
        }
        startActivity(Intent(this, MainActivity::class.java).putExtra(
            StartupArtwork.EXTRA_SHOW, MmkvManager.decodeSettingsBool(PREF_SHOW_SPLASH, false),
        ))
        overridePendingTransition(0, 0)
        finish()
    }
}
