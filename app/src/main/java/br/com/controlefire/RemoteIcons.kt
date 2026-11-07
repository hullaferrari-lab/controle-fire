package br.com.controlefire

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Small local icon set keeps the remote APK free of a full extended icon library. */
internal object RemoteIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.White)).build()
    val Power = icon("Power", "M11,2 L13,2 L13,12 L11,12 Z M7,4 L8,6 C2,10 5,20 12,20 C19,20 22,10 16,6 L17,4 C25,9 21,22 12,22 C3,22 -1,9 7,4 Z")
    val Mic = icon("Microfone", "M9,3 C9,0 15,0 15,3 L15,11 C15,15 9,15 9,11 Z M6,10 L8,10 C8,17 16,17 16,10 L18,10 C18,14 16,17 13,18 L13,21 L17,21 L17,23 L7,23 L7,21 L11,21 L11,18 C8,17 6,14 6,10 Z")
    val Bluetooth = icon("Bluetooth", "M11,1 L20,8 L15,12 L20,16 L11,23 L11,14 L5,20 L3,18 L9,12 L3,6 L5,4 L11,10 Z M13,5 L13,10 L16,8 Z M13,14 L13,19 L16,16 Z")
    val Wifi = icon("Wi-Fi", "M1,7 C7,1 17,1 23,7 L21,9 C16,4 8,4 3,9 Z M5,11 C9,7 15,7 19,11 L17,13 C14,10 10,10 7,13 Z M9,16 C11,14 13,14 15,16 L12,20 Z")
    val Rewind = icon("Retroceder", "M11,5 L1,12 L11,19 Z M22,5 L12,12 L22,19 Z")
    val Forward = icon("Avançar", "M2,5 L12,12 L2,19 Z M13,5 L23,12 L13,19 Z")
    val PlayPause = icon("Reproduzir e pausar", "M2,5 L11,12 L2,19 Z M13,5 L16,5 L16,19 L13,19 Z M19,5 L22,5 L22,19 L19,19 Z")
    val Mute = icon("Mudo", "M2,9 L6,9 L11,4 L11,20 L6,15 L2,15 Z M15,7 L18,10 L21,7 L23,9 L20,12 L23,15 L21,17 L18,14 L15,17 L13,15 L16,12 L13,9 Z")
    val Minus = icon("Menos", "M5,11 L19,11 L19,13 L5,13 Z")
    val Tv = icon("TV", "M7,1 L12,6 L17,1 L19,3 L15,7 L22,7 L22,22 L2,22 L2,7 L9,7 L5,3 Z M4,9 L4,20 L20,20 L20,9 Z")
    val Apps = icon("Aplicativo", "M3,3 L10,3 L10,10 L3,10 Z M14,3 L21,3 L21,10 L14,10 Z M3,14 L10,14 L10,21 L3,21 Z M14,14 L21,14 L21,21 L14,21 Z")
}
