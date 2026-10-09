package com.miku.ray.ui.splash

import com.miku.ray.ui.main.MainActivity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.miku.ray.AppConfig.PREF_SHOW_SPLASH
import com.miku.ray.R
import com.miku.ray.extension.delay
import com.miku.ray.handler.MmkvManager
import com.miku.ray.ui.base.BaseActivity
import com.miku.ray.util.AppNameHelper
import kotlinx.coroutines.launch

/**
 * The stop between welcome and home.
 *
 * The artwork below is the reference client's own splash, behind the same
 * switch: the logo, the app's display name and the version dwell for two
 * seconds and hand over with a cross-fade. With the switch off — the default,
 * as upstream ships it — the route to home is immediate, and the Android 12+
 * system splash's mask reveal carries the transition by itself; that immediate
 * route is what this screen did unconditionally until the switch was wired.
 */
class SplashActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Re-entering the app from recents must not replay the splash; the
        // reference checks this before any activity setup, so the finished
        // instance never touches the theme or the settings store.
        if (!isTaskRoot) {
            val intentAction = intent.action
            if (intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
                intentAction != null && intentAction == Intent.ACTION_MAIN
            ) {
                finish()
                return
            }
        }

        super.onCreate(savedInstanceState)

        if (!MmkvManager.decodeSettingsBool(PREF_SHOW_SPLASH, false)) {
            navigateToMain()
            return
        }

        setContentView(R.layout.uwu_activity_splash)

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

        findViewById<TextView>(R.id.splash_name).text = AppNameHelper.getDisplayName(this)

        val versionText = findViewById<TextView>(R.id.splash_version)
        versionText.text = getString(
            R.string.uwu_splash_summary,
            getString(R.string.uwu_version_name),
            getString(R.string.uwu_version_code).toInt()
        )

        // The splash is a poster, not a screen: back must not peel it off early
        // and leave the task with nothing to show. The hand-over is the timer
        // below, and the callback goes with the activity.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
            }
        })

        lifecycleScope.launch {
            delay(SPLASH_DWELL_MILLIS)
            navigateToMain()
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)

        val options = ActivityOptionsCompat.makeCustomAnimation(
            this,
            R.anim.fade_in,
            R.anim.fade_out
        )

        startActivity(intent, options.toBundle())

        finish()
    }

    private companion object {
        /** The reference's dwell; the switch's whole point is this beat. */
        const val SPLASH_DWELL_MILLIS = 2000L
    }
}
