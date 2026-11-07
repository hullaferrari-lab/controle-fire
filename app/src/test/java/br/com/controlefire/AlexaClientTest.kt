package br.com.controlefire

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AlexaClientTest {
    private fun server(): MockWebServer {
        val cert = HeldCertificate.Builder().commonName("fire-tv-test").addSubjectAlternativeName("127.0.0.1").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        return MockWebServer().apply { useHttps(tls.sslSocketFactory(), false); start() }
    }
    @Test fun pairsPersistsCertificateAndAuthenticatesWithoutLoggingCredentials() {
        server().use { server ->
            val client = AlexaClient("127.0.0.1", port = server.port)
            try {
                server.enqueue(MockResponse().setBody("{\"description\":\"OK\"}"))
                client.displayPin()
                val display = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("/v1/FireTV/pin/display", display.path)
                assertEquals("Controle Fire", JSONObject(display.body.readUtf8()).getString("friendlyName"))
                assertNull(display.getHeader("x-client-token"))
                server.enqueue(MockResponse().setBody("{\"description\":\"\"}"))
                assertNull(client.verifyPin("0000")); server.takeRequest()
                server.enqueue(MockResponse().setBody("{\"description\":\"fixture-private-token\"}"))
                val creds = client.verifyPin("1234")!!; server.takeRequest()
                assertEquals(64, creds.certificate.length)
                assertFalse(creds.toString().contains(creds.token))
                server.enqueue(MockResponse().setBody("{}")); client.validate()
                assertEquals("fixture-private-token", server.takeRequest().getHeader("x-client-token"))
                server.enqueue(MockResponse().setResponseCode(401))
                assertThrows(AlexaUnauthorized::class.java) { client.validate() }
            } finally { client.dispose() }
        }
    }
    @Test fun rejectsAChangedReceiverCertificate() {
        server().use { first ->
            val client = AlexaClient("127.0.0.1", port = first.port)
            first.enqueue(MockResponse().setBody("{\"description\":\"OK\"}")); client.displayPin()
            first.enqueue(MockResponse().setBody("{\"description\":\"fixture-token\"}"))
            val creds = client.verifyPin("1234")!!; client.dispose()
            server().use { changed ->
                val next = AlexaClient("127.0.0.1", creds, port = changed.port)
                try {
                    changed.enqueue(MockResponse().setBody("{}"))
                    assertThrows(IOException::class.java) { next.validate() }
                } finally { next.dispose() }
            }
        }
    }
    @Test fun transportsExactPcmOnAuthenticatedWebSocketAndStops() = runBlocking {
        server().use { server ->
            val client = AlexaClient("127.0.0.1", port = server.port, voicePort = server.port)
            val audio = LinkedBlockingQueue<ByteArray>()
            try {
                server.enqueue(MockResponse().setBody("{\"description\":\"fixture-token\"}"))
                client.verifyPin("1234"); server.takeRequest()
                server.enqueue(MockResponse().setBody("{}")); client.startVoice()
                val start = server.takeRequest(); assertEquals("/v1/FireTV/voiceCommand?action=start", start.path)
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) { audio.offer(bytes.toByteArray()) }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                }))
                val closed = CompletableDeferred<Unit>()
                val socket = withTimeout(5000) { client.openVoiceSocket(onClosed = { closed.complete(Unit) }) {} }
                val upgrade = server.takeRequest(); assertEquals("fixture-token", upgrade.getHeader("x-client-token"))
                val pcm = ByteArray(3200) { it.toByte() }; client.sendPcm(socket, pcm, pcm.size)
                assertArrayEquals(pcm, audio.poll(3, TimeUnit.SECONDS))
                assertThrows(IllegalArgumentException::class.java) { client.sendPcm(socket, byteArrayOf(1), 1) }
                socket.close(1000, null)
                server.enqueue(MockResponse().setBody("{}")); client.stopVoice()
                assertEquals("/v1/FireTV/voiceCommand?action=stop", server.takeRequest().path)
                withTimeout(5000) { closed.await() }
            } finally { client.dispose() }
        }
    }
    @Test fun realAlexaVoiceWhenConfigured() = runBlocking {
        val credentialsPath = System.getenv("FIRETV_ALEXA_CREDENTIALS")
        val pcmPath = System.getenv("FIRETV_VOICE_PCM")
        assumeTrue(credentialsPath != null && pcmPath != null)
        val json = JSONObject(File(credentialsPath!!).readText())
        val client = AlexaClient("192.168.1.5", AlexaCredentials(json.getString("token"), json.getString("certificate")))
        var socket: WebSocket? = null
        try {
            client.validate(); client.startVoice()
            socket = withTimeout(10000) { client.openVoiceSocket {} }
            val pcm = File(pcmPath!!).readBytes()
            assertTrue(pcm.isNotEmpty() && pcm.size % 2 == 0)
            for (offset in pcm.indices step 3200) {
                val count = minOf(3200, pcm.size - offset)
                client.sendPcm(socket, pcm.copyOfRange(offset, offset + count), count)
                delay(count / 32L)
            }
        } finally {
            socket?.close(1000, null)
            try { client.stopVoice() } finally { client.dispose() }
        }
    }
    @Test fun nativeNavigationReleasesTheKeyEvenWhenDownFails() {
        server().use { server ->
            val client = AlexaClient("127.0.0.1", port = server.port)
            try {
                server.enqueue(MockResponse().setBody("{\"description\":\"fixture-token\"}"))
                client.verifyPin("1234"); server.takeRequest()
                server.enqueue(MockResponse().setResponseCode(500))
                server.enqueue(MockResponse().setBody("{}"))
                assertThrows(IOException::class.java) { client.pressKey(19) }
                val down = server.takeRequest(); val up = server.takeRequest()
                assertEquals("/v1/FireTV?action=dpad_up", down.path)
                assertEquals("keyDown", JSONObject(down.body.readUtf8()).getString("keyActionType"))
                assertEquals("keyUp", JSONObject(up.body.readUtf8()).getString("keyActionType"))
            } finally { client.dispose() }
        }
    }
    @Test fun nativeAppNamesIgnoreUninstalledAndShortcutTiles() {
        server().use { server ->
            val client = AlexaClient("127.0.0.1", port = server.port)
            try {
                server.enqueue(MockResponse().setBody("{\"description\":\"fixture-token\"}"))
                client.verifyPin("1234"); server.takeRequest()
                server.enqueue(MockResponse().setBody("""[
                    {"appId":"com.example.installed","name":"Meu app","isInstalled":true},
                    {"appId":"com.example.removed","name":"Removido","isInstalled":false},
                    {"appId":"com.example.shortcut","name":"Atalho","isShortcutApp":true},
                    {"appId":"../unsafe","name":"Inválido"}
                ]"""))
                assertEquals(mapOf("com.example.installed" to "Meu app"),client.appNames())
            } finally { client.dispose() }
        }
    }
    @Test fun realNativeNavigationWhenConfigured() {
        val path = System.getenv("FIRETV_ALEXA_CREDENTIALS")
        assumeTrue(path != null)
        val json=JSONObject(File(path!!).readText())
        val client=AlexaClient("192.168.1.5",AlexaCredentials(json.getString("token"),json.getString("certificate")))
        try {
            client.validate()
            val times=mutableListOf<Long>()
            for (code in listOf(22,21,22,21,22,21)) {
                val start=System.nanoTime();client.pressKey(code);times.add((System.nanoTime()-start)/1_000_000)
            }
            println("Native Kotlin navigation median: ${times.sorted()[times.size/2]} ms, ${times.size} presses")
            assertTrue(client.appNames().containsKey("com.amazon.firetv.youtube"))
        } finally { client.dispose() }
    }

}
