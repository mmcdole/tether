package com.mmcdole.tether.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WorldDao {
    @Query("SELECT * FROM worlds ORDER BY createdAt, id")
    fun observeAll(): Flow<List<WorldEntity>>

    @Query("SELECT * FROM worlds WHERE id = :id")
    suspend fun get(id: Long): WorldEntity?

    @Insert
    suspend fun insert(world: WorldEntity): Long

    @Update
    suspend fun update(world: WorldEntity)

    @Query("DELETE FROM worlds WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE worlds SET lastConnectedAt = :at WHERE id = :id")
    suspend fun setLastConnected(id: Long, at: Long)
}
