package com.waytoconsistency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunDistanceCalculatorTest {
    private fun fix(latitude: Double, elapsedMs: Long, accuracy: Double = 4.0) =
        RunDistanceCalculator.Fix(latitude, 0.0, accuracy, elapsedMs, elapsedMs)

    @Test fun firstFixCreatesAnchorWithoutDistance() {
        val movement = RunDistanceCalculator.movement(null, fix(0.0, 1_000))
        assertTrue(movement.accepted)
        assertEquals(0.0, movement.distanceMeters, 0.0)
    }

    @Test fun smallFixIsRejectedSoMovementCanAccumulateFromOriginalAnchor() {
        val anchor = fix(0.0, 0)
        assertFalse(RunDistanceCalculator.movement(anchor, fix(0.000009, 1_000)).accepted)
        val accumulated = RunDistanceCalculator.movement(anchor, fix(0.000027, 2_000))
        assertTrue(accumulated.accepted)
        assertTrue(accumulated.distanceMeters in 2.8..3.2)
    }

    @Test fun inaccurateAndImpossibleFixesAreRejected() {
        val anchor = fix(0.0, 0)
        assertFalse(RunDistanceCalculator.movement(anchor, fix(0.0001, 10_000, 50.0)).accepted)
        assertEquals("impossible_speed", RunDistanceCalculator.movement(anchor, fix(0.001, 1_000)).reason)
    }

    @Test fun everyCrossedKilometerGetsAnInterpolatedSplit() {
        val splits = RunDistanceCalculator.crossedSplits(900.0, 3_100.0, 90_000, 310_000)
        assertEquals(listOf(1, 2, 3), splits.map { it.kilometer })
        assertEquals(listOf(100_000L, 200_000L, 300_000L), splits.map { it.elapsedMs })
    }
}
