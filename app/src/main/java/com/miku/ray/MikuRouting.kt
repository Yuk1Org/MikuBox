package com.miku.ray

/** Routing capability implemented by the host core, including offline choices. */
object MikuRouting {
    data class Exit(
        val name: String,
        val group: Boolean,
        /** Live round-trip latency in ms from the core; negative when unknown (offline, untested). */
        val delay: Int = -1,
        /** Core type string, e.g. "Selector" or "Shadowsocks"; empty offline. */
        val type: String = "",
    )
    /**
     * One strategy group of the active profile, with the members it can point at.
     *
     * A profile imported from a subscription carries these as `proxy-groups`; the
     * list is the config's own, not one this app invents. Offline it holds what
     * the YAML declares, live what the core reports (including provider nodes).
     */
    data class Group(
        val name: String,
        /** Core type string: "Selector", "URLTest", ... empty offline. */
        val type: String = "",
        /** Members in the config's order; a member may be another group. */
        val members: List<Exit> = emptyList(),
        /** Member in use — or the stored choice while offline; null when nothing is known. */
        val selected: String? = null,
        /** True for groups the core picks members for (url-test/fallback/...). */
        val automatic: Boolean = false,
        /** True when an automatic group has been pinned to [selected]. */
        val pinned: Boolean = false,
    )

    data class State(val mode: String, val exit: String?, val options: List<Exit>)
    interface Impl {
        fun state(): State
        fun mode(value: String): Boolean
        fun exit(name: String): Boolean

        /** The profile the running core was started from; null while it is stopped. */
        fun activeProfileId(): String?

        /**
         * Strategy groups of a profile, in the config's declared order — the
         * selected one when [profileId] is null. Live from the core; offline
         * from the stored profile YAML, where provider-backed members are
         * unknowable and stay unlisted.
         */
        fun groups(profileId: String? = null): List<Group>

        /**
         * Points [group] at [member]. An empty [member] clears the pin of an
         * automatic group, returning it to automatic selection. The choice is
         * remembered for that profile's next start, and applied to the running
         * core immediately when it is the profile the core is carrying.
         */
        fun selectGroupMember(group: String, member: String, profileId: String? = null): Boolean

        /**
         * Round-trip latency of one proxy in ms through the running core,
         * negative when unknown; blocking. Meaningful only for the active
         * profile: the core can only probe the nodes it is running.
         */
        fun delay(name: String): Int
    }
    var impl: Impl? = null
}
