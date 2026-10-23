package com.mmcdole.tether.service

import com.mmcdole.tether.data.world.World
import com.mmcdole.tether.mud.rules.NotifyRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationsTest {
    private val viking = World(id = 1, name = "VikingMUD", host = "viking", port = 2001)
    private val other = World(id = 2, name = "Other", host = "other", port = 4000)
    private val conversations = Conversations()

    private fun tell(sender: String, message: String, prefix: String? = "tell $sender", unlock: Boolean = true) =
        NotifyRequest(triggerId = 1, title = sender, message = message, replyPrefix = prefix, replyRequiresUnlock = unlock)

    @Test
    fun messagesFromOneSenderShareAConversation() {
        conversations.received(viking, tell("Hagbard", "wanna group?"), 1)
        val second = conversations.received(viking, tell("Hagbard", "troll is up"), 2)
        assertEquals(ConversationKey(1, "Hagbard"), second.key)
        assertEquals(listOf("wanna group?", "troll is up"), second.messages.map { it.text })
        assertEquals("Hagbard tells you", second.title)
        assertTrue(second.hasSender)
    }

    @Test
    fun keyIsWorldAndSender() {
        val hagbard = conversations.received(viking, tell("Hagbard", "hi"), 1)
        val grog = conversations.received(viking, tell("Grog", "hi"), 2)
        val elsewhere = conversations.received(other, tell("Hagbard", "hi"), 3)
        assertEquals(setOf(hagbard.key.tag, grog.key.tag, elsewhere.key.tag).size, 3)
        assertEquals(listOf("hi"), grog.messages.map { it.text })
        assertEquals(listOf("hi"), elsewhere.messages.map { it.text })
        // The tag is the notification's identity, so it must stay the same for the same sender.
        assertEquals(hagbard.key.tag, conversations.received(viking, tell("Hagbard", "again"), 4).key.tag)
        assertTrue(hagbard.key.tag.startsWith(ConversationKey.worldPrefix(1)))
        assertFalse(ConversationKey(11, "Hagbard").tag.startsWith(ConversationKey.worldPrefix(1)))
    }

    @Test
    fun noSenderIsTitledWithTheWorld() {
        val shout = conversations.received(viking, tell("VikingMUD", "Grog shouts hello", prefix = null), 1)
        assertFalse(shout.hasSender)
        assertEquals("VikingMUD", shout.title)
        assertNull(shout.replyTarget)
    }

    @Test
    fun historyKeepsTheLastTen() {
        var last: Conversation? = null
        for (i in 1..12) last = conversations.received(viking, tell("Hagbard", "m$i"), i.toLong())
        assertEquals((3..12).map { "m$it" }, last!!.messages.map { it.text })
    }

    @Test
    fun replyIsAddedAsYouAndClearsStatus() {
        val target = conversations.received(viking, tell("Hagbard", "wanna group?", unlock = false), 1).replyTarget!!
        assertEquals(ReplyTarget(1, "VikingMUD", "Hagbard", "tell Hagbard", requiresUnlock = false), target)
        conversations.status(target, "Not connected")
        val replied = conversations.replied(target, "sure, omw", 2)
        assertEquals(listOf(false, true), replied.messages.map { it.fromYou })
        assertEquals("sure, omw", replied.messages.last().text)
        assertNull(replied.status)
    }

    @Test
    fun replyAfterTheHistoryWasLostStartsFresh() {
        val target = ReplyTarget(1, "VikingMUD", "Hagbard", "tell Hagbard", true)
        val replied = conversations.replied(target, "sure", 1)
        assertEquals("Hagbard tells you", replied.title)
        assertEquals("tell Hagbard", replied.replyPrefix)
        assertEquals(listOf("sure"), replied.messages.map { it.text })
    }

    @Test
    fun newMessageClearsStatusAndTakesTheLatestTemplate() {
        val target = conversations.received(viking, tell("Hagbard", "hi"), 1).replyTarget!!
        assertEquals("Not connected", conversations.status(target, "Not connected").status)
        val next = conversations.received(viking, tell("Hagbard", "hello?", prefix = "reply"), 2)
        assertNull(next.status)
        assertEquals("reply", next.replyPrefix)
    }

    @Test
    fun clearingAWorldForgetsOnlyItsConversations() {
        conversations.received(viking, tell("Hagbard", "old"), 1)
        conversations.received(other, tell("Hagbard", "kept"), 2)
        conversations.removeWorld(1)
        assertEquals(listOf("new"), conversations.received(viking, tell("Hagbard", "new"), 3).messages.map { it.text })
        assertEquals(listOf("kept", "more"), conversations.received(other, tell("Hagbard", "more"), 4).messages.map { it.text })
    }

    @Test
    fun dismissedConversationStartsOver() {
        val key = conversations.received(viking, tell("Hagbard", "old"), 1).key
        conversations.remove(key)
        assertEquals(listOf("new"), conversations.received(viking, tell("Hagbard", "new"), 2).messages.map { it.text })
    }
}
