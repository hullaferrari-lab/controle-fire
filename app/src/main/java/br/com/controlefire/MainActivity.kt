package br.com.controlefire

import android.os.Bundle
import android.Manifest
import android.os.Build
import android.content.Intent
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private val Ice = Color(0xFF65D4F1)
private val Panel = Color(0xFF22272E)
private val Ink = Color(0xFF0C1015)

class MainActivity : ComponentActivity() {
    private val model by viewModels<RemoteViewModel>()
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) model.onFocusLost()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Ice, background = Ink, surface = Panel, onPrimary = Ink)) {
                RemoteScreen(model)
            }
        }
    }
}

@Composable
private fun RemoteScreen(vm: RemoteViewModel) {
    var connectionDialog by remember { mutableStateOf(vm.host.isBlank()) }
    var explanation by remember { mutableStateOf<String?>(null) }
    var bluetoothDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.notice(if (granted) "Microfone autorizado. Segure o botão azul para falar com a Alexa." else "Permita o microfone para usar a Alexa. Se necessário, ative em Configurações do celular → Aplicativos → Controle Fire → Permissões.")
    }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.startBluetooth() }
    val discoverBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.startBluetooth(); vm.refreshBluetoothTargets() }
    val bluetoothPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.isNotEmpty() && grants.values.all { it }) enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        else vm.bluetoothNotice("Permita dispositivos próximos para usar o controle Bluetooth.")
    }
    val setupBluetooth: () -> Unit = {
        if (Build.VERSION.SDK_INT < 28) vm.startBluetooth()
        else if (Build.VERSION.SDK_INT >= 31 && (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED || context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)) {
            bluetoothPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
        } else {
            runCatching { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }.onFailure { vm.bluetoothNotice("Bluetooth indisponível neste aparelho.") }
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.onForeground()
            if (event == Lifecycle.Event.ON_STOP) vm.onBackground()
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.onForeground()
        onDispose { owner.lifecycle.removeObserver(observer); vm.stopDirection(); vm.stopVoice() }
    }
    val connected = vm.controlsReady
    val wifiConnected = vm.state == Connection.ONLINE
    val volumeInfo: () -> Unit = { explanation = "A TV Samsung PN43H4000AG não respondeu aos comandos de volume do Fire Stick nos testes. Alterar o volume interno do Fire não muda o som dessa TV. Bluetooth também não transmite infravermelho. Para controlar esses botões, use o controle da TV ou uma solução infravermelha compatível." }
    val unavailable: (String) -> Unit = { label -> explanation = "$label não está disponível nesta versão. Use os controles compatíveis com seu aparelho." }
    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().widthIn(max = 420.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CONTROLE FIRE", color = Ice, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                    Text(if (connected) vm.deviceName else "Seu controle, no celular", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = { vm.stopDirection(); connectionDialog = true }) { Icon(Icons.Default.Settings, contentDescription = "Configurar conexão") }
            }
            Row(Modifier.fillMaxWidth().widthIn(max = 420.dp).padding(top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(if (connected) Color(0xFF77DBA3) else Color(0xFFFFC777), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("${vm.transportLabel} • ${vm.message}", Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, color = Color(0xFFC3CBD3), fontSize = 12.sp, lineHeight = 16.sp)
            }
            Column(
                Modifier.widthIn(max = 328.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(48.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF242A31), Color(0xFF343940), Color(0xFF242A31))))
                    .border(1.dp, Color(0xFF454B52), RoundedCornerShape(48.dp)).padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.fillMaxWidth().height(76.dp)) {
                    Box(Modifier.align(Alignment.TopStart)) {
                        RemoteButton("Ligar ou desligar a TV", RemoteIcons.Power, muted = true, onClick = { unavailable("Ligar/desligar a TV") })
                    }
                    Icon(if (vm.controlMode == ControlMode.BLUETOOTH) RemoteIcons.Bluetooth else RemoteIcons.Wifi, contentDescription = vm.transportLabel, tint = if (connected) Ice else Color.Gray, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(18.dp))
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        AlexaButton(vm.voiceActive, wifiConnected, onPress = {
                            when {
                                !vm.alexaPaired -> { vm.requestAlexaPairing(); false }
                                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> { microphonePermission.launch(Manifest.permission.RECORD_AUDIO); false }
                                else -> vm.startVoice()
                            }
                        }, onRelease = vm::stopVoice)
                    }
                }
                Dpad(vm, connected)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    RemoteButton("Voltar", Icons.AutoMirrored.Filled.ArrowBack, active = connected, onClick = { vm.key(4) })
                    RemoteButton("Início", Icons.Default.Home, active = connected, onClick = { vm.key(3) })
                    RemoteButton("Menu", Icons.Default.Menu, active = connected, onClick = { vm.key(82) })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    RemoteButton("Retroceder", RemoteIcons.Rewind, active = connected, onClick = { vm.key(89) })
                    RemoteButton("Reproduzir ou pausar", RemoteIcons.PlayPause, active = connected, onClick = { vm.key(85) })
                    RemoteButton("Avançar", RemoteIcons.Forward, active = connected, onClick = { vm.key(90) })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    RemoteButton("Mudo da TV — integração indisponível", RemoteIcons.Mute, muted = true, onClick = volumeInfo)
                    Column(Modifier.clip(RoundedCornerShape(28.dp)).background(Ink).border(1.dp, Color(0xFF454B52), RoundedCornerShape(28.dp))) {
                        RemoteButton("Aumentar volume da TV — integração indisponível", Icons.Default.Add, muted = true, onClick = volumeInfo)
                        RemoteButton("Diminuir volume da TV — integração indisponível", RemoteIcons.Minus, muted = true, onClick = volumeInfo)
                    }
                    RemoteButton("TV ao vivo", RemoteIcons.Tv, muted = true, onClick = { unavailable("TV ao vivo") })
                }
                Spacer(Modifier.height(2.dp))
                (0..1).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        (0..1).forEach { col ->
                            val index = row * 2 + col
                            Shortcut(vm.shortcuts[index], index, Modifier.weight(1f), onClick = { vm.openShortcut(index) }, onEdit = { vm.editShortcut(index) })
                        }
                    }
                }
                Text("Segure um atalho para editar", color = Color(0xFFB5BEC8), fontSize = 10.sp)
            }
            Text("WI-FI • CONTROLE LOCAL", Modifier.padding(top = 14.dp, bottom = 8.dp), fontSize = 10.sp, letterSpacing = 2.sp, color = Color(0xFF8D99A7))
        }
    }
    if (connectionDialog) ConnectionDialog(vm, onBluetooth = { connectionDialog = false; bluetoothDialog = true }) { connectionDialog = false }
    if (bluetoothDialog) BluetoothDialog(vm, setupBluetooth, onVisible = {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) setupBluetooth()
        else runCatching { discoverBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)) }.onFailure { vm.bluetoothNotice("Não foi possível tornar o celular visível. Confira o Bluetooth.") }
    }) { bluetoothDialog = false }
    if (vm.editingSlot != null) AppPicker(vm)
    if (vm.pairingRequested) AlexaPairingDialog(vm)
    explanation?.let { text -> AlertDialog(onDismissRequest = { explanation = null }, title = { Text("Função indisponível") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { explanation = null }) { Text("Entendi") } }) }
}

@Composable
private fun RemoteButton(label: String, icon: ImageVector, active: Boolean = true, muted: Boolean = false, accent: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = active, shape = CircleShape, color = if (accent) Color(0xFF297C96) else Color(0xFF161B21),
        border = BorderStroke(1.dp, Color(0xFF48515C)), modifier = Modifier.size(48.dp).semantics { contentDescription = if (muted) "$label, indisponível nesta versão" else label }) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.alpha(if (muted || !active) .42f else 1f)) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(25.dp))
        }
    }
}

@Composable
private fun Dpad(vm: RemoteViewModel, connected: Boolean) {
    Box(Modifier.size(176.dp).background(Brush.radialGradient(listOf(Color(0xFF414952), Color(0xFF11161C))), CircleShape).border(1.dp, Color(0xFF59616B), CircleShape), contentAlignment = Alignment.Center) {
        Direction("Cima", Icons.Default.KeyboardArrowUp, 19, vm, connected, Modifier.align(Alignment.TopCenter))
        Direction("Baixo", Icons.Default.KeyboardArrowDown, 20, vm, connected, Modifier.align(Alignment.BottomCenter))
        Direction("Esquerda", Icons.Default.KeyboardArrowLeft, 21, vm, connected, Modifier.align(Alignment.CenterStart))
        Direction("Direita", Icons.Default.KeyboardArrowRight, 22, vm, connected, Modifier.align(Alignment.CenterEnd))
        Surface(onClick = { vm.key(23) }, enabled = connected, shape = CircleShape, color = Color(0xFF303740), border = BorderStroke(2.dp, Color(0xFF11161C)), modifier = Modifier.size(82.dp).semantics { contentDescription = "OK, selecionar" }) {
            Box(contentAlignment = Alignment.Center) { Text("OK", color = if (connected) Color.White else Color.Gray, fontSize = 17.sp, fontWeight = FontWeight.Medium) }
        }
    }
}

@Composable
private fun Direction(label: String, icon: ImageVector, code: Int, vm: RemoteViewModel, connected: Boolean, modifier: Modifier) {
    Box(modifier.size(52.dp).clip(CircleShape)
        .semantics { contentDescription = label; role = Role.Button; if (!connected) disabled(); onClick { if (connected) vm.key(code); connected } }
        .pointerInput(connected, code) {
            if (connected) awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                vm.startDirection(code)
                try { waitForUpOrCancellation() } finally { vm.stopDirection() }
            }
        }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (connected) Color(0xFFE8EDF3) else Color.Gray, modifier = Modifier.size(28.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Shortcut(app: TvApp?, index: Int, modifier: Modifier, onClick: () -> Unit, onEdit: () -> Unit) {
    Row(modifier.heightIn(min = 50.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF171E27)).border(1.dp, Color(0xFF526174), RoundedCornerShape(16.dp))
        .combinedClickable(onClick = onClick, onLongClick = onEdit, onLongClickLabel = "Trocar aplicativo")
        .semantics { contentDescription = "Atalho ${index + 1}: ${app?.name ?: "Escolher aplicativo"}" }.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppIcon(app, Modifier.size(24.dp))
        Text(app?.name ?: "Adicionar", Modifier.weight(1f), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2, color = Color(0xFFE0EAF5))
    }
}

@Composable
private fun AppIcon(app: TvApp?, modifier: Modifier) {
    if (app?.icon != null) Image(app.icon.asImageBitmap(), null, modifier.clip(RoundedCornerShape(6.dp)))
    else Icon(if (app == null) Icons.Default.Add else RemoteIcons.Apps, null, modifier, tint = Ice)
}

@Composable
private fun ConnectionDialog(vm: RemoteViewModel, onBluetooth: () -> Unit, onDismiss: () -> Unit) {
    val busy = vm.state == Connection.CONNECTING || vm.state == Connection.AUTHORIZING
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Conectar ao Fire Stick") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Transporte dos botões", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = vm.controlMode == ControlMode.AUTO, onClick = { vm.chooseControlMode(ControlMode.AUTO) }, label = { Text("Wi-Fi rápido", fontSize = 11.sp) })
                FilterChip(selected = vm.controlMode == ControlMode.ADB, onClick = { vm.chooseControlMode(ControlMode.ADB) }, label = { Text("ADB", fontSize = 11.sp) })
            }
            OutlinedButton(onClick = onBluetooth) { Icon(RemoteIcons.Bluetooth, null); Text("Configurar Bluetooth") }
            Text("Wi-Fi rápido usa o pareamento da Alexa. Bluetooth controla navegação; Alexa e atalhos continuam usando Wi-Fi.", fontSize = 12.sp)
            Text("1. Conecte celular e Fire Stick à mesma rede Wi-Fi.\n2. Ative a depuração ADB no Fire Stick.\n3. Ao conectar, autorize este celular na TV.", fontSize = 13.sp)
            OutlinedTextField(value = vm.host, onValueChange = vm::updateHost, label = { Text("Endereço IP do Fire Stick") }, placeholder = { Text("192.168.1.20") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !busy)
            Text("Veja o IP em Meu Fire TV → Sobre → Rede.", fontSize = 12.sp)
            OutlinedButton(onClick = { onDismiss(); vm.requestAlexaPairing() }, enabled = vm.state == Connection.ONLINE && !vm.voiceBusy) {
                Text(if (vm.alexaPaired) "Parear Wi-Fi rápido / Alexa novamente" else "Parear Wi-Fi rápido e Alexa")
            }
            Text("Volume e mudo não funcionaram na TV PN43H4000AG. Os botões mostram a limitação e a necessidade de controle infravermelho compatível.", fontSize = 11.sp)
            OutlinedButton(onClick = vm::scan, enabled = !vm.scanning && !busy) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text(if (vm.scanning) "Buscando…" else "Buscar na rede") }
            vm.discovered.forEach { ip -> TextButton(onClick = { vm.connect(ip) }, enabled = !busy) { Text("Conectar a $ip") } }
            Text(vm.message, fontSize = 12.sp, color = Ice, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = { vm.connect() }, enabled = !busy) { Text("Conectar") } }, dismissButton = {
        Row {
            if (busy || vm.state == Connection.ONLINE) TextButton(onClick = { vm.disconnect(); vm.notice("Desconectado.") }) { Text("Desconectar") }
            TextButton(onClick = onDismiss) { Text("Fechar") }
        }
    })
}

@Composable
private fun AppPicker(vm: RemoteViewModel) {
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = vm::closePicker, title = { Text("Escolher aplicativo") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text("Buscar no Fire Stick") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (vm.loadingApps) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp)); Text("Consultando apps, nomes e ícones…", fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp)) }
            vm.catalogError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            val filtered = vm.apps.filter { it.name.contains(query, true) || it.packageName.contains(query, true) }
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                items(filtered, key = { it.packageName }) { app ->
                    Row(Modifier.fillMaxWidth().clickable { vm.choose(app) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(app, Modifier.size(32.dp)); Spacer(Modifier.width(10.dp))
                        Column { Text(app.name, fontSize = 14.sp); if (app.name != app.packageName) Text(app.packageName, fontSize = 10.sp, color = Color.Gray) }
                    }
                }
                if (filtered.isEmpty() && !vm.loadingApps) item { Text("Nenhum aplicativo encontrado.", Modifier.padding(vertical = 16.dp)) }
            }
        }
    }, confirmButton = { TextButton(onClick = vm::closePicker) { Text("Fechar") } }, dismissButton = { Row {
        TextButton(onClick = vm::loadApps, enabled = !vm.loadingApps) { Text("Atualizar") }
        TextButton(onClick = { vm.choose(null) }) { Text("Limpar") }
    } })
}

@Composable
private fun AlexaButton(listening: Boolean, enabled: Boolean, onPress: () -> Boolean, onRelease: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape)
        .background(if (listening) Color(0xFFB13240) else Color(0xFF297C96))
        .border(1.dp, Ice, CircleShape)
        .semantics {
            contentDescription = if (listening) "Alexa ouvindo. Solte para encerrar." else "Alexa. Segure para falar."
            role = Role.Button
            if (!enabled) disabled()
            onClick(label = if (listening) "Encerrar escuta" else "Falar com Alexa") {
                if (enabled) {
                    if (listening) onRelease() else onPress()
                }
                enabled
            }
        }
        .pointerInput(enabled) {
            if (enabled) awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                val started = onPress()
                try { waitForUpOrCancellation() } finally { if (started) onRelease() }
            }
        }, contentAlignment = Alignment.Center) {
        Icon(RemoteIcons.Mic, null, tint = if (enabled) Color.White else Color.Gray, modifier = Modifier.size(25.dp))
    }
}

@Composable
private fun AlexaPairingDialog(vm: RemoteViewModel) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = vm::closeAlexaPairing, title = { Text("Parear Wi-Fi rápido e Alexa") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Digite o código que aparece na TV para “Controle Fire”. Esse pareamento é separado da autorização ADB.")
            OutlinedTextField(pin, { pin = it.filter { c -> c in '0'..'9' }.take(4) }, label = { Text("Código de quatro dígitos") }, singleLine = true, enabled = !vm.pairingBusy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            vm.pairingError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            if (vm.pairingBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Depois, permita o microfone do celular. Segure o botão azul, espere “Ouvindo”, fale e solte. A escuta termina em até 15 segundos.", fontSize = 12.sp)
            TextButton(onClick = { pin = ""; vm.requestAlexaPairing() }, enabled = !vm.pairingBusy) { Text("Solicitar outro código") }
            TextButton(onClick = { pin = ""; vm.requestAlexaPairing(activate = true) }, enabled = !vm.pairingBusy) { Text("Código não apareceu? Reativar na TV") }
        }
    }, confirmButton = { TextButton(onClick = { vm.verifyAlexaPin(pin) }, enabled = !vm.pairingBusy && pin.length == 4) { Text("Parear") } }, dismissButton = { TextButton(onClick = vm::closeAlexaPairing) { Text("Cancelar") } })
}

@Composable
private fun BluetoothDialog(vm: RemoteViewModel, onSetup: () -> Unit, onVisible: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Controle Bluetooth") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("O celular funciona como um teclado/controle Bluetooth HID. Exige Android 9 ou superior e suporte do fabricante.", fontSize = 12.sp)
            Text("1. Ative o controle neste app.\n2. Torne o celular visível.\n3. No Fire Stick: Configurações → Controles e dispositivos Bluetooth → Outros dispositivos Bluetooth → Adicionar. Selecione seu Galaxy Note20 Ultra.\n4. Mantenha este app aberto. Ao conectar, os botões passam a usar Bluetooth automaticamente.", fontSize = 13.sp)
            OutlinedButton(onClick = onSetup) { Text("Ativar controle Bluetooth") }
            OutlinedButton(onClick = onVisible, enabled = vm.bluetoothRegistered) { Text("Tornar celular visível por 2 minutos") }
            Text(vm.bluetoothMessage, color = Ice, fontSize = 12.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = vm::refreshBluetoothTargets) { Text("Atualizar dispositivos pareados") }
            vm.bluetoothTargets.forEach { target ->
                TextButton(onClick = { vm.connectBluetooth(target.address) }, enabled = vm.bluetoothRegistered) { Text("Conectar: ${target.name}") }
            }
            Text("Bluetooth reduz a dependência do Wi-Fi para os botões, mas não leva a voz da Alexa nem abre os atalhos de apps. Essas funções precisam do Wi-Fi conectado. Volume da TV continua dependente da integração da TV.", fontSize = 11.sp)
            TextButton(onClick = vm::pauseBluetooth) { Text("Desconectar Bluetooth") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }, dismissButton = { TextButton(onClick = { vm.chooseControlMode(ControlMode.AUTO); onDismiss() }) { Text("Usar Wi-Fi") } })
}
