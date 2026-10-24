package com.mmcdole.tether.data.rules

import com.mmcdole.tether.data.db.RuleDao
import com.mmcdole.tether.data.db.RuleEntity
import com.mmcdole.tether.data.db.RuleKind
import com.mmcdole.tether.di.IoDispatcher
import com.mmcdole.tether.mud.rules.Alias
import com.mmcdole.tether.mud.rules.Gag
import com.mmcdole.tether.mud.rules.RuleSet
import com.mmcdole.tether.mud.rules.Scope
import com.mmcdole.tether.mud.rules.Timer
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
    val worldGags: List<Gag> = emptyList(),
    val globalGags: List<Gag> = emptyList(),
    val worldTimers: List<Timer> = emptyList(),
    val globalTimers: List<Timer> = emptyList(),
) {
    val triggerCount get() = worldTriggers.size + globalTriggers.size
    val aliasCount get() = worldAliases.size + globalAliases.size
}

/** What a `#unalias`, `#unaction`, `#ungag` or `#ticker name on|off` did. */
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
                RuleSet.build(worldId, rows.triggers(), rows.aliases(), rows.gags(), rows.timers())
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
                worldGags = world.gags(),
                globalGags = global.gags(),
                worldTimers = world.timers(),
                globalTimers = global.timers(),
            )
        }.distinctUntilChanged()

    suspend fun getTrigger(id: Long): Trigger? = dao.get(id)?.takeIf { it.kind == RuleKind.Trigger }?.toTrigger()

    suspend fun getAlias(id: Long): Alias? = dao.get(id)?.takeIf { it.kind == RuleKind.Alias }?.toAlias()

    suspend fun getGag(id: Long): Gag? = dao.get(id)?.takeIf { it.kind == RuleKind.Gag }?.toGag()

    suspend fun getTimer(id: Long): Timer? = dao.get(id)?.takeIf { it.kind == RuleKind.Timer }?.toTimer()

    /** Inserts when [Trigger.id] is 0, otherwise updates. Returns the id. */
    suspend fun save(trigger: Trigger): Long = save(trigger.id) { trigger.toEntity(it) }

    /** Inserts when [Alias.id] is 0, otherwise updates. Returns the id. */
    suspend fun save(alias: Alias): Long = save(alias.id) { alias.toEntity(it) }

    /** Inserts when [Gag.id] is 0, otherwise updates. Returns the id. */
    suspend fun save(gag: Gag): Long = save(gag.id) { gag.toEntity(it) }

    /** Inserts when [Timer.id] is 0, otherwise updates. Returns the id. */
    suspend fun save(timer: Timer): Long = save(timer.id) { timer.toEntity(it) }

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
        val existing = dao.find(scope.worldId, RuleKind.Gag, pattern).firstOrNull { !it.isRegex }
            ?: return dao.insert(Gag(0, scope, pattern).toEntity(clock.millis()))
        if (!existing.enabled) dao.setEnabled(existing.id, true)
        return existing.id
    }

    /** `#ticker`: replaces a timer with the same name in the same scope, and turns it on. Returns the timer's id. */
    suspend fun addTimer(scope: Scope, name: String, command: String, intervalMillis: Long): Long {
        val existing = dao.find(scope.worldId, RuleKind.Timer, name)
        val first = existing.firstOrNull()
            ?: return dao.insert(Timer(0, scope, name, command, intervalMillis).toEntity(clock.millis()))
        dao.apply(
            delete = existing.drop(1).map { it.id },
            update = listOf(first.copy(body = command, intervalMillis = intervalMillis, enabled = true)),
        )
        return first.id
    }

    /** `#ticker name on|off`: turns [worldId]'s timer with this name on or off if there is one, otherwise the global one. */
    suspend fun setTimerEnabled(worldId: Long?, name: String, enabled: Boolean): Removal =
        removeFirstFound(worldId) { scopeId ->
            val timers = dao.find(scopeId, RuleKind.Timer, name)
            timers.forEach { dao.setEnabled(it.id, enabled) }
            timers.isNotEmpty()
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

    /**
     * `#ungag`: removes gags on [pattern], world first. Where there are none, takes the gag off triggers
     * on [pattern] instead, deleting any that then do nothing.
     */
    suspend fun removeGag(worldId: Long?, pattern: String): Removal =
        removeFirstFound(worldId) { scopeId ->
            val ids = dao.find(scopeId, RuleKind.Gag, pattern).map { it.id }
            if (ids.isNotEmpty()) dao.deleteAll(ids)
            ids.isNotEmpty() || strip(scopeId, pattern, has = { gag }, without = { copy(gag = false) })
        }

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

private fun List<RuleEntity>.gags() = filter { it.kind == RuleKind.Gag }.map { it.toGag() }

private fun List<RuleEntity>.timers() = filter { it.kind == RuleKind.Timer }.map { it.toTimer() }
