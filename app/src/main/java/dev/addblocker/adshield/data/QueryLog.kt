package dev.addblocker.adshield.data

data class LogEntry(
    val id: Long,
    val time: Long,
    val domain: String,
    val blocked: Boolean,
    val qType: String
)

/**
 * Bounded in-memory ring of recent lookups. Deliberately not persisted: nothing
 * about your browsing leaves the device, not even to this app's own disk.
 */
object QueryLog {

    private const val CAPACITY = 600

    private val lock = Any()
    private val entries = ArrayDeque<LogEntry>()
    private var sequence = 0L

    @Volatile
    var version: Long = 0L
        private set

    fun add(domain: String, blocked: Boolean, qType: String) {
        if (!Prefs.logQueries) return
        synchronized(lock) {
            sequence++
            entries.addFirst(LogEntry(sequence, System.currentTimeMillis(), domain, blocked, qType))
            while (entries.size > CAPACITY) {
                entries.removeLast()
            }
            version++
        }
    }

    /** Newest first. */
    fun snapshot(): List<LogEntry> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            version++
        }
    }
}
