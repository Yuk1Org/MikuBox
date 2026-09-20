package top.uwu.mikubox.profile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import top.uwu.mikubox.R

/** Entry points for file, YAML, URI and subscription imports; intentionally UI-free. */
object MihomoProfileImporter {

    fun importConfig(context: Context, name: String, yaml: String): MihomoProfileStore.Profile =
        MihomoProfileStore.create(context, name, MihomoSubscriptionDecoder.toMihomoConfig(context, yaml))

    fun importSubscription(
        context: Context,
        name: String,
        url: String,
        intervalMinutes: Long = 24 * 60,
        updateWhenConnectedOnly: Boolean = false,
    ): MihomoProfileStore.Profile =
        MihomoProfileStore.createSubscription(context, name, url, intervalMinutes, updateWhenConnectedOnly)

    fun importUri(context: Context, uri: Uri): MihomoProfileStore.Profile {
        val subscriptionUrl = when {
            uri.scheme.equals("clash", true) && uri.host == "install-config" -> uri.getQueryParameter("url")
            uri.scheme.equals("sn", true) && uri.host == "subscription" -> uri.getQueryParameter("url")
            else -> null
        }
        if (!subscriptionUrl.isNullOrBlank()) {
            return importSubscription(
                context,
                uri.getQueryParameter("name") ?: context.getString(R.string.profile_subscription_default_name),
                subscriptionUrl,
            )
        }
        if (uri.scheme?.lowercase() in setOf("ss", "ssr", "vmess", "vless", "trojan",
                "socks", "socks5", "hysteria", "hysteria2", "hy2", "tuic", "ssh")) {
            return importConfig(context, uri.fragment ?: context.getString(R.string.profile_imported_default_name), uri.toString())
        }
        // Any other URI is a document pointing at the YAML itself; the URI string
        // is not the content, so read it through the resolver.
        val content = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull().orEmpty()
        if (content.isBlank()) {
            throw IllegalArgumentException(context.getString(R.string.error_import_file_empty))
        }
        return importConfig(context, displayName(context, uri), content)
    }

    /** The document's display name when the provider offers one, else its last segment. */
    private fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) {
                        cursor.getString(column)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.profile_imported_default_name)
    }
}
