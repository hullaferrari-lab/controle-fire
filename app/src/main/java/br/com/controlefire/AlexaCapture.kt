package br.com.controlefire

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import okhttp3.WebSocket
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/** Captures only during the explicit foreground press. No recording is saved to disk. */
internal class AlexaCapture(private val context: Context, private val client: AlexaClient) {
    suspend fun run(onListening: () -> Unit) = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw SecurityException("Permita o microfone para falar com a Alexa.")
        var record: AudioRecord? = null
        var socket: WebSocket? = null
        var startAttempted = false
        val failure = AtomicReference<IOException?>(null)
        val closed = CompletableDeferred<Unit>()
        try {
            ensureActive()
            startAttempted = true
            client.startVoice()
            ensureActive()
            socket = withTimeout(10000) { client.openVoiceSocket(onClosed = { closed.complete(Unit) }) { failure.compareAndSet(null, it) } }
            ensureActive()
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) throw IOException("O celular não oferece o formato de áudio necessário à Alexa.")
            record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6400))
            if (record.state != AudioRecord.STATE_INITIALIZED) throw IOException("Não foi possível abrir o microfone.")
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("O microfone está ocupado por outro aplicativo.")
            onListening()
            val buffer = ByteArray(3200)
            val end = System.nanoTime() + 15_000_000_000L
            while (System.nanoTime() < end) {
                coroutineContext.ensureActive()
                failure.get()?.let { throw it }
                val n = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (n < 0) throw IOException("A captura do microfone foi interrompida.")
                coroutineContext.ensureActive()
                val complete = n - n % 2
                if (complete > 0) client.sendPcm(socket, buffer, complete)
                // AudioRecord paces the stream at real time; never replay or batch old audio.
            }
        } finally {
            if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { record.stop() }
            record?.release()
            withContext(NonCancellable + Dispatchers.IO) {
                VoiceShutdown.finish(socket, closed) { if (startAttempted) client.stopVoice() }
            }
        }
    }
}
