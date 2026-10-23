package com.mmcdole.tether.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowSizeTest {
    @Test
    fun countsWholeCellsOnly() {
        assertEquals(WindowSize(columns = 38, rows = 20), windowSize(width = 1000f, height = 1000f, charWidth = 26f, lineHeight = 49f))
    }

    @Test
    fun exactFitCountsTheLastCell() {
        assertEquals(WindowSize(columns = 80, rows = 24), windowSize(width = 800f, height = 480f, charWidth = 10f, lineHeight = 20f))
    }

    @Test
    fun fractionalCharWidthFloors() {
        // 1036 / 15.6 = 66.4
        assertEquals(66, windowSize(width = 1036f, height = 100f, charWidth = 15.6f, lineHeight = 10f).columns)
    }

    @Test
    fun neverBelowOneByOne() {
        assertEquals(WindowSize(1, 1), windowSize(width = 0f, height = -4f, charWidth = 10f, lineHeight = 20f))
    }
}
