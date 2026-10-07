package com.miku.ray.fmt

import com.miku.ray.AppConfig
import com.miku.ray.dto.entities.ProfileItem
import com.miku.ray.extension.nullIfBlank

/**
 * AnyTLS share links, in the scheme the protocol documents and mihomo's own
 * converter reads: `anytls://password@host:port?sni=&insecure=&hpkp=#name`.
 *
 * The password is the userinfo; when the user typed the pair colon-separated
 * the codec keeps only the password, which is what a receiver expects.
 */
object AnytlsFmt : FmtBase() {
    fun toUri(config: ProfileItem): String {
        val password = config.password.orEmpty().substringAfter(':')
        val dicQuery = HashMap<String, String>()
        config.sni?.nullIfBlank()?.let { dicQuery["sni"] = it }
        config.alpn?.nullIfBlank()?.let { dicQuery["alpn"] = it.replace(" ", ",") }
        config.fingerPrint?.nullIfBlank()?.let { dicQuery["hpkp"] = it }
        config.insecure?.let { dicQuery["insecure"] = if (it) "1" else "0" }
        return toUri(config, password, dicQuery)
    }
}
