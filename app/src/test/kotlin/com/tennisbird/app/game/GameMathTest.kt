package com.tennisbird.app.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameMathTest {
    private val bounds = CourtBounds(
        gateWidth = 80f,
        laneHeight = 180f,
        worldHeight = 800f,
        courtFloor = 720f,
    )

    @Test
    fun ballInsideLaneDoesNotHitBarrier() {
        val gates = listOf(RallyGate(x = 200f, laneCenterY = 360f))

        assertFalse(hitsBarrier(240f, 360f, 24f, gates, bounds))
    }

    @Test
    fun ballTouchingUpperPanelHitsBarrier() {
        val gates = listOf(RallyGate(x = 200f, laneCenterY = 360f))

        assertTrue(hitsBarrier(240f, 250f, 24f, gates, bounds))
    }

    @Test
    fun clearingGateAddsOneScoreOnlyOnce() {
        val gates = listOf(RallyGate(x = 100f, laneCenterY = 360f))

        val (firstUpdate, firstScore) = markClearedGates(
            birdX = 220f,
            gates = gates,
            gateWidth = 80f,
        )
        val (_, secondScore) = markClearedGates(
            birdX = 260f,
            gates = firstUpdate,
            gateWidth = 80f,
        )

        assertEquals(1, firstScore)
        assertEquals(0, secondScore)
    }
}
