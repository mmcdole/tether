package com.mmcdole.tether.data.world

import com.mmcdole.tether.data.db.WorldDao
import com.mmcdole.tether.data.db.WorldEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakeWorldDao : WorldDao {
    val rows = MutableStateFlow<Map<Long, WorldEntity>>(emptyMap())
    private var nextId = 1L

    override fun observeAll(): Flow<List<WorldEntity>> =
        rows.map { it.values.sortedWith(compareBy({ w -> w.createdAt }, { w -> w.id })) }

    override suspend fun get(id: Long) = rows.value[id]

    override suspend fun insert(world: WorldEntity): Long {
        val id = nextId++
        rows.update { it + (id to world.copy(id = id)) }
        return id
    }

    override suspend fun update(world: WorldEntity) = rows.update { if (world.id in it) it + (world.id to world) else it }

    override suspend fun delete(id: Long) = rows.update { it - id }

    override suspend fun setLastConnected(id: Long, at: Long) =
        rows.update { map -> map[id]?.let { map + (id to it.copy(lastConnectedAt = at)) } ?: map }
}
