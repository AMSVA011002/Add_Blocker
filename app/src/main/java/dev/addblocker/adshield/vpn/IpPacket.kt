package dev.addblocker.adshield.vpn

class UdpDatagram(
    val srcAddress: ByteArray,
    val dstAddress: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray
)

/**
 * Just enough IPv4/UDP to read DNS queries off the tunnel and to write the
 * answers back. Non-first fragments and non-UDP packets are ignored: the only
 * thing routed into this tunnel is traffic for the virtual DNS server.
 */
object IpPacket {

    private const val IPV4_HEADER_MIN = 20
    private const val UDP_HEADER = 8
    private const val PROTOCOL_UDP = 17

    fun parseUdp(packet: ByteArray): UdpDatagram? {
        val length = packet.size
        if (length < IPV4_HEADER_MIN + UDP_HEADER) return null

        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return null

        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (headerLength < IPV4_HEADER_MIN) return null

        val totalLength = u16(packet, 2)
        if (totalLength < headerLength + UDP_HEADER || totalLength > length) return null

        val fragment = u16(packet, 6)
        if ((fragment and 0x1FFF) != 0) return null

        if ((packet[9].toInt() and 0xFF) != PROTOCOL_UDP) return null

        val udpLength = u16(packet, headerLength + 4)
        if (udpLength < UDP_HEADER) return null

        val payloadLength = udpLength - UDP_HEADER
        val payloadStart = headerLength + UDP_HEADER
        if (payloadStart + payloadLength > length) return null

        return UdpDatagram(
            srcAddress = packet.copyOfRange(12, 16),
            dstAddress = packet.copyOfRange(16, 20),
            srcPort = u16(packet, headerLength),
            dstPort = u16(packet, headerLength + 2),
            payload = packet.copyOfRange(payloadStart, payloadStart + payloadLength)
        )
    }

    fun buildUdp(
        srcAddress: ByteArray,
        srcPort: Int,
        dstAddress: ByteArray,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        require(srcAddress.size == 4 && dstAddress.size == 4) { "IPv4 only" }

        val udpLength = UDP_HEADER + payload.size
        val totalLength = IPV4_HEADER_MIN + udpLength
        val out = ByteArray(totalLength)

        out[0] = 0x45                       // IPv4, 20 byte header
        out[1] = 0                          // DSCP/ECN
        putU16(out, 2, totalLength)
        putU16(out, 4, 0)                   // identification
        putU16(out, 6, 0x4000)              // don't fragment
        out[8] = 64                         // ttl
        out[9] = PROTOCOL_UDP.toByte()
        System.arraycopy(srcAddress, 0, out, 12, 4)
        System.arraycopy(dstAddress, 0, out, 16, 4)
        putU16(out, 10, 0)
        putU16(out, 10, internetChecksum(out, 0, IPV4_HEADER_MIN))

        putU16(out, 20, srcPort)
        putU16(out, 22, dstPort)
        putU16(out, 24, udpLength)
        putU16(out, 26, 0)                  // checksum placeholder
        System.arraycopy(payload, 0, out, 28, payload.size)

        var sum = addWords(0L, srcAddress, 0, 4)
        sum = addWords(sum, dstAddress, 0, 4)
        sum += PROTOCOL_UDP.toLong()
        sum += udpLength.toLong()
        sum = addWords(sum, out, 20, udpLength)

        var checksum = foldChecksum(sum).inv() and 0xFFFF
        if (checksum == 0) checksum = 0xFFFF
        putU16(out, 26, checksum)

        return out
    }
}
