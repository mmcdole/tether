package com.mmcdole.tether.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

@Database(entities = [WorldEntity::class, RuleEntity::class], version = 2, exportSchema = true)
abstract class TetherDatabase : RoomDatabase() {
    abstract fun worldDao(): WorldDao

    abstract fun ruleDao(): RuleDao

    companion object {
        const val NAME = "tether.db"

        /** Every schema change bumps the version and adds a migration here; there is no destructive fallback. */
        val MIGRATIONS: Array<Migration> = arrayOf(SqlMigration(1, 2, MIGRATION_1_2))
    }
}

/** Adds the rules table. */
internal val MIGRATION_1_2 = listOf(
    "CREATE TABLE IF NOT EXISTS `rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `worldId` INTEGER, " +
        "`kind` TEXT NOT NULL, `pattern` TEXT NOT NULL, `body` TEXT, `isRegex` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, " +
        "`groupName` TEXT, `priority` INTEGER NOT NULL, `highlight` INTEGER, `gag` INTEGER NOT NULL, `vibrate` INTEGER NOT NULL, " +
        "`notify` INTEGER NOT NULL, `replyTemplate` TEXT, `replyRequiresUnlock` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
        "FOREIGN KEY(`worldId`) REFERENCES `worlds`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_rules_worldId` ON `rules` (`worldId`)",
)

/** A migration that is only SQL statements, kept as a list so a JVM test can check them against the exported schemas. */
internal class SqlMigration(start: Int, end: Int, val statements: List<String>) : Migration(start, end) {
    override fun migrate(db: SupportSQLiteDatabase) = statements.forEach(db::execSQL)

    override fun migrate(connection: SQLiteConnection) = statements.forEach(connection::execSQL)
}
