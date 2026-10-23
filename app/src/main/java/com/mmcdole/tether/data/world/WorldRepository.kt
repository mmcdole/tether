package com.mmcdole.tether.data.world

import com.mmcdole.tether.data.db.WorldDao
import com.mmcdole.tether.data.db.WorldEntity
import com.mmcdole.tether.di.IoDispatcher
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class WorldRepository @Inject constructor(
    private val dao: WorldDao,
    private val secrets: LoginSecrets,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    val worlds: Flow<List<World>> = dao.observeAll().map { list -> list.map { it.toWorld() } }

    suspend fun get(id: Long): World? = dao.get(id)?.toWorld()

    /** Inserts when [World.id] is 0, otherwise updates. Only the editable fields are written. Returns the id. */
    suspend fun save(world: World, password: PasswordEdit = PasswordEdit.Keep): Long {
        val existing = if (world.id == 0L) null else dao.get(world.id)
        val encryptedPassword = when (password) {
            PasswordEdit.Keep -> existing?.encryptedPassword
            PasswordEdit.Clear -> null
            is PasswordEdit.Set -> withContext(io) { secrets.encrypt(password.value) }
        }
        val entity = WorldEntity(
            id = existing?.id ?: 0,
            name = world.name,
            host = world.host,
            port = world.port,
            tls = world.tls,
            username = world.username,
            encryptedPassword = encryptedPassword,
            lastConnectedAt = existing?.lastConnectedAt,
            createdAt = existing?.createdAt ?: clock.millis(),
        )
        return if (existing == null) dao.insert(entity) else entity.id.also { dao.update(entity) }
    }

    suspend fun delete(id: Long) = dao.delete(id)

    /** Decrypted only at connect time. */
    suspend fun password(id: Long): String? {
        val encrypted = dao.get(id)?.encryptedPassword ?: return null
        return withContext(io) { secrets.decrypt(encrypted) }
    }

    suspend fun markConnected(id: Long) = dao.setLastConnected(id, clock.millis())
}

private fun WorldEntity.toWorld() = World(
    id = id,
    name = name,
    host = host,
    port = port,
    tls = tls,
    username = username,
    hasPassword = encryptedPassword != null,
    lastConnectedAt = lastConnectedAt,
)
