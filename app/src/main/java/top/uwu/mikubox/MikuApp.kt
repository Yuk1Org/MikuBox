package top.uwu.mikubox

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * The application class.
 *
 * It extends the vendored `AngApplication` rather than `Application`: MikuRay's
 * screens ask for it by name — its view models are `AndroidViewModel`s typed to
 * that class, and its activity lifecycle callbacks are what apply the theme, the
 * font and the locale — so inheriting from it is what lets the vendored code run
 * unchanged. `super.onCreate()` brings up its settings store and theme; MikuBox's
 * own setup follows.
 */
class MikuApp : com.miku.ray.AngApplication() {

    override fun onCreate() {
        super.onCreate()
        // Hands the vendored MikuRay layer the mihomo side of its core seam, so
        // its connect button drives MikuBox's own tunnel.
        top.uwu.mikubox.core.MikuRayBridgeContext.attach(this)
        com.miku.ray.MikuCoreBridge.install(top.uwu.mikubox.core.MikuRayCoreBridge)
        // The vendored subscription screens read and write this app's profiles
        // through the same seam.
        com.miku.ray.MikuSubscriptions.install(top.uwu.mikubox.core.MikuRaySubscriptions)
        // Mirrors this app's profiles into the vendored profile list, so its home
        // screen shows them and its selection maps back to what we connect with.
        runCatching { top.uwu.mikubox.core.MikuRayProfileSync.sync(this) }
        // Section artwork: MikuRay's own default is the `gradient` style, which is
        // also the only one drawn as a shape badge with a tinted glyph — the other
        // seventeen are character artwork. Seeded once so a fresh install matches
        // the target; a later choice in Settings → UI sticks.
        runCatching {
            val marker = "category_style_seeded"
            if (!com.miku.ray.handler.MmkvManager.decodeSettingsBool(marker, false)) {
                com.miku.ray.handler.MmkvManager.encodeSettings(com.miku.ray.AppConfig.PREF_CATEGORY_STYLE, "gradient")
                com.miku.ray.handler.MmkvManager.encodeSettings(marker, true)
            }
        }
        // The row's traffic line is behind its own switch: the numbers are there
        // to be shown, so it is seeded on as well. Its own marker rather than the
        // one above, which belonged to the section artwork and would have shipped
        // this default to nobody.
        runCatching {
            val marker = "traffic_line_seeded"
            if (!com.miku.ray.handler.MmkvManager.decodeSettingsBool(marker, false)) {
                com.miku.ray.handler.MmkvManager.encodeSettings(com.miku.ray.AppConfig.PREF_TRAFFIC_ENABLED, true)
                com.miku.ray.handler.MmkvManager.encodeSettings(marker, true)
            }
        }
        // The splash the reference shows on every start is behind its own switch,
        // which defaults to off; a fresh install gets it on, and the switch in
        // the UI settings can turn it off again.
        runCatching {
            val marker = "splash_seeded"
            if (!com.miku.ray.handler.MmkvManager.decodeSettingsBool(marker, false)) {
                com.miku.ray.handler.MmkvManager.encodeSettings(com.miku.ray.AppConfig.PREF_SHOW_SPLASH, true)
                com.miku.ray.handler.MmkvManager.encodeSettings(marker, true)
            }
        }
        // Night mode and the language are MikuRay's own settings and it applies
        // both itself — `AngApplication.onCreate` sets the night mode before this
        // runs, and every ported activity wraps its context with the chosen
        // locale. This app kept a second copy of both, which is what the app's
        // settings store used to hold; it is gone.
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // The palette is MikuRay's business: its own base activity applies the
        // chosen family, dynamic colour or custom seed. This app used to theme
        // every activity from its own manager first, and the two of them fought
        // over the same activity.

        // The MikuRay font setting applies to the whole app: the global typeface
        // override covers what the platform creates from here on, and the view-tree
        // pass covers what this activity already inflated. This is the same pair
        // AngApplication applies from its own activity callbacks.
        com.miku.ray.AngApplication.getCustomTypeface(this)?.let { typeface ->
            com.miku.ray.util.CustomFontManager.applyGlobalOverride(typeface)
            com.miku.ray.util.CustomFontManager.applyToViewTree(typeface, activity.window.decorView)
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
