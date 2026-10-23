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
                triggers = all.filter { it.kind == RuleKind.Trigger }.map { it.toJsonTrigger(nameOf(it)) },
                aliases = all.filter { it.kind == RuleKind.Alias }.map { it.toJsonAlias(nameOf(it)) },
            ),
        )
    }

    /**
     * Adds the rules in [json] to worlds with the same name (the oldest if several share it). Rules for
     * other worlds are skipped; worlds are never created. An imported alias replaces one with the same
     * name in the same scope. Throws [IllegalArgumentException] if [json] isn't a rules file.
     */
    suspend fun import(json: String): ImportSummary {
        val file = format.decodeFromString(RulesFile.serializer(), json)
        require(file.version <= VERSION) { "Rules file version ${file.version} is newer than this app" }
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

        val incoming = file.triggers.map { it.world to it::toEntity } + file.aliases.map { it.world to it::toEntity }
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
            if (entity.kind == RuleKind.Alias) {
                fun sameName(other: RuleEntity) =
                    other.kind == RuleKind.Alias && other.worldId == worldId && other.pattern == entity.pattern
                existing.filter(::sameName).mapTo(delete) { it.id }
                insert.removeAll(::sameName)
            }
            insert += entity
        }
        rules.apply(delete = delete, insert = insert)
        return ImportSummary(insert.size, skipped, present, unknown.toList())
    }

    private companion object {
        const val VERSION = 1
        val format = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }
}

private fun RuleEntity.comparable() = copy(id = 0, createdAt = 0)

@Serializable
private data class RulesFile(
    val version: Int = 1,
    val triggers: List<JsonTrigger> = emptyList(),
    val aliases: List<JsonAlias> = emptyList(),
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
    fun toEntity(worldId: Long?, createdAt: Long) = RuleEntity(
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
