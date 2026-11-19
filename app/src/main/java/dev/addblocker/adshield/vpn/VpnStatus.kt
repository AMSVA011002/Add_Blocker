package dev.addblocker.adshield.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide view of the tunnel. The service writes, the UI observes.
 * If the process dies the service dies with it, so this cannot drift.
 */
object VpnStatus {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun setRunning(running: Boolean) {
        _running.value = running
    }

    fun setError(message: String?) {
        _error.value = message
    }
}
