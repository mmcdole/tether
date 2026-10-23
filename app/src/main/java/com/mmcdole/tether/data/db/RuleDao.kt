package com.mmcdole.tether.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class RuleDao {
    /** Global rules plus [worldId]'s rules; only global ones when [worldId] is null. */
    @Query("SELECT * FROM rules WHERE worldId IS NULL OR worldId = :worldId ORDER BY createdAt, id")
    abstract fun observeFor(worldId: Long?): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules ORDER BY createdAt, id")
    abstract suspend fun getAll(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE id = :id")
    abstract suspend fun get(id: Long): RuleEntity?

    /** Rules of [kind] in one scope (null = global) whose pattern, or alias name, is [pattern]. */
    @Query("SELECT * FROM rules WHERE worldId IS :worldId AND kind = :kind AND pattern = :pattern ORDER BY createdAt, id")
    abstract suspend fun find(worldId: Long?, kind: RuleKind, pattern: String): List<RuleEntity>

    @Insert
    abstract suspend fun insert(rule: RuleEntity): Long

    @Insert
    abstract suspend fun insertAll(rules: List<RuleEntity>): List<Long>

    @Update
    abstract suspend fun update(rule: RuleEntity)

    @Query("DELETE FROM rules WHERE id IN (:ids)")
    abstract suspend fun deleteAll(ids: Collection<Long>)

    @Query("UPDATE rules SET enabled = :enabled WHERE id = :id")
    abstract suspend fun setEnabled(id: Long, enabled: Boolean)

    /** Deletes, updates, then inserts, all or nothing. Returns the new ids. */
    @Transaction
    open suspend fun apply(
        delete: Collection<Long> = emptyList(),
        update: List<RuleEntity> = emptyList(),
        insert: List<RuleEntity> = emptyList(),
    ): List<Long> {
        if (delete.isNotEmpty()) deleteAll(delete)
        update.forEach { update(it) }
        return if (insert.isEmpty()) emptyList() else insertAll(insert)
    }
}
