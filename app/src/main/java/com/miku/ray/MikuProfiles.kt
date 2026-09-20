package com.miku.ray

/** Native configuration storage behind the ported UI. Null only in UI previews. */
object MikuProfiles {
    data class Profile(val id: String, val name: String, val config: String)
    interface Impl {
        fun get(id: String): Profile?
        fun save(id: String?, name: String, content: String): String
        fun importContent(content: String): Pair<Int, Int>
        fun remove(id: String)
        fun select(id: String)
        fun sync()
        fun exportBackup(): String
        fun validateBackup(content: String)
        fun restoreBackup(content: String)
    }
    @Volatile var impl: Impl? = null
        private set
    fun install(implementation: Impl) { impl = implementation }
}
