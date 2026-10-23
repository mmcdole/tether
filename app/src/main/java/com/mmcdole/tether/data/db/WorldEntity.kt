package com.mmcdole.tether.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "worlds")
data class WorldEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val username: String?,
    /** Base64 of IV + AES/GCM ciphertext from LoginSecrets. Never plain text. */
    val encryptedPassword: String?,
    val lastConnectedAt: Long?,
    val createdAt: Long,
)
