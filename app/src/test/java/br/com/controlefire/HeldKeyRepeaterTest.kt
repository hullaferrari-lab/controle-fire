package br.com.controlefire

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeldKeyRepeaterTest {
    @Test fun sendsImmediatelyThenRepeatsAndStopsOnRelease() = runTest {
        val keys = mutableListOf<Int>()
        val repeater = HeldKeyRepeater(this, { true }) { key, valid -> if (valid()) keys.add(key) }
        repeater.start(19); runCurrent(); assertEquals(listOf(19), keys)
        advanceTimeBy(349); runCurrent(); assertEquals(1, keys.size)
        advanceTimeBy(1); runCurrent(); assertEquals(2, keys.size)
        advanceTimeBy(120); runCurrent(); assertEquals(3, keys.size)
        repeater.stop(); advanceTimeBy(2000); runCurrent(); assertEquals(3, keys.size)
    }
    @Test fun releasingWhileWaitingForTheConnectionDoesNotQueueAKey() = runTest {
        val keys = mutableListOf<Int>()
        val repeater = HeldKeyRepeater(this, { true }) { key, valid -> delay(100); if (valid()) keys.add(key) }
        repeater.start(19); runCurrent(); repeater.stop()
        advanceTimeBy(1000); runCurrent(); assertTrue(keys.isEmpty())
    }
    @Test fun changingDirectionAndLosingConnectionStopsTheOldDirection() = runTest {
        var connected = true
        val keys = mutableListOf<Int>()
        val repeater = HeldKeyRepeater(this, { connected }) { key, valid -> if (valid()) keys.add(key) }
        repeater.start(19); runCurrent(); repeater.start(20); runCurrent()
        assertEquals(listOf(19, 20), keys)
        connected = false; advanceTimeBy(1000); runCurrent()
        assertEquals(listOf(19, 20), keys)
        repeater.start(21); runCurrent(); assertEquals(2, keys.size)
        repeater.stop()
    }
}
