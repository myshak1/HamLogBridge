package pl.hamlogbridge.net

/**
 * When the connection mode is "both", the exact same WSJT-X datagram can
 * arrive over Wi-Fi and Bluetooth within milliseconds of each other - the
 * payload is byte-for-byte identical on both transports. A short window
 * keyed on the raw bytes is enough to drop the second copy before it reaches
 * the decoder or the relay, without touching either of them.
 */
class PacketDeduper(private val windowMs: Long = 4000) {
    private val seenAt = LinkedHashMap<Int, Long>()

    @Synchronized
    fun isDuplicate(data: ByteArray, length: Int): Boolean {
        val now = System.currentTimeMillis()
        seenAt.entries.removeAll { now - it.value > windowMs }
        val key = contentKey(data, length)
        val duplicate = seenAt.containsKey(key)
        seenAt[key] = now
        return duplicate
    }

    private fun contentKey(data: ByteArray, length: Int): Int {
        var h = length
        for (i in 0 until length) h = 31 * h + data[i]
        return h
    }
}
