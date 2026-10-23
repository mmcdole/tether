package com.mmcdole.tether.data.rules

import com.mmcdole.tether.data.db.RuleDao
import com.mmcdole.tether.data.db.RuleEntity
import com.mmcdole.tether.data.db.RuleKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Keeps the real [RuleDao.apply], so transfer and import run the same steps as with Room. */
class FakeRuleDao : RuleDao() {
    val rows = MutableStateFlow<Map<Long, RuleEntity>>(emptyMap())
    private var nextId = 1L

    private fun sorted() = rows.value.values.sortedWith(compareBy({ it.createdAt }, { it.id }))

    override fun observeFor(worldId: Long?): Flow<List<RuleEntity>> =
        rows.map { sorted().filter { it.worldId == null || it.worldId == worldId } }

    override suspend fun getAll() = sorted()

    override suspend fun get(id: Long) = rows.value[id]

    override suspend fun find(worldId: Long?, kind: RuleKind, pattern: String) =
        sorted().filter { it.worldId == worldId && it.kind == kind && it.pattern == pattern }

    override suspend fun insert(rule: RuleEntity): Long {
        val id = nextId++
        rows.update { it + (id to rule.copy(id = id)) }
        return id
    }

    override suspend fun insertAll(rules: List<RuleEntity>) = rules.map { insert(it) }

    override suspend fun update(rule: RuleEntity) = rows.update { if (rule.id in it) it + (rule.id to rule) else it }

    override suspend fun deleteAll(ids: Collection<Long>) = rows.update { it - ids.toSet() }

    override suspend fun setEnabled(id: Long, enabled: Boolean) =
        rows.update { map -> map[id]?.let { map + (id to it.copy(enabled = enabled)) } ?: map }
}
