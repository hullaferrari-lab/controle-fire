package br.com.controlefire

import org.junit.Assert.*
import org.junit.Test

class HidReportStateTest {
    @Test fun getReportReturnsTheLastPressedKeyWithoutExposingItsBuffer() {
        val state = HidReportState()
        val key = HidReports.forKey(19)
        state.pressed(key)
        key.bytes.fill(0)
        assertEquals(0x52, state.getReport(1)[2].toInt())
        state.getReport(1).fill(0)
        assertEquals(0x52, state.getReport(1)[2].toInt())
    }

    @Test fun disconnectResetClearsBothReportsBeforeReconnectGetReport() {
        val state = HidReportState()
        state.pressed(HidReports.forKey(19))
        state.pressed(HidReports.forKey(85))
        state.reset() // Disconnect, even though no receiver remains to accept releases.
        assertArrayEquals(ByteArray(8), state.getReport(1))
        assertArrayEquals(ByteArray(2), state.getReport(2))
        val reconnectReports = state.reset()
        assertEquals(listOf(1, 2), reconnectReports.map { it.id })
        assertArrayEquals(ByteArray(8), reconnectReports[0].bytes)
        assertArrayEquals(ByteArray(2), reconnectReports[1].bytes)
    }

    @Test fun pauseOrUnregisterCanResetWithoutRadioAndNextSessionAcceptsNewKeys() {
        val state = HidReportState()
        state.pressed(HidReports.forKey(4))
        state.reset() // No permission/device/service is needed to clear local state.
        state.reset() // Repeated lifecycle callbacks are harmless.
        assertArrayEquals(ByteArray(2), state.getReport(2))
        state.pressed(HidReports.forKey(22))
        assertEquals(0x4F, state.getReport(1)[2].toInt())
        assertArrayEquals(ByteArray(2), state.getReport(2))
    }

    @Test fun neutralReportsAreIndependentOfStoredState() {
        val state = HidReportState()
        state.reset().forEach { it.bytes.fill(127) }
        assertArrayEquals(ByteArray(8), state.getReport(1))
        assertArrayEquals(ByteArray(2), state.getReport(2))
        assertThrows(IllegalArgumentException::class.java) { state.getReport(0) }
    }
}
