package com.mmcdole.tether.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(entities = [WorldEntity::class], version = 1, exportSchema = true)
abstract class TetherDatabase : RoomDatabase() {
    abstract fun worldDao(): WorldDao

    companion object {
        const val NAME = "tether.db"

        /** Every schema change bumps the version and adds a migration here; there is no destructive fallback. */
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
