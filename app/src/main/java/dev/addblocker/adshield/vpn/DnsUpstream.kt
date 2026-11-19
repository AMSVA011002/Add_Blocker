package dev.addblocker.adshield.vpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Forwards a query to a real resolver over a *protected* socket, so the reply
 * travels over the phone's normal connection instead of looping back into the
 * tunnel. A fresh socket per query keeps the code simple and stateless; DNS
 * volume on a phone is low and the cache absorbs the repetitive lookups.
 */
class DnsUpstream(
    private val server: InetAddress,
    private val protectSocket: (DatagramSocket) -> Boolean
) {

    fun resolve(query: ByteArray, timeoutMs: Int = 4_500): ByteArray? {
        var socket: DatagramSocket? = null
        try {
            val candidate = DatagramSocket()
            socket = candidate
            if (!protectSocket(candidate)) return null
            candidate.soTimeout = timeoutMs
            candidate.connect(server, 53)
            candidate.send(DatagramPacket(query, query.size))

            val buffer = ByteArray(4096)
            val response = DatagramPacket(buffer, buffer.size)
            candidate.receive(response)

            val data = buffer.copyOf(response.length)
            if (!looksLikeAnswerFor(query, data)) return null
            return data
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun looksLikeAnswerFor(query: ByteArray, response: ByteArray): Boolean {
        if (response.size < 12 || query.size < 2) return false
        if (u16(response, 0) != u16(query, 0)) return false
        return (u16(response, 2) and 0x8000) != 0
    }
}
