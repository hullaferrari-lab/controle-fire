package br.com.controlefire

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

/** At most one in-flight key. The validity callback is checked after acquiring the ADB lock. */
internal class HeldKeyRepeater(
    private val scope: CoroutineScope,
    private val isConnected: () -> Boolean,
    private val send: suspend (Int, () -> Boolean) -> Unit
) {
    private val token = AtomicLong()
    private var job: Job? = null
    fun start(code: Int) {
        stop()
        if (!isConnected()) return
        val current = token.get()
        job = scope.launch {
            var first = true
            while (isActive && token.get() == current && isConnected()) {
                send(code) { token.get() == current && isConnected() }
                delay(if (first) 350L else 120L)
                first = false
            }
        }
    }
    fun stop() { token.incrementAndGet(); job?.cancel(); job = null }
}
