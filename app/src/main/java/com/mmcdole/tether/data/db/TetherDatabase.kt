package com.mmcdole.tether.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

@Database(entities = [WorldEntity::class, RuleEntity::class], version = 4, exportSchema = true)
abstract class TetherDatabase : RoomDatabase() {
    abstract fun worldDao(): WorldDao

    abstract fun ruleDao(): RuleDao

    companion object {
        const val NAME = "tether.db"

        /** Every schema change bumps the version and adds a migration here; there is no destructive fallback. */
        val MIGRATIONS: Array<Migration> = arrayOf(SqlMigration(1, 2, MIGRATION_1_2), SqlMigration(2, 3, MIGRATION_2_3), SqlMigration(3, 4, MIGRATION_3_4))
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

/** Adds timers, and moves triggers that do nothing but gag to the new gag kind. */
internal val MIGRATION_2_3 = listOf(
    "ALTER TABLE `rules` ADD COLUMN `intervalMillis` INTEGER NOT NULL DEFAULT 0",
    "UPDATE `rules` SET `kind` = 'Gag', `gag` = 0 WHERE `kind` = 'Trigger' AND `gag` = 1 AND `body` IS NULL " +
        "AND `highlight` IS NULL AND `vibrate` = 0 AND `notify` = 0",
)

/** Adds the trigger action that copies a line to the chat tray. */
internal val MIGRATION_3_4 = listOf("ALTER TABLE `rules` ADD COLUMN `chat` INTEGER NOT NULL DEFAULT 0")

/** A migration that is only SQL statements, kept as a list so a JVM test can check them against the exported schemas. */
internal class SqlMigration(start: Int, end: Int, val statements: List<String>) : Migration(start, end) {
    override fun migrate(db: SupportSQLiteDatabase) = statements.forEach(db::execSQL)

    override fun migrate(connection: SQLiteConnection) = statements.forEach(connection::execSQL)
}
