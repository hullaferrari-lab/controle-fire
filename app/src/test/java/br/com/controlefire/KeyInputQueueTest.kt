package br.com.controlefire

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeyInputQueueTest {
    @Test fun aStaleRequestCancellationDoesNotKillTheInputWorker() = runTest {
        val sent=mutableListOf<Int>()
        val input=KeyInputQueue(backgroundScope, { testScheduler.currentTime*1_000_000 }) { key, valid ->
            if (key==1) throw CancellationException("stale connection")
            if (valid()) sent.add(key)
        }
        input.offer(1);runCurrent()
        input.offer(2);runCurrent()
        assertEquals(listOf(2),sent)
    }
    @Test fun reconnectInvalidatesTheInFlightAndQueuedPresses() = runTest {
        val gate=CompletableDeferred<Unit>();val sent=mutableListOf<Int>()
        val input=KeyInputQueue(backgroundScope, { testScheduler.currentTime*1_000_000 }) { key, valid ->
            gate.await();if (valid()) sent.add(key)
        }
        input.offer(1);runCurrent();input.offer(2);input.invalidate()
        gate.complete(Unit);runCurrent();assertTrue(sent.isEmpty())
        input.offer(3);runCurrent();assertEquals(listOf(3),sent)
    }
    @Test fun anOldPressDoesNotReplayAfterANetworkStall() = runTest {
        val gate=CompletableDeferred<Unit>();val sent=mutableListOf<Int>()
        val input=KeyInputQueue(backgroundScope, { testScheduler.currentTime*1_000_000 }) { key, valid ->
            gate.await();if (valid()) sent.add(key)
        }
        input.offer(1);runCurrent();input.offer(2)
        advanceTimeBy(1000);gate.complete(Unit);runCurrent();assertTrue(sent.isEmpty())
        input.offer(3);runCurrent();assertEquals(listOf(3),sent)
    }
    @Test fun pendingInputIsBounded() = runTest {
        val gate=CompletableDeferred<Unit>()
        val input=KeyInputQueue(backgroundScope, { 0L }) { _, _ -> gate.await() }
        assertTrue(input.offer(0));runCurrent()
        repeat(4) { assertTrue(input.offer(it+1)) }
        assertFalse(input.offer(5))
    }
}
