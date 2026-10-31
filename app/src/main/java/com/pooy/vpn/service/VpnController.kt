package com.pooy.vpn.service

import android.os.SystemClock
import com.pooy.vpn.core.XrayCore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Process-wide VPN state shared between the service (writer) and the UI (reader). */
object VpnController {
    enum class State { Disconnected, Connecting, Connected }

    data class Status(
        val state: State = State.Disconnected,
        val serverName: String? = null,
        val connectedAtMs: Long = 0L,   // SystemClock.elapsedRealtime() when connected
        val error: String? = null,
    )

    data class Traffic(
        val up: Long = 0,
        val down: Long = 0,
        val upSpeed: Long = 0,     // bytes/s over the last poll interval
        val downSpeed: Long = 0,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val _traffic = MutableStateFlow(Traffic())
    val traffic: StateFlow<Traffic> = _traffic

    @Volatile private var lastPoll = 0L

    fun connecting(serverName: String?) {
        _traffic.value = Traffic()
        _status.value = Status(State.Connecting, serverName)
    }

    fun connected(serverName: String?) {
        lastPoll = SystemClock.elapsedRealtime()
        _status.value = Status(State.Connected, serverName, lastPoll)
    }

    fun disconnected() {
        _status.update { Status(State.Disconnected, null, 0L, it.error) }
    }

    fun failed(message: String) {
        _status.value = Status(State.Disconnected, null, 0L, message)
    }

    fun clearError() {
        _status.update { it.copy(error = null) }
    }

    /**
     * Pulls new byte counts from the core. Only called by the UI while it is visible,
     * so there is no background polling; the core keeps counting in between.
     */
    fun pollTraffic() {
        if (_status.value.state != State.Connected || !XrayCore.isRunning) return
        val now = SystemClock.elapsedRealtime()
        val up = XrayCore.queryTraffic("proxy", "uplink")
        val down = XrayCore.queryTraffic("proxy", "downlink")
        val dt = (now - lastPoll).coerceAtLeast(1L)
        lastPoll = now
        _traffic.update { Traffic(it.up + up, it.down + down, up * 1000 / dt, down * 1000 / dt) }
    }
}
