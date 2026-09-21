package com.mikubox.mihomo.core

import com.miku.ray.MikuProfiles
import com.miku.ray.util.LogUtil
import com.mikubox.mihomo.profile.MihomoProfileStore
import com.mikubox.mihomo.profile.MihomoSubscriptionDecoder
import com.mikubox.mihomo.profile.MihomoSubscriptionUpdater

object MikuRayProfiles : MikuProfiles.Impl {
    private val context get() = checkNotNull(MikuRayBridgeContext.application)
    override fun get(id: String): MikuProfiles.Profile? =
        MihomoProfileStore.profiles(context).firstOrNull { it.id == id }?.let {
            MikuProfiles.Profile(it.id, it.name, it.config)
        }

    override fun save(id: String?, name: String, content: String): String {
        val config = MihomoSubscriptionDecoder.toMihomoConfig(context, content)
        val previous = id?.takeIf { it.isNotBlank() }?.let { wanted ->
            requireNotNull(MihomoProfileStore.profiles(context).firstOrNull { it.id == wanted })
        }
        val result = if (previous == null) MihomoProfileStore.create(context, name, config)
        else previous.copy(name = name, config = config, updatedAtMillis = System.currentTimeMillis()).also {
            MihomoProfileStore.update(context, it)
        }
        sync()
        return result.id
    }

    override fun importContent(content: String): Pair<Int, Int> {
        val text = content.trim()
        val uri = android.net.Uri.parse(text)
        if (!text.contains('\n') && uri.scheme in listOf("clash", "sn")) {
            val profile = try {
                com.mikubox.mihomo.profile.MihomoProfileImporter.importUri(context, uri)
            } finally { sync() }
            return (if (profile.isSubscription) 0 to 1 else 1 to 0)
        }
        if (!text.contains('\n') && uri.scheme in listOf("http", "https") && uri.userInfo == null) {
            try {
                com.mikubox.mihomo.profile.MihomoProfileImporter.importSubscription(context, uri.host ?: "Subscription", text)
            } finally { sync() }
            return 0 to 1
        }
        save(null, context.getString(com.mikubox.mihomo.R.string.profile_imported_default_name), text)
        return 1 to 0
    }

    override fun remove(id: String) {
        if (get(id) != null) MihomoProfileStore.remove(context, id)
    }
    override fun select(id: String) {
        if (get(id) != null) {
            MihomoProfileStore.select(context, id)
        }
    }
    override fun sync() {
        runCatching { MikuRayProfileSync.sync(context) }
            .onFailure { LogUtil.w(message = "Profile mirror unavailable", throwable = it) }
    }
    override fun exportBackup(): String = BackupManager.export(context)
    override fun validateBackup(content: String) = BackupManager.validate(context, content)
    override fun restoreBackup(content: String) {
        BackupManager.import(context, content)
        MihomoSubscriptionUpdater.reconfigure(context)
        sync()
    }
}
