package br.com.controlefire

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceShutdownTest {
    private class Socket(val events: MutableList<String>, val onClose: () -> Unit = {}) : WebSocket {
        var queued=0L
        override fun request() = Request.Builder().url("https://127.0.0.1/").build()
        override fun queueSize()=queued
        override fun send(text: String)=true
        override fun send(bytes: ByteString)=true
        override fun close(code: Int, reason: String?): Boolean { events.add("close");onClose();return true }
        override fun cancel() { events.add("cancel") }
    }
    @Test fun waitsForPendingPcmBeforeStoppingTheReceiver() = runTest {
        val events=mutableListOf<String>();val closed=CompletableDeferred<Unit>()
        val socket=Socket(events) { closed.complete(Unit) }.apply { queued=3200 }
        launch { delay(50);socket.queued=0 }
        VoiceShutdown.finish(socket,closed) { assertEquals(0L,socket.queued);events.add("stop") }
        assertEquals(listOf("close","stop","cancel"),events)
        assertTrue(testScheduler.currentTime>=50)
    }
    @Test fun aStalledQueueStillStopsAndReportsIncompleteAudio() = runTest {
        val events=mutableListOf<String>();val closed=CompletableDeferred<Unit>()
        val socket=Socket(events) { closed.complete(Unit) }.apply { queued=3200 }
        val failure=runCatching { VoiceShutdown.finish(socket,closed) { events.add("stop") } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(listOf("close","stop","cancel"),events)
        assertTrue(testScheduler.currentTime in 1000..1100)
    }
    @Test fun aMissingCloseAcknowledgementHasABoundedWait() = runTest {
        val events=mutableListOf<String>();val socket=Socket(events)
        VoiceShutdown.finish(socket,CompletableDeferred()) { events.add("stop") }
        assertEquals(listOf("close","stop","cancel"),events)
        assertEquals(1000L,testScheduler.currentTime)
    }
    @Test fun stopFailureIsPreservedAndTheSocketIsAlwaysReleased() = runTest {
        val events=mutableListOf<String>();val closed=CompletableDeferred<Unit>().apply { complete(Unit) }
        val socket=Socket(events);val expected=IOException("receiver unavailable")
        val actual=runCatching { VoiceShutdown.finish(socket,closed) { throw expected } }.exceptionOrNull()
        assertTrue(actual is IOException);assertEquals(expected.message,actual?.message);assertEquals(listOf("close","cancel"),events)
    }
    @Test fun cancellingTheCallerDoesNotSkipRemoteCleanup() = runTest {
        val events=mutableListOf<String>();val closed=CompletableDeferred<Unit>();val socket=Socket(events)
        val job=launch { VoiceShutdown.finish(socket,closed) { events.add("stop") } }
        runCurrent();job.cancel();advanceTimeBy(1000);runCurrent()
        assertEquals(listOf("close","stop","cancel"),events)
        assertTrue(job.isCompleted)
    }
}
