package com.mmcdole.tether.mud.rules

class CompiledTrigger(val trigger: Trigger, val pattern: Pattern)

/** A trigger left out of a [RuleSet] because its pattern doesn't compile. */
data class InvalidTrigger(val trigger: Trigger, val message: String)

/**
 * The rules active in one session: the global rules plus one world's rules, enabled only, with
 * patterns compiled. Immutable, so it can be swapped in whole when rules change.
 */
class RuleSet private constructor(
    /** In the order they fire: priority, then world before global. */
    val triggers: List<CompiledTrigger>,
    /** By name. A world alias replaces a global one with the same name. */
    val aliases: Map<String, Alias>,
    val invalid: List<InvalidTrigger>,
) {
    companion object {
        val Empty = RuleSet(emptyList(), emptyMap(), emptyList())

        /**
         * Rules for other worlds are ignored, as are disabled ones. With a null [worldId] only global
         * rules apply.
         */
        fun build(worldId: Long?, triggers: List<Trigger>, aliases: List<Alias>): RuleSet {
            fun applies(scope: Scope) = scope == Scope.Global || (scope is Scope.World && scope.worldId == worldId)

            val compiled = ArrayList<CompiledTrigger>()
            val invalid = ArrayList<InvalidTrigger>()
            for (trigger in triggers) {
                if (!trigger.enabled || !applies(trigger.scope)) continue
                when (val result = Pattern.compile(trigger.pattern, trigger.isRegex)) {
                    is PatternResult.Valid -> compiled += CompiledTrigger(trigger, result.pattern)
                    is PatternResult.Invalid -> invalid += InvalidTrigger(trigger, result.message)
                }
            }
            // Stable sort, so equal rules keep the order they were given in.
            compiled.sortWith(compareBy({ it.trigger.priority }, { if (it.trigger.scope == Scope.Global) 1 else 0 }))

            val byName = LinkedHashMap<String, Alias>()
            val active = aliases.filter { it.enabled && applies(it.scope) }
            for (alias in active.filter { it.scope == Scope.Global }) byName.putIfAbsent(alias.name, alias)
            val worldNames = HashSet<String>()
            for (alias in active.filter { it.scope != Scope.Global }) {
                if (worldNames.add(alias.name)) byName[alias.name] = alias
            }
            return RuleSet(compiled, byName, invalid)
        }
    }
}
