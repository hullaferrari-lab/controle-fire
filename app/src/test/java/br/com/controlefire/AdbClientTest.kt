package br.com.controlefire

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.*
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

class AdbClientTest {
    private fun directory() = Files.createTempDirectory("fire-identity").toFile().apply { deleteOnExit() }
    @Test fun packetRoundTripAndCorruption() {
        val data = byteArrayOf(0, -1, 5, 13, 10)
        val out = ByteArrayOutputStream()
        AdbWire.write(out, AdbWire.Packet(AdbWire.WRTE, 3, 7, data))
        val read = AdbWire.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(3, read.a); assertEquals(7, read.b); assertArrayEquals(data, read.data)
        val bad = out.toByteArray(); bad[24] = 42
        assertThrows(IOException::class.java) { AdbWire.read(ByteArrayInputStream(bad)) }
    }
    @Test fun refusesOversizedPacketBeforeAllocation() {
        val bytes = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(AdbWire.WRTE).putInt(1).putInt(1).putInt(Int.MAX_VALUE).putInt(0).putInt(AdbWire.WRTE xor -1).array()
        assertThrows(IOException::class.java) { AdbWire.read(ByteArrayInputStream(bytes)) }
    }
    @Test fun stableIdentityAndCorrectAdbSignature() {
        val dir = directory(); val first = AdbIdentity(dir); val token = ByteArray(20) { it.toByte() }
        val signature = first.sign(token)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(File(dir, "public.der").readBytes()))
        val decrypted = Cipher.getInstance("RSA/ECB/PKCS1Padding").run { init(Cipher.DECRYPT_MODE, publicKey); doFinal(signature) }
        assertArrayEquals(token, decrypted.takeLast(20).toByteArray())
        assertEquals(35, decrypted.size)
        assertArrayEquals(first.publicPayload(), AdbIdentity(dir).publicPayload())
        assertEquals(524, Base64.getDecoder().decode(first.publicPayload().toString(Charsets.UTF_8).substringBefore(' ')).size)
    }
    @Test fun authenticatesAndAcknowledgesMultiPacketShell() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val remote = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 4000
                        val input = socket.getInputStream(); val output = socket.getOutputStream()
                        assertEquals(AdbWire.CNXN, AdbWire.read(input).command)
                        AdbWire.write(output, AdbWire.Packet(AdbWire.AUTH, 1, 0, ByteArray(20)))
                        assertEquals(2, AdbWire.read(input).a)
                        AdbWire.write(output, AdbWire.Packet(AdbWire.AUTH, 1, 0, ByteArray(20)))
                        assertEquals(3, AdbWire.read(input).a)
                        AdbWire.write(output, AdbWire.Packet(AdbWire.CNXN, 0x01000000, 4096))
                        val open = AdbWire.read(input); assertEquals(AdbWire.OPEN, open.command)
                        AdbWire.write(output, AdbWire.Packet(AdbWire.OKAY, 42, open.a))
                        listOf("hello", " world").forEach { text ->
                            AdbWire.write(output, AdbWire.Packet(AdbWire.WRTE, 42, open.a, text.toByteArray()))
                            val ack = AdbWire.read(input); assertEquals(AdbWire.OKAY, ack.command); assertEquals(42, ack.b)
                        }
                        AdbWire.write(output, AdbWire.Packet(AdbWire.CLSE, 42, open.a))
                        assertEquals(AdbWire.CLSE, AdbWire.read(input).command)
                        val second = AdbWire.read(input)
                        AdbWire.write(output, AdbWire.Packet(AdbWire.CLSE, 42, open.a))
                        AdbWire.write(output, AdbWire.Packet(AdbWire.OKAY, 43, second.a))
                        AdbWire.write(output, AdbWire.Packet(AdbWire.WRTE, 43, second.a, "next".toByteArray()))
                        assertEquals(AdbWire.OKAY, AdbWire.read(input).command)
                        AdbWire.write(output, AdbWire.Packet(AdbWire.CLSE, 43, second.a))
                        assertEquals(AdbWire.CLSE, AdbWire.read(input).command)
                    }
                }
                AdbClient(AdbIdentity(directory())).use { client ->
                    var authorization = false
                    client.connect("127.0.0.1", server.localPort) { authorization = true }
                    assertTrue(authorization); assertEquals("hello world", client.shell("echo hello")); assertEquals("next", client.shell("echo next"))
                }
                remote.get(6, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun authorizationRejectedClosesConnection() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val remote = executor.submit {
                    server.accept().use { s ->
                        s.soTimeout = 4000
                        val input = s.getInputStream(); val output = s.getOutputStream()
                        AdbWire.read(input)
                        repeat(3) { i ->
                            AdbWire.write(output, AdbWire.Packet(AdbWire.AUTH, 1, 0, ByteArray(20)))
                            if (i < 2) AdbWire.read(input)
                        }
                    }
                }
                AdbClient(AdbIdentity(directory())).use { client ->
                    assertThrows(IOException::class.java) { client.connect("127.0.0.1", server.localPort) {} }
                    assertThrows(IOException::class.java) { client.shell("echo no") }
                }
                remote.get(6, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun connectionLossDoesNotLeaveAUsableStaleSession() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val remote = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 4000
                        AdbWire.read(socket.getInputStream())
                        AdbWire.write(socket.getOutputStream(), AdbWire.Packet(AdbWire.CNXN, 0x01000000, 4096))
                        AdbWire.read(socket.getInputStream())
                        // Simulates Wi-Fi loss after opening a command channel.
                    }
                }
                AdbClient(AdbIdentity(directory())).use { client ->
                    client.connect("127.0.0.1", server.localPort) {}
                    assertThrows(IOException::class.java) { client.shell("echo lost") }
                    assertThrows(IOException::class.java) { client.shell("echo stale") }
                }
                remote.get(6, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun validatesInputsAndParsesFireOsCatalog() {
        assertTrue(RemoteCommands.validHost("192.168.1.5"))
        listOf("999.1.1.1", "127.0.0.1;reboot", "1.2.3", "224.1.1.1").forEach { assertFalse(RemoteCommands.validHost(it)) }
        assertFalse(RemoteCommands.validPackage("x';reboot"))
        val result = RemoteCommands.components("2 activities found:\n Activity #0:\n com.example.app/.MainActivity\n com.foo/dev.foo.Main")
        assertEquals(2, result.size); assertEquals("com.example.app/.MainActivity", result["com.example.app"])
        assertThrows(IllegalArgumentException::class.java) { RemoteCommands.launch("com.foo/.Main';reboot") }
    }
    @Test fun realDeviceSmokeWhenConfigured() {
        val host = System.getenv("FIRETV_TEST_HOST")
        val identity = System.getenv("FIRETV_TEST_IDENTITY")
        assumeTrue(host != null && identity != null)
        AdbClient(AdbIdentity(File(identity!!))).use { client ->
            client.connect(host!!) {}
            assertEquals("AFTSSS", client.shell("getprop ro.product.model").trim())
            val raw = client.shell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER")
            val apps = RemoteCommands.components(raw)
            assertTrue(apps.containsKey("com.esaba.downloader"))
            assertEquals("", client.shell("input keyevent 3").trim())
            assertEquals("", client.shell("input keyevent 20").trim())
            assertEquals("", client.shell("input keyevent 19").trim())
            val launched = client.shell(RemoteCommands.launch(apps.getValue("com.esaba.downloader")))
            assertFalse(launched.contains("Error:")); assertTrue(launched.contains("Starting:"))
            client.shell("input keyevent 3")
            val path = client.shell("pm path com.esaba.downloader").trim().removePrefix("package:")
            assertTrue(path.startsWith("/data/app/"))
            val apk = client.service("exec:cat '$path'", 24 * 1024 * 1024)
            assertTrue(apk.size > 1000); assertEquals('P'.code.toByte(), apk[0]); assertEquals('K'.code.toByte(), apk[1])
            val save = System.getenv("FIRETV_TEST_APK")
            if (save != null) File(save).writeBytes(apk)
            // Reconnect with the same identity without another authorization prompt.
            client.connect(host) {}; assertEquals("ok", client.shell("echo ok").trim())
        }
    }
}
