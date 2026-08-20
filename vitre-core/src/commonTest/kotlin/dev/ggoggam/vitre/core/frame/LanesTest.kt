package dev.ggoggam.vitre.core.frame

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LanesTest {
    @Test
    fun `lane ids are one letter per lane`() {
        assertEquals(listOf("a", "b", "c", "d"), Lanes.laneIds(4))
        assertEquals(listOf("a"), Lanes.laneIds(1))
    }

    @Test
    fun `refuses more lanes than the pool supports`() {
        assertFailsWith<IllegalArgumentException> { Lanes.laneIds(Lanes.MAX_LANES + 1) }
        assertFailsWith<IllegalArgumentException> { Lanes.laneIds(0) }
    }
}
