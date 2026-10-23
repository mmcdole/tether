package com.mmcdole.tether.data.rules

import com.mmcdole.tether.data.db.RuleDao
import com.mmcdole.tether.data.db.RuleEntity
import com.mmcdole.tether.data.db.RuleKind
import com.mmcdole.tether.mud.rules.Scope
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** What to do where an alias with the same name already exists. */
enum class OnConflict { Replace, Skip }

data class TransferResult(
    val copiedTo: List<Scope>,
    /** Destinations left alone because of a name conflict and [OnConflict.Skip]. */
    val skipped: List<Scope>,
    /** False for a copy, and for a move where nothing was copied, so the original is kept. */
    val originalDeleted: Boolean,
)

/** Copies or moves one rule to other scopes. Copies are independent rows. */
@Singleton
class RuleTransfer @Inject constructor(
    private val dao: RuleDao,
    private val clock: Clock,
) {
    /** Destinations that already have an alias with this alias's name. Always empty for a trigger. */
    suspend fun conflicts(ruleId: Long, destinations: Collection<Scope>): List<Scope> {
        val rule = dao.get(ruleId) ?: return emptyList()
        return targets(rule, destinations).filter { existing(rule, it).isNotEmpty() }
    }

    /** Returns null if the rule no longer exists. */
    suspend fun copy(ruleId: Long, destinations: Collection<Scope>, onConflict: OnConflict = OnConflict.Replace): TransferResult? =
        transfer(ruleId, destinations, onConflict, move = false)

    /** Copy, then delete the original, in one transaction. Returns null if the rule no longer exists. */
    suspend fun move(ruleId: Long, destinations: Collection<Scope>, onConflict: OnConflict = OnConflict.Replace): TransferResult? =
        transfer(ruleId, destinations, onConflict, move = true)

    private suspend fun transfer(ruleId: Long, destinations: Collection<Scope>, onConflict: OnConflict, move: Boolean): TransferResult? {
        val rule = dao.get(ruleId) ?: return null
        val now = clock.millis()
        val copied = ArrayList<Scope>()
        val skipped = ArrayList<Scope>()
        val delete = ArrayList<Long>()
        val insert = ArrayList<RuleEntity>()
        for (scope in targets(rule, destinations)) {
            val clashes = existing(rule, scope)
            if (clashes.isNotEmpty() && onConflict == OnConflict.Skip) {
                skipped += scope
                continue
            }
            delete += clashes.map { it.id }
            insert += rule.copy(id = 0, worldId = scope.worldId, createdAt = now)
            copied += scope
        }
        val deleteOriginal = move && copied.isNotEmpty()
        if (deleteOriginal) delete += rule.id
        dao.apply(delete = delete, insert = insert)
        return TransferResult(copied, skipped, deleteOriginal)
    }

    private fun targets(rule: RuleEntity, destinations: Collection<Scope>) =
        destinations.distinct().filter { it.worldId != rule.worldId }

    private suspend fun existing(rule: RuleEntity, scope: Scope): List<RuleEntity> =
        if (rule.kind == RuleKind.Alias) dao.find(scope.worldId, RuleKind.Alias, rule.pattern) else emptyList()
}
