package com.mmcdole.tether.data.rules

import com.mmcdole.tether.data.db.RuleEntity
import com.mmcdole.tether.data.db.RuleKind
import com.mmcdole.tether.mud.rules.Alias
import com.mmcdole.tether.mud.rules.Gag
import com.mmcdole.tether.mud.rules.Scope
import com.mmcdole.tether.mud.rules.Timer
import com.mmcdole.tether.mud.rules.Trigger

internal val Scope.worldId: Long? get() = (this as? Scope.World)?.worldId

internal fun scopeOf(worldId: Long?): Scope = if (worldId == null) Scope.Global else Scope.World(worldId)

internal fun RuleEntity.toTrigger() = Trigger(
    id = id,
    scope = scopeOf(worldId),
    pattern = pattern,
    isRegex = isRegex,
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
    chat = chat,
)

internal fun RuleEntity.toAlias() = Alias(
    id = id,
    scope = scopeOf(worldId),
    name = pattern,
    expansion = body.orEmpty(),
    enabled = enabled,
    group = group,
)

internal fun RuleEntity.toGag() = Gag(
    id = id,
    scope = scopeOf(worldId),
    pattern = pattern,
    isRegex = isRegex,
    enabled = enabled,
    group = group,
)

internal fun RuleEntity.toTimer() = Timer(
    id = id,
    scope = scopeOf(worldId),
    name = pattern,
    command = body.orEmpty(),
    intervalMillis = intervalMillis,
    enabled = enabled,
    group = group,
)

internal fun Trigger.toEntity(createdAt: Long) = RuleEntity(
    id = id,
    worldId = scope.worldId,
    kind = RuleKind.Trigger,
    pattern = pattern,
    body = send,
    isRegex = isRegex,
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
    chat = chat,
)

internal fun Alias.toEntity(createdAt: Long) = RuleEntity(
    id = id,
    worldId = scope.worldId,
    kind = RuleKind.Alias,
    pattern = name,
    body = expansion,
    enabled = enabled,
    group = group,
    createdAt = createdAt,
)

internal fun Gag.toEntity(createdAt: Long) = RuleEntity(
    id = id,
    worldId = scope.worldId,
    kind = RuleKind.Gag,
    pattern = pattern,
    body = null,
    isRegex = isRegex,
    enabled = enabled,
    group = group,
    createdAt = createdAt,
)

internal fun Timer.toEntity(createdAt: Long) = RuleEntity(
    id = id,
    worldId = scope.worldId,
    kind = RuleKind.Timer,
    pattern = name,
    body = command,
    enabled = enabled,
    group = group,
    intervalMillis = intervalMillis,
    createdAt = createdAt,
)
