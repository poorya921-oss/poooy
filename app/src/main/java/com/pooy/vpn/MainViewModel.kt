package com.pooy.vpn

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pooy.vpn.core.ConfigException
import com.pooy.vpn.core.ConfigParser
import com.pooy.vpn.core.Importer
import com.pooy.vpn.core.Subscriptions
import com.pooy.vpn.core.XrayConfigBuilder
import com.pooy.vpn.core.XrayCore
import com.pooy.vpn.data.Profile
import com.pooy.vpn.data.ProfileStore
import com.pooy.vpn.data.StoreData
import com.pooy.vpn.data.Subscription
import com.pooy.vpn.service.PooyVpnService
import com.pooy.vpn.service.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ProfileStore(app)
    private val mutex = Mutex()
    private val testGate = Semaphore(3)

    var data by mutableStateOf(StoreData())
        private set
    var loaded by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set

    /** profile id -> latency in ms (-1 = failed). Absent = not tested. */
    val latency = mutableStateMapOf<String, Long>()
    val testing = mutableStateMapOf<String, Boolean>()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages

    init {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock { data = store.load() }
            loaded = true
        }
    }

    fun say(text: String) {
        _messages.tryEmit(text)
    }

    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private suspend fun update(block: (StoreData) -> StoreData) {
        mutex.withLock {
            val next = block(data)
            try {
                withContext(Dispatchers.IO) { store.save(next) }
                data = next
            } catch (e: Exception) {
                say(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ------------------------------------------------------------------ import

    /** Clipboard / QR text: a lone http(s) URL is treated as a subscription, anything else as links or JSON. */
    fun importAuto(text: String?) {
        if (text.isNullOrBlank()) {
            say(str(R.string.msg_clipboard_empty))
            return
        }
        if (ConfigParser.isSubscriptionUrl(text)) importUrl(text.trim(), "", true) else importText(text)
    }

    private fun importText(text: String) {
        viewModelScope.launch(Dispatchers.IO) { addImported(Importer.fromText(text)) }
    }

    private suspend fun addImported(r: Importer.Result) {
        if (r.profiles.isEmpty()) {
            say(str(R.string.msg_nothing_imported, r.skipped))
            return
        }
        update { d -> d.copy(profiles = d.profiles + r.profiles, activeId = d.activeId ?: r.profiles.first().id) }
        say(
            if (r.skipped > 0) str(R.string.msg_imported_skipped, r.profiles.size, r.skipped)
            else str(R.string.msg_imported, r.profiles.size),
        )
    }

    fun importUrl(url: String, name: String, asSubscription: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            busy = true
            try {
                val body = Subscriptions.fetch(url)
                if (asSubscription) {
                    val sub = Subscription(Importer.newId(), name.ifBlank { hostOf(url) }, url.trim())
                    val r = Importer.fromText(body, sub.id)
                    if (r.profiles.isEmpty()) {
                        say(str(R.string.msg_nothing_imported, r.skipped))
                        return@launch
                    }
                    update { d ->
                        d.copy(
                            subscriptions = d.subscriptions + sub,
                            profiles = d.profiles + r.profiles,
                            activeId = d.activeId ?: r.profiles.first().id,
                        )
                    }
                    say(str(R.string.msg_imported, r.profiles.size))
                } else {
                    addImported(Importer.fromText(body))
                }
            } catch (e: Exception) {
                say(str(R.string.err_fetch, e.message ?: e.javaClass.simpleName))
            } finally {
                busy = false
            }
        }
    }

    fun importFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.use { Subscriptions.readLimited(it, 2_000_000) }
                    ?: throw IOException("cannot open file")
                addImported(Importer.fromText(text))
            } catch (e: Exception) {
                say(str(R.string.msg_file_error, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun addManual(name: String, raw: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val trimmed = raw.trim()
            val parsed = try {
                ConfigParser.parse(trimmed)
            } catch (e: ConfigException) {
                say(str(R.string.msg_invalid_config, e.message ?: ""))
                return@launch
            }
            val p = Profile(Importer.newId(), name.ifBlank { parsed.name }, trimmed)
            update { d -> d.copy(profiles = d.profiles + p, activeId = d.activeId ?: p.id) }
        }
    }

    private fun hostOf(url: String): String =
        try {
            URI(url.trim()).host ?: url
        } catch (e: Exception) {
            url
        }

    // ------------------------------------------------------------------ subscriptions

    private suspend fun refresh(sub: Subscription) {
        val body = Subscriptions.fetch(sub.url)
        val r = Importer.fromText(body, sub.id)
        if (r.profiles.isEmpty()) throw IOException(str(R.string.err_sub_empty))
        update { d ->
            val oldActive = d.profiles.firstOrNull { it.id == d.activeId && it.subId == sub.id }
            val newActive = when {
                oldActive != null ->
                    (r.profiles.firstOrNull { it.raw == oldActive.raw }
                        ?: r.profiles.firstOrNull { it.name == oldActive.name }
                        ?: r.profiles.first()).id
                d.activeId == null -> r.profiles.first().id
                else -> d.activeId
            }
            d.copy(profiles = d.profiles.filter { it.subId != sub.id } + r.profiles, activeId = newActive)
        }
    }

    fun updateSubscription(id: String) {
        val sub = data.subscriptions.firstOrNull { it.id == id } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            busy = true
            try {
                refresh(sub)
                say(str(R.string.msg_sub_updated))
            } catch (e: Exception) {
                say(str(R.string.err_fetch, e.message ?: e.javaClass.simpleName))
            } finally {
                busy = false
            }
        }
    }

    fun updateAllSubscriptions() {
        val subs = data.subscriptions
        if (subs.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            busy = true
            var ok = 0
            var failed = 0
            try {
                for (s in subs) {
                    try {
                        refresh(s)
                        ok++
                    } catch (e: Exception) {
                        failed++
                    }
                }
            } finally {
                busy = false
            }
            say(str(R.string.msg_subs_updated, ok, failed))
        }
    }

    fun deleteSubscription(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            update { d ->
                val remaining = d.profiles.filter { it.subId != id }
                d.copy(
                    subscriptions = d.subscriptions.filter { it.id != id },
                    profiles = remaining,
                    activeId = if (remaining.any { it.id == d.activeId }) d.activeId else remaining.firstOrNull()?.id,
                )
            }
        }
    }

    // ------------------------------------------------------------------ profile management

    fun select(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            update { d -> d.copy(activeId = id) }
            // Connected or connecting: switch the live tunnel to the newly selected server.
            if (VpnController.status.value.state != VpnController.State.Disconnected) {
                val app = getApplication<Application>()
                app.startService(
                    Intent(app, PooyVpnService::class.java)
                        .setAction(PooyVpnService.ACTION_START)
                        .putExtra(PooyVpnService.EXTRA_PROFILE_ID, id),
                )
            }
        }
    }

    fun rename(id: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            update { d -> d.copy(profiles = d.profiles.map { if (it.id == id) it.copy(name = name.trim()) else it }) }
        }
    }

    fun edit(id: String, name: String, raw: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val trimmed = raw.trim()
            val parsed = try {
                ConfigParser.parse(trimmed)
            } catch (e: ConfigException) {
                say(str(R.string.msg_invalid_config, e.message ?: ""))
                return@launch
            }
            update { d ->
                d.copy(profiles = d.profiles.map {
                    if (it.id == id) it.copy(name = name.ifBlank { parsed.name }, raw = trimmed) else it
                })
            }
            latency.remove(id)
        }
    }

    fun duplicate(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            update { d ->
                val i = d.profiles.indexOfFirst { it.id == id }
                if (i < 0) return@update d
                val src = d.profiles[i]
                // The copy is detached from any subscription so a subscription refresh does not delete it.
                val copy = Profile(Importer.newId(), src.name + " (copy)", src.raw, null)
                d.copy(profiles = d.profiles.toMutableList().apply { add(i + 1, copy) })
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            update { d ->
                val remaining = d.profiles.filter { it.id != id }
                d.copy(
                    profiles = remaining,
                    activeId = if (d.activeId == id) remaining.firstOrNull()?.id else d.activeId,
                )
            }
            latency.remove(id)
        }
    }

    // ------------------------------------------------------------------ latency

    fun test(id: String) {
        viewModelScope.launch(Dispatchers.IO) { runTest(id) }
    }

    fun testAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val ids = data.profiles.map { it.id }
            coroutineScope { ids.map { async { runTest(it) } }.awaitAll() }
        }
    }

    private suspend fun runTest(id: String) {
        val p = data.profiles.firstOrNull { it.id == id } ?: return
        testing[id] = true
        try {
            testGate.withPermit {
                val ms = try {
                    val server = ConfigParser.parse(p.raw)
                    XrayCore.init(getApplication<Application>())
                    XrayCore.measureDelay(XrayConfigBuilder.speedTest(server), XrayConfigBuilder.TEST_URL)
                } catch (e: Throwable) {
                    -1L
                }
                latency[id] = if (ms >= 1) ms else -1L
            }
        } finally {
            testing.remove(id)
        }
    }
}
