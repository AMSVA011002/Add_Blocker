package dev.addblocker.adshield.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Today's counters. The DNS worker threads call [record] for every query, so the
 * hot path is synchronized but cheap: the exposed StateFlows are throttled to
 * ~4 updates/second to keep Compose from recomposing hundreds of times a second.
 */
object StatsRepository {

    private const val EMIT_INTERVAL_MS = 250L
    private const val FLUSH_INTERVAL_MS = 5_000L

    private val lock = Any()

    private var queriesToday = 0L
    private var blockedToday = 0L

    private var lastEmit = 0L
    private var lastFlush = 0L
    private var dirty = false
    private var dayKey = ""

    private val _queries = MutableStateFlow(0L)
    val queries: StateFlow<Long> = _queries.asStateFlow()

    private val _blocked = MutableStateFlow(0L)
    val blocked: StateFlow<Long> = _blocked.asStateFlow()

    @Volatile
    private var initialized = false

    fun init() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            initialized = true
            dayKey = Prefs.todayKey()
            val stored = Prefs.dailyStats()
            queriesToday = stored.first
            blockedToday = stored.second
            _queries.value = queriesToday
            _blocked.value = blockedToday
            lastEmit = System.currentTimeMillis()
            lastFlush = lastEmit
            dirty = false
        }
    }

    fun record(blocked: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (dayKey.isEmpty()) dayKey = Prefs.todayKey()
            queriesToday++
            if (blocked) blockedToday++
            dirty = true

            if (now - lastEmit >= EMIT_INTERVAL_MS) {
                lastEmit = now
                _queries.value = queriesToday
                _blocked.value = blockedToday
            }
            if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                lastFlush = now
                rolloverIfNeeded(now)
                flushLocked()
            }
        }
    }

    /** Push the latest counters to disk immediately (service stop, activity pause). */
    fun flush() {
        synchronized(lock) {
            rolloverIfNeeded(System.currentTimeMillis())
            flushLocked()
            _queries.value = queriesToday
            _blocked.value = blockedToday
        }
    }

    fun resetToday() {
        synchronized(lock) {
            queriesToday = 0L
            blockedToday = 0L
            dayKey = Prefs.todayKey()
            Prefs.resetDailyStats()
            dirty = false
            _queries.value = 0L
            _blocked.value = 0L
        }
    }

    private fun rolloverIfNeeded(now: Long) {
        val today = Prefs.todayKey()
        if (today == dayKey) return
        // New day: start the counters from zero.
        dayKey = today
        queriesToday = 0L
        blockedToday = 0L
        dirty = true
        lastEmit = now
        _queries.value = 0L
        _blocked.value = 0L
    }

    private fun flushLocked() {
        if (!dirty) return
        Prefs.saveDailyStats(queriesToday, blockedToday)
        dirty = false
    }
}
