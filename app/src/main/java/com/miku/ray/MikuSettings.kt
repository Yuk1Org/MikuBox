package com.miku.ray

import androidx.fragment.app.Fragment

/** UI host seam. The app owns its native core settings and storage. */
object MikuSettings {
    interface Impl {
        fun coreFragment(): Fragment
        fun vpnFragment(): Fragment
        fun mixedPort(): Int
        fun selectedProfileId(): String?
    }
    var impl: Impl? = null
}
