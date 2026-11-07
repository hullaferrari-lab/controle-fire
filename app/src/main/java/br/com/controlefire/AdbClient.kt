package br.com.controlefire

import java.io.*
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

internal object AdbWire {
    fun command(s: String): Int = s.toByteArray(Charsets.US_ASCII).foldIndexed(0) { i, a, b -> a or ((b.toInt() and 255) shl (8 * i)) }
    val CNXN = command("CNXN"); val AUTH = command("AUTH"); val OPEN = command("OPEN")
    val OKAY = command("OKAY"); val WRTE = command("WRTE"); val CLSE = command("CLSE")
    data class Packet(val command: Int, val a: Int, val b: Int, val data: ByteArray = byteArrayOf())
    fun write(output: OutputStream, p: Packet) {
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(p.command).putInt(p.a).putInt(p.b).putInt(p.data.size)
            .putInt(p.data.sumOf { it.toInt() and 255 }).putInt(p.command xor -1)
        output.write(h.array()); output.write(p.data); output.flush()
    }
    fun read(input: InputStream): Packet {
        val h = ByteArray(24); DataInputStream(input).readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a = b.int; val remote = b.int; val size = b.int; val sum = b.int; val magic = b.int
        if (magic != cmd xor -1 || size !in 0..(1024 * 1024)) throw IOException("Resposta ADB inválida")
        val payload = ByteArray(size); DataInputStream(input).readFully(payload)
        if (payload.sumOf { it.toInt() and 255 } != sum) throw IOException("Resposta ADB corrompida")
        return Packet(cmd, a, remote, payload)
    }
}

internal class AdbIdentity(private val directory: File) {
    private val pair: KeyPair by lazy {
        directory.mkdirs()
        val privateFile = File(directory, "private.der"); val publicFile = File(directory, "public.der")
        if (privateFile.exists() && publicFile.exists()) {
            val factory = KeyFactory.getInstance("RSA")
            KeyPair(factory.generatePublic(X509EncodedKeySpec(publicFile.readBytes())), factory.generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes())))
        } else {
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().also {
                privateFile.writeBytes(it.private.encoded); publicFile.writeBytes(it.public.encoded)
            }
        }
    }
    fun sign(token: ByteArray): ByteArray {
        require(token.size == 20) { "Desafio ADB inválido" }
        // ADB already supplies a SHA-1 digest; sign its DigestInfo without hashing again.
        val prefix = byteArrayOf(0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14)
        return Cipher.getInstance("RSA/ECB/PKCS1Padding").run { init(Cipher.ENCRYPT_MODE, pair.private); doFinal(prefix + token) }
    }
    fun publicPayload(): ByteArray {
        val key = pair.public as RSAPublicKey
        val n = key.modulus; val base = BigInteger.ONE.shiftLeft(32)
        val n0inv = base.subtract(n.mod(base).modInverse(base)).toLong().toInt()
        fun little(value: BigInteger): ByteArray {
            val src = value.toByteArray().reversedArray()
            return ByteArray(256) { if (it < src.size) src[it] else 0 }
        }
        val packed = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(64).putInt(n0inv).put(little(n))
            .put(little(BigInteger.ONE.shiftLeft(4096).mod(n))).putInt(key.publicExponent.toInt()).array()
        return (Base64.getEncoder().encodeToString(packed) + " controlefire@android\u0000").toByteArray(Charsets.UTF_8)
    }
}

internal class AdbClient(private val identity: AdbIdentity) : Closeable {
    @Volatile private var socket: Socket? = null
    private var nextId = 0
    private var ready = false
    fun connect(host: String, port: Int = 5555, awaitingAuthorization: () -> Unit) {
        close()
        val s = Socket(); socket = s
        try {
            s.connect(InetSocketAddress(host, port), 4000); s.tcpNoDelay = true; s.soTimeout = 90000
            val input = s.getInputStream(); val output = s.getOutputStream()
            AdbWire.write(output, AdbWire.Packet(AdbWire.CNXN, 0x01000000, 1024 * 1024, "host::\u0000".toByteArray()))
            var signed = false; var offered = false
            while (true) {
                val p = AdbWire.read(input)
                when (p.command) {
                    AdbWire.CNXN -> { ready = true; s.soTimeout = 12000; return }
                    AdbWire.AUTH -> {
                        if (p.a != 1 || offered) throw IOException("Autorização recusada. Reconecte e confirme na TV.")
                        if (!signed) {
                            AdbWire.write(output, AdbWire.Packet(AdbWire.AUTH, 2, 0, identity.sign(p.data))); signed = true
                        } else {
                            awaitingAuthorization()
                            AdbWire.write(output, AdbWire.Packet(AdbWire.AUTH, 3, 0, identity.publicPayload())); offered = true
                        }
                    }
                    else -> throw IOException("O dispositivo não respondeu como Fire TV/ADB")
                }
            }
        } catch (e: Exception) { close(); throw e }
    }
    @Synchronized fun service(destination: String, limit: Int = 2 * 1024 * 1024): ByteArray {
        val s = socket ?: throw IOException("Conecte ao Fire Stick")
        if (!ready) throw IOException("Conexão ainda não autorizada")
        val id = ++nextId
        val output = s.getOutputStream(); val input = s.getInputStream()
        val bytes = ByteArrayOutputStream(); var remote = 0
        try {
            AdbWire.write(output, AdbWire.Packet(AdbWire.OPEN, id, 0, (destination + "\u0000").toByteArray()))
            val deadline = System.nanoTime() + 60_000_000_000L
            while (true) {
                if (System.nanoTime() > deadline) throw IOException("O Fire Stick demorou para responder")
                val p = AdbWire.read(input)
                // Fire OS can repeat CLSE for a channel after its close acknowledgement.
                // It belongs to the previous command, not to the newly opened channel.
                if (p.command == AdbWire.CLSE && p.b in 1 until id) continue
                if (p.b != id) throw IOException("Canal ADB inesperado")
                when (p.command) {
                    AdbWire.OKAY -> remote = p.a
                    AdbWire.WRTE -> {
                        if (remote == 0) remote = p.a
                        if (bytes.size().toLong() + p.data.size > limit) throw IOException("Resposta maior que o limite permitido")
                        bytes.write(p.data)
                        AdbWire.write(output, AdbWire.Packet(AdbWire.OKAY, id, remote))
                    }
                    AdbWire.CLSE -> {
                        AdbWire.write(output, AdbWire.Packet(AdbWire.CLSE, id, p.a))
                        if (remote == 0) throw IOException("Serviço indisponível no Fire Stick")
                        return bytes.toByteArray()
                    }
                    else -> throw IOException("Resposta ADB inesperada")
                }
            }
        } catch (e: Exception) { close(); throw e }
    }
    fun shell(command: String): String = service("shell:$command").toString(Charsets.UTF_8).replace("\r", "")
    override fun close() { ready = false; val old = socket; socket = null; runCatching { old?.close() } }
}

internal object RemoteCommands {
    fun validHost(host: String): Boolean = host.split('.').let { p -> p.size == 4 && p.all { it.isNotEmpty() && it.all(Char::isDigit) && (it.toIntOrNull() ?: -1) in 0..255 } && p[0] != "0" && p[0].toInt() < 224 && host != "255.255.255.255" }
    fun validPackage(id: String) = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(id)
    fun components(raw: String): Map<String, String> = raw.lineSequence().map(String::trim)
        .filter { Regex("[A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+").matches(it) }
        .distinctBy { it.substringBefore('/') }.associateBy { it.substringBefore('/') }
    fun launch(component: String): String {
        require(components(component).isNotEmpty())
        return "am start -a android.intent.action.MAIN -n '$component'"
    }
}
