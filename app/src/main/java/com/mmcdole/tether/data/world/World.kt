package com.mmcdole.tether.data.world

/** A MUD and the character to log in as. The password stays encrypted in the database; see [WorldRepository.password]. */
data class World(
    val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int,
    val tls: Boolean = false,
    val username: String? = null,
    val hasPassword: Boolean = false,
    val lastConnectedAt: Long? = null,
)

sealed interface PasswordEdit {
    data object Keep : PasswordEdit
    data object Clear : PasswordEdit
    data class Set(val value: String) : PasswordEdit
}
