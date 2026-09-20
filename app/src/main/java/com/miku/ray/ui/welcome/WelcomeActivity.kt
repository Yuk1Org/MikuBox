package com.miku.ray.ui.welcome

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.miku.ray.R
import com.miku.ray.handler.MmkvManager
import com.miku.ray.ui.base.BaseActivity
import com.miku.ray.ui.main.MainActivity
import com.miku.ray.ui.splash.StartupArtwork

class WelcomeActivity : BaseActivity() {
    private var currentPage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (MmkvManager.decodeSettingsBool(PREF_WELCOME_COMPLETED, false)) {
            window.decorView.viewTreeObserver.addOnPreDrawListener { false }
            navigateToMain(awaitSystemHandoff = true)
            return
        }

        setContentView(R.layout.uwu_activity_welcome)

        val rootLayout = findViewById<View>(R.id.main_content)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = systemBars.left,
                top = systemBars.top,
                right = systemBars.right,
                bottom = systemBars.bottom
            )
            insets
        }

        currentPage = savedInstanceState?.getInt(STATE_PAGE, 0)?.coerceIn(0, 2) ?: 0
        setupViewsAndListeners()
    }

    private fun setupViewsAndListeners() {
        showPage(currentPage)

        findViewById<View>(R.id.page_1button).setOnClickListener {
            showPage(1)
        }

        findViewById<View>(R.id.page_2button).setOnClickListener {
            showPage(2)
        }

        val navigateAction = View.OnClickListener { navigateToMain() }

        findViewById<View>(R.id.page_3button).setOnClickListener(navigateAction)
        findViewById<View>(R.id.page_1_skip).setOnClickListener(navigateAction)
        findViewById<View>(R.id.page_2_skip).setOnClickListener(navigateAction)
    }

    private fun showPage(page: Int) {
        currentPage = page
        listOf(R.id.page1, R.id.page2, R.id.page3).forEachIndexed { index, id ->
            findViewById<View>(id).visibility = if (index == page) View.VISIBLE else View.GONE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_PAGE, currentPage)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    private fun navigateToMain(awaitSystemHandoff: Boolean = false) {
        MmkvManager.encodeSettings(PREF_WELCOME_COMPLETED, true)
        startActivity(Intent(this, MainActivity::class.java).putExtra(
            StartupArtwork.EXTRA_SHOW,
            MmkvManager.decodeSettingsBool(com.miku.ray.AppConfig.PREF_SHOW_SPLASH, false),
        ).putExtra(StartupArtwork.EXTRA_SYSTEM_HANDOFF, awaitSystemHandoff))
        overridePendingTransition(0, 0)
        finish()
    }

    companion object {
        // Do not inherit the old port's completion flag: MikuBox's introduction
        // must be shown once even when those settings already exist.
        private const val PREF_WELCOME_COMPLETED = "pref_mikubox_welcome_completed"
        private const val STATE_PAGE = "welcome_page"
    }
}
