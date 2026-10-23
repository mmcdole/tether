package com.mmcdole.tether.data.rules

import com.mmcdole.tether.data.db.RuleDao
import com.mmcdole.tether.data.db.RuleEntity
import com.mmcdole.tether.data.db.RuleKind
import com.mmcdole.tether.di.IoDispatcher
import com.mmcdole.tether.mud.rules.Alias
import com.mmcdole.tether.mud.rules.RuleSet
import com.mmcdole.tether.mud.rules.Scope
import com.mmcdole.tether.mud.rules.Trigger
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** One world's rules and the global ones, each in creation order. With no world, the world lists are empty. */
data class RuleLists(
    val worldTriggers: List<Trigger> = emptyList(),
    val globalTriggers: List<Trigger> = emptyList(),
    val worldAliases: List<Alias> = emptyList(),
    val globalAliases: List<Alias> = emptyList(),
) {
    val triggerCount get() = worldTriggers.size + globalTriggers.size
    val aliasCount get() = worldAliases.size + globalAliases.size
}

/** What a `#unalias`, `#unaction` or `#ungag` did. */
sealed interface Removal {
    data class Removed(val scope: Scope) : Removal

    data object NotFound : Removal
}

@Singleton
class RuleRepository @Inject constructor(
    private val dao: RuleDao,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /** Global rules plus [worldId]'s rules, rebuilt whenever any of them change. */
    fun observeRuleSet(worldId: Long?): Flow<RuleSet> =
        dao.observeFor(worldId)
            .distinctUntilChanged()
            .map { rows ->
                RuleSet.build(
                    worldId,
                    rows.filter { it.kind == RuleKind.Trigger }.map { it.toTrigger() },
                    rows.filter { it.kind == RuleKind.Alias }.map { it.toAlias() },
                )
            }
            .flowOn(io)

    /** For the Rules screen. A null [worldId] gives only the global rules. */
    fun observeRules(worldId: Long?): Flow<RuleLists> =
        dao.observeFor(worldId).map { rows ->
            val (global, world) = rows.partition { it.worldId == null }
            RuleLists(
                worldTriggers = world.triggers(),
                globalTriggers = global.triggers(),
                worldAliases = world.aliases(),
                globalAliases = global.aliases(),
            )
        }.distinctUntilChanged()

    suspend fun getTrigger(id: Long): Trigger? = dao.get(id)?.takeIf { it.kind == RuleKind.Trigger }?.toTrigger()

    suspend fun getAlias(id: Long): Alias? = dao.get(id)?.takeIf { it.kind == RuleKind.Alias }?.toAlias()

    /** Inserts when [Trigger.id] is 0, otherwise updates. Returns the id. */
    suspend fun save(trigger: Trigger): Long = save(trigger.id) { trigger.toEntity(it) }

    /** Inserts when [Alias.id] is 0, otherwise updates. Returns the id. */
    suspend fun save(alias: Alias): Long = save(alias.id) { alias.toEntity(it) }

    private suspend fun save(id: Long, toEntity: (createdAt: Long) -> RuleEntity): Long {
        val existing = if (id == 0L) null else dao.get(id)
        val entity = toEntity(existing?.createdAt ?: clock.millis())
        return if (existing == null) dao.insert(entity.copy(id = 0)) else id.also { dao.update(entity) }
    }

    suspend fun delete(id: Long) = dao.deleteAll(listOf(id))

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    /** `#alias`: replaces an alias with the same name in the same scope. Returns the alias's id. */
    suspend fun addAlias(scope: Scope, name: String, expansion: String): Long {
        val existing = dao.find(scope.worldId, RuleKind.Alias, name)
        val first = existing.firstOrNull()
            ?: return dao.insert(Alias(0, scope, name, expansion).toEntity(clock.millis()))
        dao.apply(
            delete = existing.drop(1).map { it.id },
            update = listOf(first.copy(body = expansion, enabled = true)),
        )
        return first.id
    }

    /** `#action`: a trigger that sends [command]. Replaces the command of a plain trigger in the scope that already sends on [pattern]. */
    suspend fun addAction(scope: Scope, pattern: String, command: String): Long {
        val existing = dao.find(scope.worldId, RuleKind.Trigger, pattern).firstOrNull { !it.isRegex && it.body != null }
            ?: return dao.insert(Trigger(0, scope, pattern, send = command).toEntity(clock.millis()))
        dao.update(existing.copy(body = command, enabled = true))
        return existing.id
    }

    /** `#gag`. A plain gag on the same pattern in the scope is reused rather than duplicated. */
    suspend fun addGag(scope: Scope, pattern: String): Long {
        val existing = dao.find(scope.worldId, RuleKind.Trigger, pattern).firstOrNull { !it.isRegex && it.gag }
            ?: return dao.insert(Trigger(0, scope, pattern, gag = true).toEntity(clock.millis()))
        if (!existing.enabled) dao.setEnabled(existing.id, true)
        return existing.id
    }

    /** `#unalias`: removes [worldId]'s alias with this name if there is one, otherwise the global one. */
    suspend fun removeAlias(worldId: Long?, name: String): Removal =
        removeFirstFound(worldId) { scopeId ->
            val ids = dao.find(scopeId, RuleKind.Alias, name).map { it.id }
            if (ids.isNotEmpty()) dao.deleteAll(ids)
            ids.isNotEmpty()
        }

    /** `#unaction`: takes the send off triggers on [pattern], world first, deleting any that then do nothing. */
    suspend fun removeAction(worldId: Long?, pattern: String): Removal =
        removeFirstFound(worldId) { strip(it, pattern, has = { body != null }, without = { copy(body = null) }) }

    /** `#ungag`: takes the gag off triggers on [pattern], world first, deleting any that then do nothing. */
    suspend fun removeGag(worldId: Long?, pattern: String): Removal =
        removeFirstFound(worldId) { strip(it, pattern, has = { gag }, without = { copy(gag = false) }) }

    private suspend fun removeFirstFound(worldId: Long?, remove: suspend (scopeId: Long?) -> Boolean): Removal {
        for (scopeId in if (worldId == null) listOf(null) else listOf(worldId, null)) {
            if (remove(scopeId)) return Removal.Removed(scopeOf(scopeId))
        }
        return Removal.NotFound
    }

    private suspend fun strip(
        worldId: Long?,
        pattern: String,
        has: RuleEntity.() -> Boolean,
        without: RuleEntity.() -> RuleEntity,
    ): Boolean {
        val matches = dao.find(worldId, RuleKind.Trigger, pattern).filter { it.has() }
        if (matches.isEmpty()) return false
        val (empty, rest) = matches.map { it.without() }.partition { !it.doesAnything() }
        dao.apply(delete = empty.map { it.id }, update = rest)
        return true
    }
}

private fun RuleEntity.doesAnything() = body != null || gag || highlight != null || vibrate || notify

private fun List<RuleEntity>.triggers() = filter { it.kind == RuleKind.Trigger }.map { it.toTrigger() }

private fun List<RuleEntity>.aliases() = filter { it.kind == RuleKind.Alias }.map { it.toAlias() }
