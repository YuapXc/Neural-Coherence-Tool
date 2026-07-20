package io.github.neuralcoherence.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleTaskCoordinatorTest {
    @Test
    fun scanBlocksInteractionUntilItFinishes() {
        val coordinator = ModuleTaskCoordinator()

        assertTrue(coordinator.tryStart(ModuleTask.SCANNING))
        assertFalse(coordinator.tryStart(ModuleTask.INTERACTING))
        assertEquals(ModuleTask.SCANNING, coordinator.state)
        assertTrue(coordinator.finish(ModuleTask.SCANNING))
        assertTrue(coordinator.tryStart(ModuleTask.INTERACTING))
    }

    @Test
    fun wrongOwnerCannotReleaseTask() {
        val coordinator = ModuleTaskCoordinator()

        assertTrue(coordinator.tryStart(ModuleTask.INTERACTING))
        assertFalse(coordinator.finish(ModuleTask.SCANNING))
        assertEquals(ModuleTask.INTERACTING, coordinator.state)
    }
}
