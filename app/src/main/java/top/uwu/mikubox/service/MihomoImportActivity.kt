package top.uwu.mikubox.service

import android.app.Activity
import android.os.Bundle
import top.uwu.mikubox.profile.MihomoProfileImporter

/** Transparent deep-link importer for UwU's share/import workflows. */
class MihomoImportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        if (data != null) {
            // Plain Activity has no lifecycleScope; a raw thread keeps the read
            // and parse off the main thread, and finish() only fires once the
            // import settles so the transient URI grant outlives the work.
            Thread {
                runCatching { MihomoProfileImporter.importUri(this, data) }
                runOnUiThread { finish() }
            }.start()
        } else {
            finish()
        }
    }
}
