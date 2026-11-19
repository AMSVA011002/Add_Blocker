package dev.addblocker.adshield.vpn

internal fun u16(data: ByteArray, offset: Int): Int =
    ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

internal fun u32(data: ByteArray, offset: Int): Int =
    ((data[offset].toInt() and 0xFF) shl 24) or
        ((data[offset + 1].toInt() and 0xFF) shl 16) or
        ((data[offset + 2].toInt() and 0xFF) shl 8) or
        (data[offset + 3].toInt() and 0xFF)

internal fun putU16(data: ByteArray, offset: Int, value: Int) {
    data[offset] = ((value shr 8) and 0xFF).toByte()
    data[offset + 1] = (value and 0xFF).toByte()
}

internal fun putU32(data: ByteArray, offset: Int, value: Int) {
    data[offset] = ((value ushr 24) and 0xFF).toByte()
    data[offset + 1] = ((value ushr 16) and 0xFF).toByte()
    data[offset + 2] = ((value ushr 8) and 0xFF).toByte()
    data[offset + 3] = (value and 0xFF).toByte()
}

/** Adds big-endian 16-bit words of [data] to [initial] (no folding yet). */
internal fun addWords(initial: Long, data: ByteArray, offset: Int, length: Int): Long {
    var sum = initial
    var index = offset
    val end = offset + length
    while (index + 1 < end) {
        sum += (((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF)).toLong()
        index += 2
    }
    if (index < end) {
        sum += ((data[index].toInt() and 0xFF) shl 8).toLong()
    }
    return sum
}

internal fun foldChecksum(sumIn: Long): Int {
    var sum = sumIn
    while ((sum ushr 16) != 0L) {
        sum = (sum and 0xFFFF) + (sum ushr 16)
    }
    return sum.toInt() and 0xFFFF
}

/** Standard internet checksum, already complemented and ready to store. */
internal fun internetChecksum(data: ByteArray, offset: Int, length: Int): Int {
    val sum = foldChecksum(addWords(0L, data, offset, length))
    return sum.inv() and 0xFFFF
}
