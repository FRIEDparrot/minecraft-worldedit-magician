package com.magician.worldedit.client.screen.reusable

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextDropdownStateTest {
    @Test
    fun `selecting a visible text row updates selected value and closes menu`() {
        val state = TextDropdownState(listOf("alpha", "beta", "gamma"), "alpha", maxVisibleOptions = 2)

        state.open()
        assertTrue(state.isOpen)
        assertEquals(1, state.optionIndexAt(localY = 18, rowHeight = 18))

        state.selectVisibleRow(row = 1)

        assertEquals("beta", state.selected)
        assertFalse(state.isOpen)
    }

    @Test
    fun `updating options replaces visible menu options and selected fallback`() {
        val state = TextDropdownState(listOf("old-a", "old-b"), "old-b", maxVisibleOptions = 5)

        state.open()
        state.updateOptions(listOf("new-a", "new-b", "new-c"))

        assertEquals(listOf("new-a", "new-b", "new-c"), state.visibleOptions())
        assertEquals("new-a", state.selected)
        assertTrue(state.isOpen)
    }

    @Test
    fun `scrolling reveals later options without exceeding list bounds`() {
        val state = TextDropdownState((1..12).map { "model-$it" }, "model-1", maxVisibleOptions = 5)

        state.open()
        state.scrollBy(99)

        assertEquals(7, state.firstVisibleIndex)
        assertEquals(listOf("model-8", "model-9", "model-10", "model-11", "model-12"), state.visibleOptions())

        state.scrollBy(-99)
        assertEquals(0, state.firstVisibleIndex)
    }
}
