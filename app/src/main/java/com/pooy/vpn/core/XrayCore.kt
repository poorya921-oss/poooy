package com.pooy.vpn.core

import android.content.Context
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.lang.reflect.InvocationTargetException
import java.util.UUID

/**
 * The ONLY file that touches the Xray AAR (libv2ray, from 2dust/AndroidLibXrayLite).
 * Targets the CoreController API (Libv2ray.newCoreController / initCoreEnv / measureOutboundDelay).
 * If your AAR exposes a different API, adapt this file only.
 */
object XrayCore {

    @Volatile private var controller: CoreController? = null
    private var envReady = false

    private val handler = object : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(code: Long, message: String?): Long = 0
    }

    val isRunning: Boolean
        get() = try {
            controller?.isRunning == true
        } catch (ignored: Exception) {
            false
        }

    @Synchronized
    fun init(context: Context) {
        if (envReady) return
        val prefs = context.applicationContext.getSharedPreferences("core", Context.MODE_PRIVATE)
        val key = prefs.getString("xudp_key", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("xudp_key", it).apply() }
        Libv2ray.initCoreEnv(context.applicationContext.filesDir.absolutePath, key)
        envReady = true
    }

    @Synchronized
    fun start(configJson: String) {
        stop()
        val c = Libv2ray.newCoreController(handler)
        startLoop(c, configJson)
        controller = c
    }

    @Synchronized
    fun stop() {
        val c = controller ?: return
        controller = null
        try {
            c.stopLoop()
        } catch (ignored: Exception) {
            // already stopped
        }
    }

    /** Bytes since the previous call (the core resets the counter on read). tag = "proxy", direction = "uplink" | "downlink". */
    fun queryTraffic(tag: String, direction: String): Long =
        try {
            controller?.queryStats(tag, direction) ?: 0L
        } catch (ignored: Exception) {
            0L
        }

    /** Round-trip time in ms through the given outbound config, or throws. Blocking. */
    fun measureDelay(configJson: String, url: String): Long = Libv2ray.measureOutboundDelay(configJson, url)

    /**
     * Some AndroidLibXrayLite releases expose startLoop(config) and newer ones startLoop(config, tunFd).
     * We always run a SOCKS inbound and our own tun2socks, so pass fd 0 ("no tun") if a second argument exists.
     */
    private fun startLoop(c: CoreController, config: String) {
        val m = CoreController::class.java.methods.firstOrNull { it.name == "startLoop" }
            ?: throw IllegalStateException("CoreController.startLoop not found in libv2ray.aar")
        try {
            if (m.parameterTypes.size >= 2) m.invoke(c, config, 0) else m.invoke(c, config)
        } catch (e: InvocationTargetException) {
            throw (e.targetException ?: e)
        }
    }
}
