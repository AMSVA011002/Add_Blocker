package dev.addblocker.adshield.vpn

import dev.addblocker.adshield.data.BlockResponse

/**
 * A parsed DNS question. [raw] holds the exact bytes that arrived from the
 * tunnel so we can forward them upstream untouched.
 */
class DnsQuery(
    val id: Int,
    val flags: Int,
    val name: String,
    val qType: Int,
    val questionEnd: Int,
    val raw: ByteArray
)

/**
 * Minimal, allocation-conscious DNS wire format helper. Only what a filtering
 * forwarder needs: parse the question, synthesise block/error answers, read the
 * TTL of a real answer for caching.
 */
object Dns {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val RCODE_NOERROR = 0
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3

    fun parseQuery(packet: ByteArray): DnsQuery? {
        val length = packet.size
        if (length < 12) return null

        val flags = u16(packet, 2)
        if (flags and 0x8000 != 0) return null          // a response, not a query
        val questionCount = u16(packet, 4)
        if (questionCount < 1) return null

        val nameResult = readName(packet, 12) ?: return null
        val offset = nameResult.second
        if (offset + 4 > length) return null

        val qType = u16(packet, offset)
        val qClass = u16(packet, offset + 2)
        if (qClass != 1 && qClass != 255) return null

        return DnsQuery(
            id = u16(packet, 0),
            flags = flags,
            name = nameResult.first,
            qType = qType,
            questionEnd = offset + 4,
            raw = packet
        )
    }

    /** NXDOMAIN or null-address answer for a blocked name. */
    fun buildBlockedResponse(query: DnsQuery, mode: BlockResponse): ByteArray {
        val rcode = if (mode == BlockResponse.NXDOMAIN) RCODE_NXDOMAIN else RCODE_NOERROR
        val answers = if (mode == BlockResponse.NULL_IP) {
            when (query.qType) {
                TYPE_A -> listOf(resourceRecord(TYPE_A, ByteArray(4)))
                TYPE_AAAA -> listOf(resourceRecord(TYPE_AAAA, ByteArray(16)))
                else -> emptyList()
            }
        } else {
            emptyList()
        }
        return buildResponse(query, rcode, answers)
    }

    /** Used when the upstream resolver is unreachable, so clients fail fast. */
    fun buildErrorResponse(query: DnsQuery, rcode: Int = RCODE_SERVFAIL): ByteArray =
        buildResponse(query, rcode, emptyList())

    fun withTransactionId(packet: ByteArray, id: Int): ByteArray {
        if (packet.size < 2) return packet
        val copy = packet.copyOf()
        putU16(copy, 0, id)
        return copy
    }

    fun rcode(packet: ByteArray): Int = if (packet.size < 4) -1 else u16(packet, 2) and 0x0F

    fun typeLabel(qType: Int): String = when (qType) {
        TYPE_A -> "A"
        TYPE_AAAA -> "AAAA"
        5 -> "CNAME"
        12 -> "PTR"
        16 -> "TXT"
        33 -> "SRV"
        65 -> "HTTPS"
        else -> "T$qType"
    }

    /** Smallest TTL across the answer section, or 0 when it cannot be read. */
    fun minTtl(packet: ByteArray): Int {
        val length = packet.size
        if (length < 12) return 0
        val questionCount = u16(packet, 4)
        val answerCount = u16(packet, 6)
        if (answerCount == 0) return 0

        var pos = 12
        var index = 0
        while (index < questionCount) {
            val question = readName(packet, pos) ?: return 0
            pos = question.second + 4
            if (pos > length) return 0
            index++
        }

        var min = Int.MAX_VALUE
        index = 0
        while (index < answerCount) {
            val owner = readName(packet, pos) ?: return 0
            pos = owner.second
            if (pos + 10 > length) return 0
            val ttl = u32(packet, pos + 4)
            val rdLength = u16(packet, pos + 8)
            pos += 10 + rdLength
            if (pos > length) return 0
            if (ttl > 0 && ttl < min) min = ttl
            index++
        }
        return if (min == Int.MAX_VALUE) 0 else min
    }

    // ------------------------------------------------------------------ helpers

    private fun buildResponse(query: DnsQuery, rcode: Int, answers: List<ByteArray>): ByteArray {
        val questionLength = query.questionEnd - 12
        var size = 12 + questionLength
        for (answer in answers) size += answer.size

        val out = ByteArray(size)
        putU16(out, 0, query.id)

        var flags = 0x8000 or 0x0080 or (rcode and 0x0F)      // QR + RA + RCODE
        flags = flags or (query.flags and 0x0100)              // copy RD
        flags = flags or (query.flags and 0x7800)              // copy opcode
        putU16(out, 2, flags)
        putU16(out, 4, 1)
        putU16(out, 6, answers.size)
        putU16(out, 8, 0)
        putU16(out, 10, 0)

        System.arraycopy(query.raw, 12, out, 12, questionLength)
        var position = 12 + questionLength
        for (answer in answers) {
            System.arraycopy(answer, 0, out, position, answer.size)
            position += answer.size
        }
        return out
    }

    private fun resourceRecord(type: Int, rdata: ByteArray): ByteArray {
        val out = ByteArray(12 + rdata.size)
        putU16(out, 0, 0xC00C)          // compression pointer to the question name
        putU16(out, 2, type)
        putU16(out, 4, 1)               // class IN
        putU32(out, 6, 60)              // ttl
        putU16(out, 10, rdata.size)
        System.arraycopy(rdata, 0, out, 12, rdata.size)
        return out
    }

    private fun readName(buffer: ByteArray, start: Int): Pair<String, Int>? {
        val length = buffer.size
        val builder = StringBuilder()
        var position = start
        var afterPointer = -1
        var jumps = 0

        while (true) {
            if (position >= length) return null
            val labelLength = buffer[position].toInt() and 0xFF

            when {
                labelLength == 0 -> {
                    position++
                    val end = if (afterPointer >= 0) afterPointer else position
                    if (builder.isEmpty()) return null
                    return builder.toString() to end
                }

                (labelLength and 0xC0) == 0xC0 -> {
                    if (position + 1 >= length) return null
                    val pointer = ((labelLength and 0x3F) shl 8) or
                        (buffer[position + 1].toInt() and 0xFF)
                    if (afterPointer < 0) afterPointer = position + 2
                    if (++jumps > 16) return null
                    position = pointer
                }

                (labelLength and 0xC0) != 0 -> return null

                else -> {
                    position++
                    if (position + labelLength > length) return null
                    if (builder.isNotEmpty()) builder.append('.')
                    var i = 0
                    while (i < labelLength) {
                        val code = buffer[position + i].toInt() and 0xFF
                        builder.append(if (code in 33..126) code.toChar().lowercaseChar() else '?')
                        i++
                    }
                    position += labelLength
                    if (builder.length > 255) return null
                }
            }
        }
    }
}
