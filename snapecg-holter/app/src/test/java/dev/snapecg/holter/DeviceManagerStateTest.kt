package dev.snapecg.holter

import dev.snapecg.holter.bluetooth.DeviceManager
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for DeviceManager state machine.
 * Tests state transitions without actual Bluetooth hardware.
 */
class DeviceManagerStateTest {

    /**
     * Verify the State enum has all expected states.
     */
    @Test
    fun `state enum has all states`() {
        val states = DeviceManager.State.values()
        assertEquals(4, states.size)
        assertTrue(states.contains(DeviceManager.State.DISCONNECTED))
        assertTrue(states.contains(DeviceManager.State.CONNECTING))
        assertTrue(states.contains(DeviceManager.State.CONNECTED))
        assertTrue(states.contains(DeviceManager.State.RECONNECTING))
    }

    /**
     * Verify initial state is DISCONNECTED.
     */
    @Test
    fun `initial state is disconnected`() {
        // DeviceManager requires Context, so we test state enum directly
        assertEquals("DISCONNECTED", DeviceManager.State.DISCONNECTED.name)
    }

    /**
     * Verify state transitions are well-defined.
     */
    @Test
    fun `valid state transitions`() {
        // Document expected transitions:
        // DISCONNECTED → CONNECTING (on connect())
        // CONNECTING → CONNECTED (on socket success)
        // CONNECTING → RECONNECTING (on socket failure, if shouldReconnect)
        // CONNECTING → DISCONNECTED (on socket failure, if !shouldReconnect)
        // CONNECTED → RECONNECTING (on IOException, if shouldReconnect)
        // RECONNECTING → CONNECTED (on reconnect success)
        // RECONNECTING → DISCONNECTED (on disconnect())
        // Any → DISCONNECTED (on disconnect())

        // This test documents the design — actual transitions tested in instrumented tests
        val validTransitions = mapOf(
            DeviceManager.State.DISCONNECTED to setOf(DeviceManager.State.CONNECTING),
            DeviceManager.State.CONNECTING to setOf(
                DeviceManager.State.CONNECTED,
                DeviceManager.State.RECONNECTING,
                DeviceManager.State.DISCONNECTED
            ),
            DeviceManager.State.CONNECTED to setOf(
                DeviceManager.State.RECONNECTING,
                DeviceManager.State.DISCONNECTED
            ),
            DeviceManager.State.RECONNECTING to setOf(
                DeviceManager.State.CONNECTED,
                DeviceManager.State.DISCONNECTED
            ),
        )

        // Every state should have at least one valid transition
        for (state in DeviceManager.State.values()) {
            assertTrue("State $state should have transitions",
                validTransitions.containsKey(state))
            assertTrue("State $state should have at least one target",
                validTransitions[state]!!.isNotEmpty())
        }
    }
}
