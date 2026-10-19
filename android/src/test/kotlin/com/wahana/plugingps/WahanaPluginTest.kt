package com.wahana.plugingps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WahanaPluginTest {

    private val implementation = WahanaPlugin()

    @Test
    fun calculateStatus_whenAllValid_usableIsTrue() {
        val status = implementation.calculateStatus(enabled = true, granted = true, isMock = false)
        assertTrue(status.getBoolean("enabled"))
        assertTrue(status.getBoolean("granted"))
        assertFalse(status.getBoolean("isMock"))
        assertTrue(status.getBoolean("usable"))
    }

    @Test
    fun calculateStatus_whenGpsDisabled_usableIsFalse() {
        val status = implementation.calculateStatus(enabled = false, granted = true, isMock = false)
        assertFalse(status.getBoolean("enabled"))
        assertTrue(status.getBoolean("granted"))
        assertFalse(status.getBoolean("isMock"))
        assertFalse(status.getBoolean("usable"))
    }

    @Test
    fun calculateStatus_whenPermissionDenied_usableIsFalse() {
        val status = implementation.calculateStatus(enabled = true, granted = false, isMock = false)
        assertTrue(status.getBoolean("enabled"))
        assertFalse(status.getBoolean("granted"))
        assertFalse(status.getBoolean("isMock"))
        assertFalse(status.getBoolean("usable"))
    }

    @Test
    fun calculateStatus_whenMockLocationDetected_usableIsFalse() {
        val status = implementation.calculateStatus(enabled = true, granted = true, isMock = true)
        assertTrue(status.getBoolean("enabled"))
        assertTrue(status.getBoolean("granted"))
        assertTrue(status.getBoolean("isMock"))
        assertFalse(status.getBoolean("usable"))
    }

    @Test
    fun isLocationMock_whenNull_returnsFalse() {
        assertFalse(implementation.isLocationMock(null))
    }

    @Test
    fun formatLocation_whenNull_returnsEmptyObject() {
        val formatted = implementation.formatLocation(null)
        assertEquals(0, formatted.length())
    }
}

