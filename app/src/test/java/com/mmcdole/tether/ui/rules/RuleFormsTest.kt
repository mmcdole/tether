package com.mmcdole.tether.ui.rules

import com.mmcdole.tether.mud.rules.Scope
import com.mmcdole.tether.mud.rules.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleFormsTest {
    private val world = Scope.World(1)

    @Test
    fun summaryUsesShortWording() {
        val red = HighlightColor.Red.rgb
        assertEquals("Highlight red · vibrate", Trigger(1, world, "x", highlight = red, vibrate = true).summary())
        assertEquals("Gag", Trigger(1, world, "x", gag = true).summary())
        assertEquals("kill %1", Trigger(1, world, "x", send = "kill %1").summary())
        assertEquals("Highlight magenta · notify", Trigger(1, world, "x", highlight = HighlightColor.Magenta.rgb, notify = true).summary())
        assertEquals("Gag · eat bread · notify", Trigger(1, world, "x", gag = true, send = "eat bread", notify = true).summary())
        assertEquals("Highlight", Trigger(1, world, "x", highlight = 0x123456).summary())
    }

    @Test
    fun badRegexShowsShortMessageAndBlocksSave() {
        val form = TriggerForm(scope = world, pattern = "^HP:(\\d+", isRegex = true, gag = true)
        val error = form.patternError!!
        assertFalse(error.startsWith("Invalid regex"))
        assertTrue(error, error.startsWith("Unclosed group"))
        assertFalse(form.canSave)

        // The same text is fine as a plain pattern.
        assertNull(form.copy(isRegex = false).patternError)
        assertTrue(form.copy(isRegex = false).canSave)
    }

    @Test
    fun androidRegexMessagesAreShortened() {
        assertEquals(
            "Incorrectly nested parentheses at 13",
            shortPatternMessage("Invalid regex: Incorrectly nested parentheses in regexp pattern at position 13"),
        )
    }

    @Test
    fun emptyPatternHasNoErrorButCannotSave() {
        val form = TriggerForm(scope = world, gag = true)
        assertNull(form.patternError)
        assertFalse(form.canSave)
    }

    @Test
    fun saveNeedsAnActionOrASend() {
        val form = TriggerForm(scope = world, pattern = "You are hungry.")
        assertFalse(form.canSave)
        assertFalse(form.copy(send = "  ").canSave)
        assertTrue(form.copy(send = "eat bread").canSave)
        assertTrue(form.copy(highlight = HighlightColor.Cyan.rgb).canSave)
        assertTrue(form.copy(vibrate = true).canSave)
        assertTrue(form.copy(notify = true).canSave)
        assertFalse(form.copy(gag = true, priority = "-").canSave)
    }

    @Test
    fun formRoundTripsTriggerAndOpensAdvancedWhenNeeded() {
        val trigger = Trigger(
            7, Scope.Global, "%1 tells you: %2", group = "Social", priority = -2,
            highlight = HighlightColor.Magenta.rgb, notify = true, replyTemplate = "tell %1", replyRequiresUnlock = false,
        )
        val form = TriggerForm.of(trigger)
        assertTrue(form.advanced)
        assertEquals(trigger, form.toTrigger())
        assertFalse(TriggerForm.of(Trigger(1, world, "x", gag = true)).advanced)
    }

    @Test
    fun replyTemplateIsDroppedWithoutNotify() {
        val form = TriggerForm(scope = world, pattern = "x", gag = true, replyTemplate = "tell %1", group = "  ")
        val trigger = form.toTrigger()
        assertNull(trigger.replyTemplate)
        assertNull(trigger.group)
    }

    @Test
    fun aliasNameMustBeFreeInItsScope() {
        val form = AliasForm(scope = world, name = "k", expansion = "kill %1", taken = mapOf(world to setOf("k")))
        assertEquals("Already used", form.nameError)
        assertFalse(form.canSave)
        assertTrue(form.copy(scope = Scope.Global).canSave)
        assertFalse(form.copy(scope = Scope.Global, expansion = " ").canSave)
    }

    @Test
    fun priorityInput() {
        assertTrue("".isPriorityInput())
        assertTrue("-".isPriorityInput())
        assertTrue("-12".isPriorityInput())
        assertFalse("1a".isPriorityInput())
        assertFalse("12345678".isPriorityInput())
    }
}
