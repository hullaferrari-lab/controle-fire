package br.com.controlefire

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicLong

/** Bounded foreground input. Old presses must never replay after a reconnect or stall. */
internal class KeyInputQueue(
    scope: CoroutineScope,
    private val nanoTime: () -> Long = System::nanoTime,
    private val send: suspend (Int, () -> Boolean) -> Unit
) {
    private data class Press(val code: Int, val generation: Long, val queuedAt: Long)
    private val generation = AtomicLong()
    private val channel = Channel<Press>(4)
    private fun valid(press: Press) = press.generation == generation.get() && nanoTime() - press.queuedAt <= 750_000_000L
    init {
        val worker = scope.launch {
            for (press in channel) {
                ensureActive()
                if (!valid(press)) continue
                try { send(press.code) { valid(press) } }
                catch (error: CancellationException) {
                    // A stale ADB generation cancels one request, not this persistent worker.
                    // Real scope cancellation must still terminate the queue.
                    currentCoroutineContext().ensureActive()
                }
            }
        }
        worker.invokeOnCompletion { channel.close() }
    }
    fun offer(code: Int) = channel.trySend(Press(code, generation.get(), nanoTime())).isSuccess
    fun invalidate() { generation.incrementAndGet() }
}
