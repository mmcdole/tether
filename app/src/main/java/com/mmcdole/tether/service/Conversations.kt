package com.mmcdole.tether.service

import com.mmcdole.tether.data.world.World
import com.mmcdole.tether.mud.rules.NotifyRequest

/** One Messages notification: a world and a sender, like VikingMUD and "Hagbard". */
data class ConversationKey(val worldId: Long, val sender: String) {
    /** The notification tag. With a fixed id it gives each conversation its own notification. */
    val tag: String get() = "${worldPrefix(worldId)}$sender"

    companion object {
        private const val PREFIX = "message:"

        fun worldPrefix(worldId: Long) = "$PREFIX$worldId:"
    }
}

data class ConversationMessage(val text: String, val time: Long, val fromYou: Boolean = false)

/** Where a reply goes. Carried in the Reply intent, so it still works after the process restarts. */
data class ReplyTarget(
    val worldId: Long,
    val worldName: String,
    val sender: String,
    val prefix: String,
    val requiresUnlock: Boolean,
) {
    val key: ConversationKey get() = ConversationKey(worldId, sender)
}

data class Conversation(
    val key: ConversationKey,
    val worldName: String,
    val messages: List<ConversationMessage>,
    /** The reply template with captures filled in, like `tell Hagbard`; null for no Reply. */
    val replyPrefix: String? = null,
    val replyRequiresUnlock: Boolean = true,
    /** Shown in the header instead of the world name, like "Not connected". */
    val status: String? = null,
) {
    /** False when the trigger had no sender capture and the notification is titled with the world. */
    val hasSender: Boolean get() = key.sender != worldName

    /** "Hagbard tells you", or the world name. */
    val title: String get() = if (hasSender) "${key.sender} tells you" else worldName

    val replyTarget: ReplyTarget?
        get() = replyPrefix?.let { ReplyTarget(key.worldId, worldName, key.sender, it, replyRequiresUnlock) }
}

/**
 * The recent messages behind each Messages notification, newest last. A notification is
 * rebuilt from its whole conversation every time, so new messages from the same sender add
 * to it instead of stacking. Kept in memory only.
 */
class Conversations(private val capacity: Int = DEFAULT_CAPACITY) {
    private val conversations = HashMap<ConversationKey, Conversation>()

    /** Adds a trigger's message. The latest trigger decides the reply template. */
    @Synchronized
    fun received(world: World, request: NotifyRequest, time: Long): Conversation {
        val key = ConversationKey(world.id, request.title)
        val old = conversations[key]
        val conversation = Conversation(
            key = key,
            worldName = world.name,
            messages = append(old, ConversationMessage(request.message, time)),
            replyPrefix = request.replyPrefix,
            replyRequiresUnlock = request.replyRequiresUnlock,
        )
        conversations[key] = conversation
        return conversation
    }

    /** Adds your reply, starting the conversation over if it was lost with the process. */
    @Synchronized
    fun replied(target: ReplyTarget, text: String, time: Long): Conversation {
        val old = conversations[target.key]
        val conversation = (old ?: fromTarget(target)).copy(
            messages = append(old, ConversationMessage(text, time, fromYou = true)),
            status = null,
        )
        conversations[target.key] = conversation
        return conversation
    }

    /** Sets the [status], such as "Not connected", leaving the messages as they are. */
    @Synchronized
    fun status(target: ReplyTarget, status: String?): Conversation {
        val conversation = (conversations[target.key] ?: fromTarget(target)).copy(status = status)
        conversations[target.key] = conversation
        return conversation
    }

    @Synchronized
    fun remove(key: ConversationKey) {
        conversations.remove(key)
    }

    @Synchronized
    fun removeWorld(worldId: Long) {
        conversations.keys.removeAll { it.worldId == worldId }
    }

    private fun append(old: Conversation?, message: ConversationMessage) =
        (old?.messages.orEmpty() + message).takeLast(capacity)

    private fun fromTarget(target: ReplyTarget) = Conversation(
        key = target.key,
        worldName = target.worldName,
        messages = emptyList(),
        replyPrefix = target.prefix,
        replyRequiresUnlock = target.requiresUnlock,
    )

    companion object {
        const val DEFAULT_CAPACITY = 10
    }
}
