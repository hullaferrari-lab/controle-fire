package br.com.controlefire

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AlexaCredentials(val token: String, val certificate: String) {
    override fun toString() = "AlexaCredentials(redacted)"
}
internal class AlexaUnauthorized : IOException("O pareamento da Alexa expirou. Pareie novamente.")

/** LAN voice protocol. See THIRD_PARTY_NOTICES.txt for protocol research attribution. */
internal class AlexaClient(
    val host: String,
    credentials: AlexaCredentials? = null,
    private val port: Int = 8080,
    private val voicePort: Int = 9090
) {
    private val certificate = AtomicReference(credentials?.certificate)
    @Volatile private var token: String? = credentials?.token
    private val tlsTrust = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("Client certificates are not used")
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty()) throw CertificateException("Certificado ausente")
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
            // Trust on first pairing only. All subsequent requests and WSS must match.
            certificate.compareAndSet(null, fingerprint)
            if (certificate.get() != fingerprint) throw CertificateException("O certificado do Fire Stick mudou. Pareie novamente.")
        }
    }
    private val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tlsTrust), SecureRandom()) }
    private val http = OkHttpClient.Builder()
        .sslSocketFactory(tls.socketFactory, tlsTrust)
        .hostnameVerifier { name, _ -> name == host }
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .build()
    init { require(RemoteCommands.validHost(host)) }
    private fun request(path: String, body: String? = null, authenticated: Boolean = true, limit: Int = 65536, timeoutMs: Long? = null): String {
        val builder = Request.Builder().url("https://$host:$port$path").header("x-api-key", "0987654321")
        if (authenticated) builder.header("x-client-token", token ?: throw AlexaUnauthorized())
        if (body != null) builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        val call = http.newCall(builder.build())
        if (timeoutMs != null) call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        return call.execute().use { response ->
            if (response.code == 401 || response.code == 403) throw AlexaUnauthorized()
            if (!response.isSuccessful) throw IOException("O Fire Stick recusou o comando Alexa (HTTP ${response.code}).")
            val source = response.body?.source() ?: return@use ""
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            val input = source.inputStream()
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > limit) throw IOException("Resposta Alexa inválida")
                out.write(buffer, 0, n)
            }
            out.toString("UTF-8")
        }
    }
    fun activateRemote() {
        val request = Request.Builder().url("http://$host:8009/apps/FireTVRemote")
            .post("".toRequestBody("text/plain".toMediaType())).build()
        http.newCall(request).execute().use { if (it.code != 201) throw IOException("Não foi possível reativar o controle na TV.") }
    }
    fun displayPin() {
        val response = JSONObject(request("/v1/FireTV/pin/display", JSONObject().put("friendlyName", "Controle Fire").toString(), false))
        if (response.optString("description") != "OK") throw IOException("Não foi possível mostrar o código na TV.")
    }
    fun verifyPin(pin: String): AlexaCredentials? {
        require(Regex("[0-9]{4}").matches(pin)) { "Digite os quatro dígitos da TV." }
        val response = JSONObject(request("/v1/FireTV/pin/verify", JSONObject().put("pin", pin).toString(), false))
        val value = response.optString("description")
        if (value.isEmpty()) return null
        if (value.length > 4096 || value.any { it.code !in 32..126 }) throw IOException("Resposta de pareamento inválida")
        val result = AlexaCredentials(value, certificate.get() ?: throw IOException("Certificado ausente"))
        token = value
        return result
    }
    fun validate() { request("/v1/FireTV/status") }
    fun appNames(): Map<String, String> {
        val payload = org.json.JSONTokener(request("/v1/FireTV/appsV2", limit = 1024 * 1024)).nextValue()
        val array = when (payload) {
            is org.json.JSONArray -> payload
            is JSONObject -> payload.optJSONArray("apps") ?: payload.optJSONArray("appsV2") ?: payload.optJSONArray("result")
            else -> null
        } ?: throw IOException("Lista nativa de aplicativos indisponível")
        return buildMap {
            for (i in 0 until minOf(array.length(), 5000)) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("appId")
                if (RemoteCommands.validPackage(id) && !item.optBoolean("isShortcutApp", false) && item.optBoolean("isInstalled", true)) put(id, item.optString("name", id).take(200))
            }
        }
    }
    fun pressKey(code: Int) {
        val action = NativeKeys.action(code) ?: throw IllegalArgumentException("Tecla não suportada no controle Wi-Fi nativo")
        when (code) {
            in 19..23 -> {
                // Release even when the down response was lost after reaching the receiver.
                try { request("/v1/FireTV?action=$action", "{\"keyActionType\":\"keyDown\"}", timeoutMs = 2000) }
                finally { request("/v1/FireTV?action=$action", "{\"keyActionType\":\"keyUp\"}", timeoutMs = 2000) }
            }
            85 -> request("/v1/media?action=play", "", timeoutMs = 2000)
            else -> request("/v1/FireTV?action=$action", "", timeoutMs = 2000)
        }
    }
    fun startVoice() { request("/v1/FireTV/voiceCommand?action=start", "") }
    fun stopVoice() { request("/v1/FireTV/voiceCommand?action=stop", "") }
    suspend fun openVoiceSocket(onClosed: () -> Unit = {}, onFailure: (IOException) -> Unit): WebSocket = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url("wss://$host:$voicePort/")
            .header("x-api-key", "0987654321")
            .header("x-client-token", token ?: run { continuation.resumeWithException(AlexaUnauthorized()); return@suspendCancellableCoroutine })
            .build()
        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (continuation.isActive) continuation.resume(webSocket) else webSocket.cancel()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val error = if (response?.code in listOf(401, 403)) AlexaUnauthorized() else IOException("A conexão de áudio com a Alexa foi interrompida.")
                if (continuation.isActive) continuation.resumeWithException(error) else onFailure(error)
                onClosed()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { onClosed() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                onFailure(IOException("A Alexa encerrou a escuta.")); webSocket.close(code, null)
            }
        })
        continuation.invokeOnCancellation { socket.cancel() }
    }
    fun sendPcm(socket: WebSocket, bytes: ByteArray, count: Int) {
        require(count > 0 && count % 2 == 0 && count <= bytes.size)
        if (socket.queueSize() > 64000 || !socket.send(bytes.toByteString(0, count))) throw IOException("A rede está lenta para transmitir sua voz. Tente novamente.")
    }
    fun dispose() { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
}
