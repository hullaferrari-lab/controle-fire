package br.com.controlefire

/** USB HID keyboard and Consumer Control reports, shared by the Android Bluetooth transport. */
internal object HidReports {
    val descriptor = ("05 01 09 06 A1 01 85 01 05 07 19 E0 29 E7 15 00 25 01 75 01 95 08 81 02 " +
        "95 01 75 08 81 01 95 06 75 08 15 00 25 65 05 07 19 00 29 65 81 00 C0 " +
        "05 0C 09 01 A1 01 85 02 15 00 26 FF 03 19 00 2A FF 03 75 10 95 01 81 00 C0")
        .split(' ').map { it.toInt(16).toByte() }.toByteArray()
    data class Report(val id: Int, val bytes: ByteArray)
    fun forKey(code: Int): Report {
        val keyboard = mapOf(19 to 0x52, 20 to 0x51, 21 to 0x50, 22 to 0x4F, 23 to 0x28, 82 to 0x65)[code]
        if (keyboard != null) return Report(1, byteArrayOf(0, 0, keyboard.toByte(), 0, 0, 0, 0, 0))
        val consumer = mapOf(3 to 0x223, 4 to 0x224, 85 to 0xCD, 89 to 0xB4, 90 to 0xB3, 24 to 0xE9, 25 to 0xEA, 164 to 0xE2)[code]
            ?: throw IllegalArgumentException("Tecla não suportada por Bluetooth")
        return Report(2, byteArrayOf(consumer.toByte(), (consumer shr 8).toByte()))
    }
    fun released(id: Int): ByteArray = when (id) { 1 -> ByteArray(8); 2 -> ByteArray(2); else -> throw IllegalArgumentException("Relatório HID inválido") }
}
