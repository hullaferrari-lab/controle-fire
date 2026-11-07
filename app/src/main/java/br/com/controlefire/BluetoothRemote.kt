package br.com.controlefire

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.concurrent.Executors

internal data class BluetoothTarget(val address: String, val name: String)

@RequiresApi(28)
@SuppressLint("MissingPermission") // Every entry point checks CONNECT before using protected APIs.
internal class BluetoothRemote(private val context: Context, private val changed: (Boolean, Boolean, String) -> Unit) : AutoCloseable {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var service: BluetoothHidDevice? = null
    @Volatile private var device: BluetoothDevice? = null
    @Volatile private var registered = false
    @Volatile private var closed = false
    @Volatile private var activeRequested = false
    private var pendingProxy = false
    @Volatile private var registering = false
    private val reports = HidReportState()
    fun permitted() = Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private fun notify(message: String) { main.post { if (!closed) changed(registered, device != null, message) } }
    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            registering = false
            if (isRegistered && !activeRequested) {
                if (permitted()) service?.unregisterApp()
                synchronized(reports) { registered = false; device = null; reports.reset() }; return
            }
            registered = isRegistered
            if (!isRegistered) { synchronized(reports) { device = null; reports.reset() }; notify("Bluetooth desativado pelo sistema. Mantenha o app aberto e ative novamente.") }
            else notify("Controle Bluetooth pronto. Procure este celular no Fire Stick.")
        }
        override fun onConnectionStateChanged(target: BluetoothDevice, state: Int) {
            if (!permitted()) { synchronized(reports) { device = null; registered = false; reports.reset() }; notify("Permissão Bluetooth revogada."); return }
            if (state == BluetoothProfile.STATE_CONNECTED) {
                synchronized(reports) {
                    if (!activeRequested) {
                        reports.reset()
                        service?.disconnect(target)
                        return
                    }
                    device = target
                    // A new host session must never inherit a held key from the old one.
                    reports.reset().forEach { report -> runCatching { service?.sendReport(target, report.id, report.bytes) } }
                }
                notify("Bluetooth conectado a ${if (permitted()) target.name ?: "Fire Stick" else "Fire Stick"}.")
            } else if (state == BluetoothProfile.STATE_DISCONNECTED && device?.address == target.address) {
                synchronized(reports) { device = null; reports.reset() }; notify("Bluetooth desconectado. Reconecte em Configurações.")
            }
        }
        override fun onGetReport(target: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            synchronized(reports) {
                if (!permitted()) { reports.reset(); return }
                val index = id.toInt()
                if (type == BluetoothHidDevice.REPORT_TYPE_INPUT && index in 1..2) service?.replyReport(target, type, id, reports.getReport(index))
                else service?.reportError(target, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
            }
        }
    }
    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            pendingProxy = false
            if (closed || !permitted()) { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy); return }
            service = proxy as BluetoothHidDevice
            register()
        }
        override fun onServiceDisconnected(profile: Int) { synchronized(reports) { service = null; device = null; registered = false; reports.reset() }; pendingProxy = false; notify("Serviço Bluetooth indisponível. Tente ativar novamente.") }
    }
    fun start() {
        activeRequested = true
        if (!permitted()) { notify("Permita dispositivos próximos para conectar por Bluetooth."); return }
        if (adapter == null || !adapter.isEnabled) { notify("Ative o Bluetooth do celular."); return }
        if (service != null) { register(); return }
        if (!pendingProxy) {
            pendingProxy = adapter.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE)
            if (!pendingProxy) notify("Este celular não disponibilizou o perfil de controle Bluetooth HID.")
            else main.postDelayed({ if (!closed && pendingProxy && service == null) { pendingProxy = false; notify("O perfil Bluetooth HID não respondeu neste aparelho. Use Wi-Fi ou tente novamente.") } }, 10000)
        }
    }
    private fun register() {
        if (registered || registering || closed || !activeRequested || !permitted()) return
        val sdp = BluetoothHidDeviceAppSdpSettings("Controle Fire", "Controle remoto Fire TV", "Controle Fire", BluetoothHidDevice.SUBCLASS1_KEYBOARD, HidReports.descriptor)
        val accepted = service?.registerApp(sdp, null, null, executor, callback) == true
        registering = accepted
        if (accepted) main.postDelayed({ if (!closed && !registered && registering) { registering = false; notify("O Android não confirmou o registro HID. Tente novamente; o suporte depende do celular.") } }, 10000)
        if (!accepted) notify("Não foi possível registrar o controle Bluetooth. Feche outros apps de teclado/controle Bluetooth e tente novamente.")
    }
    fun connectedTarget(): BluetoothTarget? {
        if (!permitted()) return null
        return device?.let { BluetoothTarget(it.address, it.name ?: "Fire Stick") }
    }
    fun pairedTargets(): List<BluetoothTarget> {
        if (!permitted()) return emptyList()
        return adapter?.bondedDevices.orEmpty().map { BluetoothTarget(it.address, it.name ?: "Dispositivo Bluetooth") }.sortedBy { it.name }
    }
    fun connect(address: String) {
        if (!permitted() || !registered) { notify("Ative o controle Bluetooth primeiro."); return }
        val target = adapter?.bondedDevices?.firstOrNull { it.address == address } ?: run { notify("Pareie este celular pelo menu Bluetooth do Fire Stick primeiro."); return }
        if (service?.connect(target) != true) notify("O Fire Stick não aceitou a conexão Bluetooth.")
        else notify("Conectando por Bluetooth…")
    }
    fun down(code: Int) = synchronized(reports) {
        if (!permitted()) { reports.reset(); throw IOException("Permissão Bluetooth revogada") }
        val target = device ?: run { reports.reset(); throw IOException("Conecte o Bluetooth primeiro") }
        val report = HidReports.forKey(code)
        if (service?.sendReport(target, report.id, report.bytes) != true) throw IOException("Não foi possível enviar a tecla por Bluetooth")
        reports.pressed(report)
    }
    fun release() = synchronized(reports) {
        val neutral = reports.reset()
        if (!permitted()) return@synchronized
        val target = device ?: return@synchronized
        neutral.forEach { report -> service?.sendReport(target, report.id, report.bytes) }
    }
    suspend fun press(code: Int) { try { down(code); delay(20) } finally { release() } }
    fun pause() {
        activeRequested = false
        synchronized(reports) {
            // Clear local state before checking permission or touching the radio.
            runCatching { release() }
            if (permitted()) runCatching { device?.let { service?.disconnect(it) }; service?.unregisterApp() }
            device = null; registered = false; registering = false
        }
    }
    override fun close() {
        closed = true; pause()
        runCatching { service?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } }
        service = null; executor.shutdown()
    }
}
