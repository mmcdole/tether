package com.mmcdole.tether.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class RuleKind { Trigger, Alias, Gag, Timer }

/** A trigger, alias, gag or timer. Columns that don't apply to the [kind] keep their defaults. */
@Entity(
    tableName = "rules",
    foreignKeys = [
        ForeignKey(entity = WorldEntity::class, parentColumns = ["id"], childColumns = ["worldId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("worldId")],
)
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Null = all worlds. */
    val worldId: Long?,
    val kind: RuleKind,
    /** A trigger's or gag's pattern, or an alias's or timer's name. */
    val pattern: String,
    /** An alias's expansion, or the commands a trigger or timer sends. */
    val body: String?,
    val isRegex: Boolean = false,
    val enabled: Boolean = true,
    @ColumnInfo(name = "groupName") val group: String? = null,
    val priority: Int = 0,
    val highlight: Int? = null,
    val gag: Boolean = false,
    val vibrate: Boolean = false,
    val notify: Boolean = false,
    val replyTemplate: String? = null,
    val replyRequiresUnlock: Boolean = true,
    val createdAt: Long,
    /** How often a timer fires. Last, where the migration that added it put it. */
    @ColumnInfo(defaultValue = "0") val intervalMillis: Long = 0,
)
