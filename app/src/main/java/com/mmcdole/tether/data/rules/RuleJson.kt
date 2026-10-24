package com.mmcdole.tether.data.rules

import com.mmcdole.tether.data.db.RuleDao
import com.mmcdole.tether.data.db.RuleEntity
import com.mmcdole.tether.data.db.RuleKind
import com.mmcdole.tether.data.db.WorldDao
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class ImportSummary(
    val imported: Int,
    /** Rules for worlds that don't exist here. */
    val skipped: Int,
    /** Rules identical to one already stored. */
    val alreadyPresent: Int,
    val unknownWorlds: List<String>,
)

/** A rules file written by a newer version of the app. */
class NewerRulesFileException(version: Int) : IllegalArgumentException("Rules file version $version is newer than this app")

/** Exports and imports every rule as JSON, naming worlds instead of using their ids. Doesn't touch files. */
@Singleton
class RuleJson @Inject constructor(
    private val rules: RuleDao,
    private val worlds: WorldDao,
    private val clock: Clock,
) {
    suspend fun export(): String {
        val names = worlds.observeAll().first().associate { it.id to it.name }
        val all = rules.getAll()
        fun nameOf(rule: RuleEntity) = rule.worldId?.let { names.getValue(it) }
        return format.encodeToString(
            RulesFile.serializer(),
            RulesFile(
                version = VERSION,
                triggers = all.filter { it.kind == RuleKind.Trigger }.map { it.toJsonTrigger(nameOf(it)) },
                aliases = all.filter { it.kind == RuleKind.Alias }.map { it.toJsonAlias(nameOf(it)) },
                gags = all.filter { it.kind == RuleKind.Gag }.map { JsonGag(nameOf(it), it.pattern, it.isRegex, it.enabled, it.group) },
                timers = all.filter { it.kind == RuleKind.Timer }.map {
                    JsonTimer(nameOf(it), it.pattern, it.body.orEmpty(), it.intervalMillis, it.enabled, it.group)
                },
            ),
        )
    }

    /**
     * Adds the rules in [json] to worlds with the same name (the oldest if several share it). Rules for
     * other worlds are skipped; worlds are never created. An imported alias or timer replaces one with
     * the same name in the same scope. Throws [IllegalArgumentException] if [json] isn't a rules file.
     */
    suspend fun import(json: String): ImportSummary {
        val file = format.decodeFromString(RulesFile.serializer(), json)
        if (file.version > VERSION) throw NewerRulesFileException(file.version)
        val idByName = HashMap<String, Long>()
        worlds.observeAll().first().forEach { idByName.putIfAbsent(it.name, it.id) }

        val existing = rules.getAll()
        val seen = existing.mapTo(HashSet()) { it.comparable() }
        val now = clock.millis()
        val unknown = LinkedHashSet<String>()
        var skipped = 0
        var present = 0
        val delete = HashSet<Long>()
        val insert = ArrayList<RuleEntity>()

        val oldGags = file.version < 2
        val incoming = file.triggers.map { t -> t.world to { worldId: Long?, now: Long -> t.toEntity(worldId, now, oldGags) } } + file.aliases.map { it.world to it::toEntity } +
            file.gags.map { it.world to it::toEntity } + file.timers.map { it.world to it::toEntity }
        for ((world, toEntity) in incoming) {
            val worldId = world?.let { idByName[it] }
            if (world != null && worldId == null) {
                unknown += world
                skipped++
                continue
            }
            val entity = toEntity(worldId, now)
            if (!seen.add(entity.comparable())) {
                present++
                continue
            }
            if (entity.kind == RuleKind.Alias || entity.kind == RuleKind.Timer) {
                fun sameName(other: RuleEntity) =
                    other.kind == entity.kind && other.worldId == worldId && other.pattern == entity.pattern
                existing.filter(::sameName).mapTo(delete) { it.id }
                insert.removeAll(::sameName)
            }
            insert += entity
        }
        rules.apply(delete = delete, insert = insert)
        return ImportSummary(insert.size, skipped, present, unknown.toList())
    }

    private companion object {
        /** 2 added gags and timers. */
        const val VERSION = 2
        val format = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }
}

private fun RuleEntity.comparable() = copy(id = 0, createdAt = 0)

@Serializable
private data class RulesFile(
    // Files without a version are version 1. A newer app writes its version so older ones refuse the file.
    val version: Int = 1,
    val triggers: List<JsonTrigger> = emptyList(),
    val aliases: List<JsonAlias> = emptyList(),
    val gags: List<JsonGag> = emptyList(),
    val timers: List<JsonTimer> = emptyList(),
)

/** Defaults match [RuleEntity] and are left out of the file. [world] is always written; null means all worlds. */
@Serializable
private data class JsonTrigger(
    val world: String?,
    val pattern: String,
    val regex: Boolean = false,
    val enabled: Boolean = true,
    val group: String? = null,
    val priority: Int = 0,
    val highlight: Int? = null,
    val gag: Boolean = false,
    val send: String? = null,
    val vibrate: Boolean = false,
    val notify: Boolean = false,
    val replyTemplate: String? = null,
    val replyRequiresUnlock: Boolean = true,
) {
    /** Version 1 files kept gags as triggers that only gag; with [oldGags] those come in as gags. */
    fun toEntity(worldId: Long?, createdAt: Long, oldGags: Boolean): RuleEntity {
        val onlyGags = oldGags && gag && send == null && highlight == null && !vibrate && !notify
        return if (onlyGags) JsonGag(world, pattern, regex, enabled, group).toEntity(worldId, createdAt) else trigger(worldId, createdAt)
    }

    private fun trigger(worldId: Long?, createdAt: Long) = RuleEntity(
        worldId = worldId,
        kind = RuleKind.Trigger,
        pattern = pattern,
        body = send,
        isRegex = regex,
        enabled = enabled,
        group = group,
        priority = priority,
        highlight = highlight,
        gag = gag,
        vibrate = vibrate,
        notify = notify,
        replyTemplate = replyTemplate,
        replyRequiresUnlock = replyRequiresUnlock,
        createdAt = createdAt,
    )
}

@Serializable
private data class JsonAlias(
    val world: String?,
    val name: String,
    val expansion: String,
    val enabled: Boolean = true,
    val group: String? = null,
) {
    fun toEntity(worldId: Long?, createdAt: Long) = RuleEntity(
        worldId = worldId,
        kind = RuleKind.Alias,
        pattern = name,
        body = expansion,
        enabled = enabled,
        group = group,
        createdAt = createdAt,
    )
}

@Serializable
private data class JsonGag(
    val world: String?,
    val pattern: String,
    val regex: Boolean = false,
    val enabled: Boolean = true,
    val group: String? = null,
) {
    fun toEntity(worldId: Long?, createdAt: Long) = RuleEntity(
        worldId = worldId,
        kind = RuleKind.Gag,
        pattern = pattern,
        body = null,
        isRegex = regex,
        enabled = enabled,
        group = group,
        createdAt = createdAt,
    )
}

@Serializable
private data class JsonTimer(
    val world: String?,
    val name: String,
    val command: String,
    val intervalMillis: Long,
    val enabled: Boolean = true,
    val group: String? = null,
) {
    fun toEntity(worldId: Long?, createdAt: Long) = RuleEntity(
        worldId = worldId,
        kind = RuleKind.Timer,
        pattern = name,
        body = command,
        enabled = enabled,
        group = group,
        intervalMillis = intervalMillis,
        createdAt = createdAt,
    )
}

private fun RuleEntity.toJsonTrigger(world: String?) = JsonTrigger(
    world = world,
    pattern = pattern,
    regex = isRegex,
    enabled = enabled,
    group = group,
    priority = priority,
    highlight = highlight,
    gag = gag,
    send = body,
    vibrate = vibrate,
    notify = notify,
    replyTemplate = replyTemplate,
    replyRequiresUnlock = replyRequiresUnlock,
)

private fun RuleEntity.toJsonAlias(world: String?) = JsonAlias(world, pattern, body.orEmpty(), enabled, group)
