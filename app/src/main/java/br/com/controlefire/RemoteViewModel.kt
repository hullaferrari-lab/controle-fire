package br.com.controlefire

import android.app.Application
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.ConnectivityManager
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

internal data class TvApp(val packageName: String, val component: String, val name: String = packageName, val icon: Bitmap? = null)
internal enum class ControlMode { AUTO, BLUETOOTH, ADB }
internal enum class Connection { OFFLINE, CONNECTING, AUTHORIZING, ONLINE }

internal class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("controle", 0)
    private val adbIdentity = AdbIdentity(File(application.filesDir, "adb"))
    private val client = AdbClient(adbIdentity)
    private val catalogClient = AdbClient(adbIdentity)
    private val catalogMutex = Mutex()
    private val controlMutex = Mutex()
    private val catalogGeneration = AtomicLong()
    @Volatile private var catalogConnected = false
    private val holdGeneration = AtomicLong()
    var controlMode by mutableStateOf(runCatching { ControlMode.valueOf(prefs.getString("controlMode", "AUTO")!!) }.getOrDefault(ControlMode.AUTO)); private set
    var bluetoothRegistered by mutableStateOf(false); private set
    var bluetoothConnected by mutableStateOf(false); private set
    var bluetoothMessage by mutableStateOf("Bluetooth ainda não ativado."); private set
    var bluetoothTargets by mutableStateOf<List<BluetoothTarget>>(emptyList()); private set
    private var bluetooth: BluetoothRemote? = null
    private var bluetoothAutoAttempted = false
    val controlsReady get() = if (controlMode == ControlMode.BLUETOOTH) bluetoothConnected else state == Connection.ONLINE
    val transportLabel get() = when {
        controlMode == ControlMode.BLUETOOTH -> if (bluetoothConnected) "Bluetooth" else "Bluetooth desconectado"
        state != Connection.ONLINE -> "Wi-Fi desconectado"
        controlMode == ControlMode.AUTO && alexaPaired -> "Wi-Fi nativo"
        else -> "ADB • compatibilidade"
    }
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private var connectJob: Job? = null
    private var catalogJob: Job? = null
    private var monitorJob: Job? = null
    private var scanJob: Job? = null
    private var alexa: AlexaClient? = null
    private var pairingJob: Job? = null
    private var pairingClient: AlexaClient? = null
    private var voiceJob: Job? = null
    var alexaPaired by mutableStateOf(false); private set
    var pairingRequested by mutableStateOf(false); private set
    var pairingBusy by mutableStateOf(false); private set
    var pairingError by mutableStateOf<String?>(null); private set
    var voiceActive by mutableStateOf(false); private set
    var voiceBusy by mutableStateOf(false); private set
    var host by mutableStateOf(prefs.getString("host", "") ?: ""); private set
    var state by mutableStateOf(Connection.OFFLINE); private set
    var message by mutableStateOf("Conecte seu Fire Stick para começar."); private set
    var deviceName by mutableStateOf("Seu Fire Stick"); private set
    var discovered by mutableStateOf<List<String>>(emptyList()); private set
    var scanning by mutableStateOf(false); private set
    var apps by mutableStateOf<List<TvApp>>(emptyList()); private set
    var loadingApps by mutableStateOf(false); private set
    var catalogError by mutableStateOf<String?>(null); private set
    var editingSlot by mutableStateOf<Int?>(null); private set
    var shortcuts by mutableStateOf<List<TvApp?>>(List(4) { null }); private set
    private var deviceId = ""
    private val appContext get() = getApplication<Application>()
    fun updateHost(value: String) { host = value.filter { it.isDigit() || it == '.' }.take(15) }
    fun notice(value: String) { message = value }
    fun connect(ip: String = host) {
        if (!RemoteCommands.validHost(ip)) { message = "Informe um endereço IP válido, como 192.168.1.20."; return }
        disconnect()
        host = ip
        val epoch = generation.get()
        state = Connection.CONNECTING; message = "Conectando a $ip…"
        connectJob = viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    mutex.withLock {
                        ensureCurrent(epoch)
                        client.connect(ip) { viewModelScope.launch { if (generation.get() == epoch) { state = Connection.AUTHORIZING; message = "Na TV, permita a depuração para este celular. Você tem até 90 segundos." } } }
                        val name = client.shell("settings get global device_name").trim().takeIf { it.isNotBlank() && it != "null" } ?: "Fire TV Stick"
                        val serial = client.shell("getprop ro.serialno").trim().takeIf { it.isNotBlank() } ?: ip
                        name to serial
                    }
                }
                ensureCurrent(epoch)
                deviceName = info.first; deviceId = info.second
                prefs.edit().putString("host", ip).apply()
                val nativeToken = prefs.getString("$deviceId.alexaToken", null)
                val nativeCert = prefs.getString("$deviceId.alexaCertificate", null)
                val credentials = if (nativeToken != null && nativeCert != null) AlexaCredentials(nativeToken, nativeCert) else null
                alexa = AlexaClient(ip, credentials)
                alexaPaired = credentials != null
                shortcuts = readShortcuts()
                apps = emptyList(); state = Connection.ONLINE; message = "Conectado a ${info.first}"
                monitorJob = viewModelScope.launch {
                    while (isActive) { delay(15000); execute { client.shell("echo controlefire") } }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(epoch, e) }
        }
    }
    private fun ensureCurrent(epoch: Long) { if (generation.get() != epoch) throw CancellationException() }
    private fun fail(epoch: Long, error: Exception) {
        if (generation.get() != epoch) return
        disconnect()
        message = when (error) {
            is java.net.SocketTimeoutException -> "Tempo esgotado. Confira o IP, o Wi-Fi e a autorização na TV."
            is java.net.ConnectException -> "Conexão recusada. Confira se o ADB está ativo e se os aparelhos estão na mesma rede."
            else -> "Conexão interrompida: ${error.message ?: "verifique a rede e tente novamente"}"
        }
    }
    private suspend fun <T> execute(block: () -> T): T? {
        val epoch = generation.get()
        if (state != Connection.ONLINE) return null
        return try {
            withContext(Dispatchers.IO) { mutex.withLock { ensureCurrent(epoch); block() } }.also { ensureCurrent(epoch) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { fail(epoch, e); null }
    }
    private val input = KeyInputQueue(viewModelScope) { code, valid -> sendKey(code, valid) }
    fun onFocusLost() { input.invalidate(); stopDirection(); stopVoice() }
    fun chooseControlMode(mode: ControlMode) {
        stopDirection(); input.invalidate()
        controlMode = mode
        if (mode != ControlMode.BLUETOOTH) suspendBluetooth()
        prefs.edit().putString("controlMode", mode.name).apply()
        message = if (mode == ControlMode.BLUETOOTH) "Ative e pareie o controle Bluetooth na engrenagem." else if (mode == ControlMode.AUTO && !alexaPaired) "Pareie o controle Wi-Fi na engrenagem para reduzir a demora." else "Controle: $transportLabel"
    }
    fun bluetoothNotice(text: String) { bluetoothMessage = text; message = text }
    fun startBluetooth() {
        if (Build.VERSION.SDK_INT < 28) { bluetoothMessage = "Controle Bluetooth exige Android 9 ou superior. Use Wi-Fi neste celular."; return }
        if (bluetooth == null) bluetooth = BluetoothRemote(appContext) { registered, connected, text ->
            bluetoothRegistered = registered; bluetoothConnected = connected; bluetoothMessage = text
            if (connected && Build.VERSION.SDK_INT >= 28) {
                if (controlMode != ControlMode.BLUETOOTH) chooseControlMode(ControlMode.BLUETOOTH)
                bluetooth?.connectedTarget()?.let { target -> prefs.edit().putString("btAddress", target.address).putBoolean("btAutoReconnect", true).apply() }
            }
            if (controlMode == ControlMode.BLUETOOTH) message = text
            refreshBluetoothTargets()
            if (registered && !connected && !bluetoothAutoAttempted && prefs.getBoolean("btAutoReconnect", false)) {
                bluetoothAutoAttempted = true
                prefs.getString("btAddress", null)?.let { bluetooth?.connect(it) }
            }
        }
        runCatching { bluetooth?.start() }.onFailure { bluetoothNotice("Não foi possível iniciar Bluetooth. Confira a permissão de dispositivos próximos e tente novamente.") }; refreshBluetoothTargets()
    }
    fun refreshBluetoothTargets() { if (Build.VERSION.SDK_INT >= 28) bluetoothTargets = runCatching { bluetooth?.pairedTargets().orEmpty() }.getOrDefault(emptyList()) }
    fun connectBluetooth(address: String) {
        prefs.edit().putString("btAddress", address).putBoolean("btAutoReconnect", true).apply()
        bluetoothAutoAttempted = true
        if (Build.VERSION.SDK_INT >= 28) runCatching { bluetooth?.connect(address) }.onFailure { bluetoothNotice("A conexão Bluetooth falhou. Confira as permissões e o pareamento.") }
    }
    fun pauseBluetooth() { prefs.edit().putBoolean("btAutoReconnect", false).apply(); suspendBluetooth() }
    private fun suspendBluetooth() {
        stopDirection()
        if (Build.VERSION.SDK_INT >= 28) bluetooth?.pause()
        bluetoothConnected = false; bluetoothRegistered = false
    }
    fun key(code: Int) {
        if (!controlsReady) { message = "Conecte o transporte selecionado primeiro."; return }
        if (!input.offer(code)) message = "A rede está demorando. Aguarde os comandos pendentes."
    }
    private suspend fun sendKey(code: Int, valid: () -> Boolean) {
        if (!controlsReady) return
        try {
            controlMutex.withLock {
                if (!valid()) return@withLock
                if (controlMode == ControlMode.BLUETOOTH && code !in listOf(24, 25, 164)) {
                    if (Build.VERSION.SDK_INT >= 28) withContext(Dispatchers.IO) { if (valid()) bluetooth?.press(code) ?: throw IOException("Bluetooth desconectado") }
                } else if (alexaPaired && controlMode != ControlMode.ADB && NativeKeys.action(code) != null) {
                    val native = alexa ?: return@withLock
                    withContext(Dispatchers.IO) { if (valid()) native.pressKey(code) }
                } else if (code in listOf(24, 25, 164)) {
                    message = "Volume da TV precisa do controle Wi-Fi pareado e de compatibilidade HDMI-CEC. O volume interno do Fire Stick não controla esta TV."
                } else execute { if (valid()) client.shell("input keyevent $code") }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is AlexaUnauthorized) { alexaPaired = false; prefs.edit().remove("$deviceId.alexaToken").remove("$deviceId.alexaCertificate").apply(); message = "Refaça o pareamento do controle Wi-Fi na engrenagem." }
            else message = if (code in listOf(24, 25, 164)) "O Fire Stick não aceitou volume da TV por esta conexão. Verifique Anynet+; alguns modelos exigem infravermelho externo." else "Falha no controle $transportLabel. Reconecte ou selecione ADB na engrenagem."
        }
    }
    private val repeater = HeldKeyRepeater(viewModelScope, { controlsReady }) { code, valid -> sendKey(code, valid) }
    fun startDirection(code: Int) {
        stopDirection()
        if (controlMode != ControlMode.BLUETOOTH) { repeater.start(code); return }
        val hold = holdGeneration.get()
        viewModelScope.launch {
            controlMutex.withLock {
                if (holdGeneration.get() != hold || !bluetoothConnected) return@withLock
                if (Build.VERSION.SDK_INT >= 28) runCatching { withContext(Dispatchers.IO) { if (holdGeneration.get() == hold) bluetooth?.down(code) } }.onFailure { message = "Bluetooth desconectado. Reconecte na engrenagem." }
            }
        }
    }
    fun stopDirection() {
        repeater.stop(); holdGeneration.incrementAndGet()
        if (Build.VERSION.SDK_INT >= 28 && bluetooth != null) viewModelScope.launch {
            controlMutex.withLock { withContext(Dispatchers.IO) { runCatching { bluetooth?.release() } } }
        }
    }
    fun disconnect() {
        stopVoice()
        val oldVoice = voiceJob
        val oldNative = alexa; alexa = null; alexaPaired = false
        viewModelScope.launch(Dispatchers.IO) { oldVoice?.join(); oldNative?.dispose() }
        closeAlexaPairing()
        generation.incrementAndGet(); input.invalidate(); stopDirection(); client.close(); closeCatalog()
        connectJob?.cancel(); catalogJob?.cancel(); monitorJob?.cancel()
        state = Connection.OFFLINE; loadingApps = false; editingSlot = null
    }
    fun onForeground() { if (state == Connection.OFFLINE && RemoteCommands.validHost(host)) connect(); if (controlMode == ControlMode.BLUETOOTH && Build.VERSION.SDK_INT >= 28) { bluetoothAutoAttempted = false; startBluetooth() } }
    fun onBackground() { suspendBluetooth(); disconnect(); scanJob?.cancel(); scanning = false; message = "Desconectado. A conexão será retomada ao abrir o app." }
    fun requestAlexaPairing(activate: Boolean = false) {
        if (state != Connection.ONLINE) { message = "Conecte ao Fire Stick primeiro."; return }
        if (voiceBusy || pairingBusy) return
        stopDirection()
        pairingRequested = true; pairingBusy = true; pairingError = null
        val epoch = generation.get()
        val candidate = AlexaClient(host)
        pairingJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (activate) { candidate.activateRemote(); delay(1500) }
                    candidate.displayPin()
                }
                ensureCurrent(epoch)
                pairingClient?.dispose()
                pairingClient = candidate
                message = "Digite o código mostrado na TV para habilitar a Alexa."
            } catch (e: CancellationException) { candidate.dispose(); throw e }
            catch (_: Exception) { candidate.dispose(); pairingError = "Não foi possível mostrar o código. Confira o Fire Stick e tente novamente." }
            finally { pairingBusy = false }
        }
    }
    fun closeAlexaPairing() {
        pairingRequested = false; pairingJob?.cancel(); pairingBusy = false
        pairingClient?.dispose(); pairingClient = null
    }
    fun verifyAlexaPin(pin: String) {
        if (pairingBusy) return
        if (!Regex("[0-9]{4}").matches(pin)) { pairingError = "Digite os quatro dígitos mostrados na TV."; return }
        val native = pairingClient ?: return
        val epoch = generation.get()
        pairingBusy = true; pairingError = null
        pairingJob = viewModelScope.launch {
            try {
                val credentials = withContext(Dispatchers.IO) { native.verifyPin(pin) }
                ensureCurrent(epoch)
                if (credentials == null) { pairingError = "Código incorreto ou expirado. Confira a TV ou solicite outro."; return@launch }
                withContext(Dispatchers.IO) { native.validate() }
                ensureCurrent(epoch)
                prefs.edit().putString("$deviceId.alexaToken", credentials.token).putString("$deviceId.alexaCertificate", credentials.certificate).apply()
                alexa?.dispose(); alexa = native; pairingClient = null
                alexaPaired = true; pairingRequested = false
                message = "Controle Wi-Fi rápido e Alexa prontos. Segure o microfone para falar."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { pairingError = "Não foi possível concluir o pareamento. Solicite um novo código e tente novamente." }
            finally { pairingBusy = false }
        }
    }
    fun startVoice(): Boolean {
        if (state != Connection.ONLINE || !alexaPaired) return false
        if (voiceBusy) { message = "Aguarde o encerramento da gravação anterior."; return false }
        val native = alexa ?: return false
        val epoch = generation.get()
        stopDirection(); voiceActive = true; voiceBusy = true
        message = "Conectando à Alexa… continue segurando o microfone."
        voiceJob = viewModelScope.launch {
            try {
                AlexaCapture(appContext, native).run {
                    viewModelScope.launch { if (voiceActive && generation.get() == epoch) message = "Ouvindo… fale com a Alexa e solte para enviar." }
                }
                if (generation.get() == epoch) message = "Áudio enviado à Alexa."
            } catch (e: CancellationException) {
                if (generation.get() == epoch && state == Connection.ONLINE) message = "Escuta encerrada. Aguarde a resposta da Alexa."
            } catch (e: Exception) {
                if (generation.get() == epoch) {
                    if (e is AlexaUnauthorized) {
                        alexaPaired = false
                        prefs.edit().remove("$deviceId.alexaToken").remove("$deviceId.alexaCertificate").apply()
                    }
                    message = if (e is SecurityException) "Permita o uso do microfone nas configurações do celular." else "Falha ao falar com a Alexa. Confira a rede; se persistir, refaça o pareamento na engrenagem."
                }
            } finally { voiceActive = false; voiceBusy = false }
        }
        return true
    }
    fun stopVoice() { voiceActive = false; voiceJob?.cancel() }
    fun scan() {
        if (scanning) return
        scanJob = viewModelScope.launch {
            scanning = true; discovered = emptyList(); message = "Buscando ADB na rede Wi-Fi…"
            try {
                val cm = appContext.getSystemService(ConnectivityManager::class.java)
                val properties = cm.getLinkProperties(cm.activeNetwork)
                val address = properties?.linkAddresses?.firstOrNull { it.address is java.net.Inet4Address && it.prefixLength == 24 }?.address?.hostAddress
                if (address == null) { message = "Busca disponível em redes IPv4 /24. Informe o IP manualmente nesta rede."; return@launch }
                val prefix = address.substringBeforeLast('.')
                val gate = kotlinx.coroutines.sync.Semaphore(24)
                coroutineScope {
                    (1..254).map { i -> async(Dispatchers.IO) {
                        gate.acquire()
                        try {
                            ensureActive()
                            val ip = "$prefix.$i"
                            if (ip == address) return@async null
                            try { Socket().use { it.connect(InetSocketAddress(ip, 5555), 700) }; ip } catch (_: IOException) { null }
                        } finally { gate.release() }
                    } }.awaitAll().filterNotNull().also { discovered = it }
                }
                message = if (discovered.isEmpty()) "Nenhum ADB encontrado. Confira a rede, ative o ADB ou informe o IP." else "Selecione um endereço encontrado para conectar."
            } finally { scanning = false }
        }
    }
    fun editShortcut(index: Int) {
        stopDirection()
        if (state != Connection.ONLINE) { message = "Conecte ao Fire Stick para escolher um aplicativo."; return }
        editingSlot = index; loadApps()
    }
    private fun closeCatalog() { catalogGeneration.incrementAndGet(); catalogConnected = false; catalogClient.close() }
    private suspend fun <T> executeCatalog(
        onFailure: () -> Unit = { catalogError = "Não foi possível carregar os apps/ícones. O controle continua disponível." },
        block: () -> T
    ): T? {
        val epoch = generation.get(); val catalogEpoch = catalogGeneration.get()
        if (state != Connection.ONLINE) return null
        return try {
            withContext(Dispatchers.IO) { catalogMutex.withLock {
                ensureActive(); ensureCurrent(epoch)
                if (catalogGeneration.get() != catalogEpoch) throw CancellationException()
                if (!catalogConnected) {
                    catalogClient.connect(host) {}
                    if (catalogGeneration.get() != catalogEpoch) { catalogClient.close(); throw CancellationException() }
                    catalogConnected = true
                }
                block().also {
                    ensureCurrent(epoch)
                    if (catalogGeneration.get() != catalogEpoch) throw CancellationException()
                }
            } }.also {
                ensureCurrent(epoch)
                if (catalogGeneration.get() != catalogEpoch) throw CancellationException()
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (generation.get() == epoch && catalogGeneration.get() == catalogEpoch) {
                catalogConnected = false; catalogClient.close()
                onFailure()
            }
            null
        }
    }
    fun closePicker() { editingSlot = null; catalogJob?.cancel(); loadingApps = false; closeCatalog() }
    fun loadApps() {
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            loadingApps = true; catalogError = null
            try {
                val raw = executeCatalog {
                    listOf("android.intent.category.LEANBACK_LAUNCHER", "android.intent.category.LAUNCHER").joinToString("\n") { category ->
                        catalogClient.shell("cmd package query-activities --brief -a android.intent.action.MAIN -c $category")
                    }
                } ?: return@launch
                val nativeNames = if (alexaPaired) withContext(Dispatchers.IO) { runCatching { alexa?.appNames().orEmpty() }.getOrDefault(emptyMap()) } else emptyMap()
                val components = RemoteCommands.components(raw)
                if (components.isEmpty()) { catalogError = "Não foi possível consultar apps iniciáveis neste Fire OS. Tente atualizar a lista."; return@launch }
                val existing = (apps + shortcuts.filterNotNull()).associateBy { it.packageName }
                apps = components.map { (pkg, component) -> (existing[pkg]?.copy(component = component) ?: cachedApp(pkg, component)).let { it.copy(name = nativeNames[pkg] ?: it.name) } }.sortedBy { it.name.lowercase() }
            } finally { loadingApps = false }
        }
    }
    private fun cacheKey(pkg: String) = "${deviceId.hashCode()}.$pkg"
    private fun iconFile(pkg: String) = File(appContext.filesDir, "icons/${cacheKey(pkg)}.png")
    private fun cachedApp(pkg: String, component: String): TvApp {
        val name = prefs.getString("name:${cacheKey(pkg)}", pkg) ?: pkg
        val icon = runCatching { BitmapFactory.decodeFile(iconFile(pkg).absolutePath) }.getOrNull()
        return TvApp(pkg, component, name, icon)
    }
    @Suppress("DEPRECATION")
    private suspend fun metadata(app: TvApp): TvApp? {
        if (!RemoteCommands.validPackage(app.packageName)) return null
        val bytes = executeCatalog {
            val path = catalogClient.shell("pm path '${app.packageName}'").lineSequence().firstOrNull { it.startsWith("package:") }?.removePrefix("package:")?.trim() ?: return@executeCatalog null
            if (!path.startsWith("/data/app/") && !path.startsWith("/system/") && !path.startsWith("/product/")) return@executeCatalog null
            if (path.contains('\\') || path.contains('\'') || path.contains('\n')) return@executeCatalog null
            val size = catalogClient.shell("stat -c %s '$path'").trim().toLongOrNull() ?: return@executeCatalog null
            if (size !in 1..(24L * 1024 * 1024)) return@executeCatalog null
            catalogClient.service("exec:cat '$path'", 24 * 1024 * 1024)
        } ?: return null
        return withContext(Dispatchers.IO) {
            val file = File.createTempFile("metadata-", ".apk", appContext.cacheDir)
            try {
                file.writeBytes(bytes)
                val pm = appContext.packageManager
                val info = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: return@withContext null
                val ai = info.applicationInfo ?: return@withContext null
                ai.sourceDir = file.absolutePath; ai.publicSourceDir = file.absolutePath
                val name = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(app.packageName)
                val bitmap = runCatching {
                    val d = ai.loadIcon(pm)
                    Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { b -> d.setBounds(0, 0, 96, 96); d.draw(Canvas(b)) }
                }.getOrNull()
                runCatching {
                    prefs.edit().putString("name:${cacheKey(app.packageName)}", name).apply()
                    if (bitmap != null) {
                        val icon = iconFile(app.packageName); icon.parentFile?.mkdirs()
                        icon.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
                app.copy(name = name, icon = bitmap)
            } catch (_: Exception) { app }
            finally { file.delete() }
        }
    }
    fun choose(app: TvApp?) {
        val slot = editingSlot ?: return
        shortcuts = shortcuts.toMutableList().also { it[slot] = app }
        saveShortcuts(); closePicker(); message = if (app == null) "Atalho removido." else "Atalho salvo: ${app.name}"
        if (app != null && app.icon == null) {
            val selectedDevice = deviceId
            viewModelScope.launch {
                val enriched = metadata(app) ?: return@launch
                if (deviceId == selectedDevice && shortcuts[slot]?.packageName == app.packageName) {
                    shortcuts = shortcuts.toMutableList().also { it[slot] = enriched }
                    saveShortcuts()
                }
            }
        }
    }
    fun openShortcut(index: Int) {
        val app = shortcuts[index] ?: run { editShortcut(index); return }
        if (state != Connection.ONLINE) { message = "Conecte ao Fire Stick primeiro."; return }
        viewModelScope.launch {
            val result = executeCatalog(onFailure = { message = "Não foi possível abrir ${app.name}. O controle continua disponível. Tente novamente." }) {
                val path = catalogClient.shell("pm path '${app.packageName}'")
                if (!path.startsWith("package:")) "REMOVED" else catalogClient.shell(RemoteCommands.launch(app.component))
            } ?: return@launch
            if (result == "REMOVED" || result.contains("Error:") || result.contains("Exception")) {
                message = "${app.name} não está disponível. Escolha outro aplicativo."; editShortcut(index)
            }
        }
    }
    private fun readShortcuts(): List<TvApp?> = (0..3).map { index ->
        runCatching {
            val obj = JSONObject(prefs.getString("$deviceId.slot$index", "") ?: "")
            val pkg = obj.getString("package"); val component = obj.getString("component")
            if (!RemoteCommands.validPackage(pkg) || RemoteCommands.components(component)[pkg] != component) null
            else cachedApp(pkg, component).let { cached -> if (cached.name != pkg) cached else cached.copy(name = obj.optString("name", pkg)) }
        }.getOrNull()
    }
    private fun saveShortcuts() {
        val editor = prefs.edit()
        shortcuts.forEachIndexed { index, app ->
            val key = "$deviceId.slot$index"
            if (app == null) editor.remove(key)
            else editor.putString(key, JSONObject().put("package", app.packageName).put("component", app.component).put("name", app.name).toString())
        }
        editor.apply()
    }
    override fun onCleared() { stopVoice(); client.close(); catalogClient.close(); if (Build.VERSION.SDK_INT >= 28) bluetooth?.close(); super.onCleared() }
}
