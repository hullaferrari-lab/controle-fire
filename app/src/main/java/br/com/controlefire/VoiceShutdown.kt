package br.com.controlefire

import kotlinx.coroutines.*
import okhttp3.WebSocket
import java.io.IOException

/** Stop local capture before entering here. Flush already-captured PCM, with finite deadlines. */
internal object VoiceShutdown {
    suspend fun finish(socket: WebSocket?, closed: Deferred<Unit>, stopRemote: suspend () -> Unit) = withContext(NonCancellable) {
        val drained = socket == null || withTimeoutOrNull(1000) {
            while (socket.queueSize() > 0) delay(10)
            true
        } == true
        socket?.close(1000, null)
        try {
            // Start HTTP stop without waiting for WSS acknowledgement: Fire TV may need it to close.
            stopRemote()
        } finally {
            if (socket != null) withTimeoutOrNull(1000) { closed.await() }
            socket?.cancel()
        }
        if (!drained) throw IOException("Parte da voz ficou pendente na rede. Tente novamente com uma conexão mais estável.")
    }
}
