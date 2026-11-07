package br.com.controlefire

internal object NativeKeys {
    fun action(code: Int): String? = when (code) {
        19 -> "dpad_up"; 20 -> "dpad_down"; 21 -> "dpad_left"; 22 -> "dpad_right"; 23 -> "select"
        3 -> "home"; 4 -> "back"; 82 -> "menu"; 85 -> "play"
        24 -> "volume_up"; 25 -> "volume_down"; 164 -> "volume_mute"
        else -> null
    }
}
