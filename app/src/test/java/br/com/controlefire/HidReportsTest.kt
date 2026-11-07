package br.com.controlefire

import org.junit.Assert.*
import org.junit.Test

class HidReportsTest {
    @Test fun arrowAndSelectUseStandardKeyboardReports() {
        val up = HidReports.forKey(19)
        assertEquals(1, up.id)
        assertArrayEquals(byteArrayOf(0,0,0x52,0,0,0,0,0), up.bytes)
        assertEquals(0x28, HidReports.forKey(23).bytes[2].toInt())
        assertArrayEquals(ByteArray(8), HidReports.released(up.id))
        assertThrows(IllegalArgumentException::class.java) { HidReports.forKey(-1) }
    }
    @Test fun mediaAndSystemControlsUseLittleEndianConsumerReports() {
        assertEquals(2, HidReports.forKey(4).id)
        assertArrayEquals(byteArrayOf(0x24,0x02), HidReports.forKey(4).bytes)
        assertArrayEquals(byteArrayOf(0x23,0x02), HidReports.forKey(3).bytes)
        assertArrayEquals(byteArrayOf(0xCD.toByte(),0), HidReports.forKey(85).bytes)
        assertArrayEquals(ByteArray(2), HidReports.released(2))
    }
    @Test fun descriptorAdvertisesTheExactReportLengthsSentToBluetooth() {
        val data = HidReports.descriptor
        val bits = mutableMapOf<Int,Int>()
        var id=0; var count=0; var size=0; var offset=0
        while (offset < data.size) {
            val tag=data[offset++].toInt() and 255
            val length=when(tag and 3) { 3 -> 4; else -> tag and 3 }
            var value=0
            repeat(length) { i -> value = value or ((data[offset++].toInt() and 255) shl (8*i)) }
            when(tag and 0xFC) {
                0x84 -> id=value
                0x74 -> size=value
                0x94 -> count=value
                0x80 -> bits[id]=(bits[id] ?: 0)+size*count
            }
        }
        assertEquals(mapOf(1 to 64, 2 to 16),bits)
        assertEquals(bits.getValue(1)/8,HidReports.forKey(19).bytes.size)
        assertEquals(bits.getValue(2)/8,HidReports.forKey(85).bytes.size)
    }
}
