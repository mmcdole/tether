package com.mmcdole.tether.data.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checks each migration on a real SQLite (sqlite-jdbc, no device): a database built from the old exported
 * schema and then migrated must have the same tables, columns, foreign keys and indices as one built
 * from the new exported schema. That is what Room validates when it opens the database.
 */
class MigrationTest {
    private val schemaDir = File("schemas/${TetherDatabase::class.qualifiedName}")

    private fun schema(version: Int): JsonObject =
        Json.parseToJsonElement(File(schemaDir, "$version.json").readText()).jsonObject.getValue("database").jsonObject

    private fun latestVersion() = schemaDir.listFiles()!!.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.max()

    private fun open(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").also { it.exec("PRAGMA foreign_keys = ON") }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.create(version: Int) {
        for (entity in schema(version).getValue("entities").jsonArray.map { it.jsonObject }) {
            val table = entity.getValue("tableName").jsonPrimitive.content
            fun sql(o: JsonObject) = o.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)
            exec(sql(entity))
            entity["indices"]?.jsonArray?.forEach { exec(sql(it.jsonObject)) }
        }
    }

    private fun Connection.rows(sql: String): List<List<Any?>> = createStatement().use { s ->
        s.executeQuery(sql).use { r ->
            buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getObject(it) }) }
        }
    }

    /** Columns, foreign keys and indices of every table, in a comparable form. */
    private fun Connection.describe(): Map<String, Any> =
        rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .map { it[0] as String }
            .associateWith { table ->
                listOf(
                    rows("PRAGMA table_info(`$table`)").map { it.drop(1) }, // name, type, notnull, default, pk
                    rows("PRAGMA foreign_key_list(`$table`)").map { it.drop(2) }.sortedBy { it.toString() },
                    rows("PRAGMA index_list(`$table`)").filter { it[3] == "c" }.sortedBy { it[1] as String }.map { index ->
                        listOf(index[1], index[2], rows("PRAGMA index_info(`${index[1]}`)").map { it[2] })
                    },
                )
            }

    @Test
    fun migrationsChainToTheLatestSchema() {
        val steps = TetherDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals((1 until latestVersion()).map { it to it + 1 }, steps)
    }

    @Test
    fun migratedSchemasMatchExportedSchemas() {
        for (migration in TetherDatabase.MIGRATIONS) {
            val migrated = open().apply {
                create(migration.startVersion)
                (migration as SqlMigration).statements.forEach { exec(it) }
            }
            val fresh = open().apply { create(migration.endVersion) }
            assertEquals("${migration.startVersion} -> ${migration.endVersion}", fresh.describe(), migrated.describe())
            assertEquals(migration.endVersion, schema(migration.endVersion).getValue("version").jsonPrimitive.int)
        }
    }

    @Test
    fun version1WorldsSurviveAndRulesCascade() {
        open().apply {
            create(1)
            exec("INSERT INTO worlds (id, name, host, port, tls, createdAt) VALUES (7, 'Viking', 'h', 2001, 0, 1)")
            MIGRATION_1_2.forEach { exec(it) }

            val insertRule = "INSERT INTO rules (worldId, kind, pattern, body, isRegex, enabled, priority, gag, vibrate, notify, " +
                "replyRequiresUnlock, createdAt) VALUES (%s, 'Alias', 'k', 'kill %%1', 0, 1, 0, 0, 0, 0, 1, 1)"
            exec(insertRule.format("7"))
            exec(insertRule.format("NULL"))
            assertEquals(listOf(listOf<Any?>("Viking")), rows("SELECT name FROM worlds"))

            exec("DELETE FROM worlds WHERE id = 7")
            assertEquals(listOf(listOf<Any?>(null)), rows("SELECT worldId FROM rules"))
        }
    }
}
