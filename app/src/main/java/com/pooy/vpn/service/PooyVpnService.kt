package com.pooy.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.pooy.vpn.MainActivity
import com.pooy.vpn.R
import com.pooy.vpn.core.ConfigParser
import com.pooy.vpn.core.XrayConfigBuilder
import com.pooy.vpn.core.XrayCore
import com.pooy.vpn.data.ProfileStore
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Data path:  apps -> TUN (VpnService) -> hev-socks5-tunnel -> 127.0.0.1 SOCKS5 inbound (Xray) -> outbound -> server
 * POOOY itself is excluded from the VPN, so Xray's own sockets reach the server directly (no loop, no protect() needed).
 * Nothing runs until the user connects; everything is torn down on disconnect.
 */
class PooyVpnService : VpnService() {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var tun: ParcelFileDescriptor? = null
    private var tunnelStarted = false

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                goForeground(notification(getString(R.string.notif_connecting)))
                val id = intent.getStringExtra(EXTRA_PROFILE_ID)
                VpnController.connecting(null)
                worker.execute { startVpn(id) }
            }
            ACTION_STOP -> worker.execute { stopVpn() }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // User switched VPN off in system settings, or another VPN app took over.
        worker.execute { stopVpn() }
    }

    override fun onDestroy() {
        teardown()
        VpnController.disconnected()
        worker.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ lifecycle

    private fun startVpn(profileId: String?) {
        try {
            teardown()
            val data = ProfileStore(applicationContext).load()
            val profile = data.profiles.firstOrNull { it.id == (profileId ?: data.activeId) }
                ?: throw IllegalStateException(getString(R.string.err_no_profile))
            VpnController.connecting(profile.name)

            val server = ConfigParser.parse(profile.raw)
            val port = freePort()
            val user = token()
            val pass = token()

            XrayCore.init(applicationContext)
            XrayCore.start(XrayConfigBuilder.runtime(server, port, user, pass))

            val pfd = buildTun() ?: throw IllegalStateException(getString(R.string.err_vpn_permission))
            synchronized(lock) { tun = pfd }

            val cfg = File(cacheDir, TUN_CONFIG)
            cfg.writeText(tunYaml(port, user, pass))
            TProxyService.TProxyStartService(cfg.absolutePath, pfd.fd)
            tunnelStarted = true

            if (!XrayCore.isRunning) throw IllegalStateException(getString(R.string.err_core_stopped))

            VpnController.connected(profile.name)
            postNotification(notification(getString(R.string.notif_connected, profile.name)))
        } catch (t: Throwable) {
            Log.e(TAG, "VPN start failed", t)
            teardown()
            VpnController.failed(friendly(t))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopVpn() {
        teardown()
        VpnController.disconnected()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Idempotent. */
    private fun teardown() {
        synchronized(lock) {
            if (tunnelStarted) {
                try {
                    TProxyService.TProxyStopService()
                } catch (ignored: Throwable) {
                    // native lib already gone
                }
                tunnelStarted = false
            }
            try {
                XrayCore.stop()
            } catch (ignored: Throwable) {
                // core missing or already stopped
            }
            try {
                tun?.close()
            } catch (ignored: IOException) {
                // ignore
            }
            tun = null
            File(cacheDir, TUN_CONFIG).delete()
        }
    }

    // ------------------------------------------------------------------ tunnel

    private fun buildTun(): ParcelFileDescriptor? {
        val b = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            .addAddress(TUN_V4, 30)
            .addAddress(TUN_V6, 126)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)          // route IPv6 into the tunnel too, so it cannot leak around the VPN
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .addDnsServer("2606:4700:4700::1111")
        try {
            b.addDisallowedApplication(packageName)   // our own (Xray) sockets bypass the VPN
        } catch (ignored: PackageManager.NameNotFoundException) {
            // cannot happen for our own package
        }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        return b.establish()
    }

    private fun tunYaml(port: Int, user: String, pass: String): String = """
        tunnel:
          mtu: $MTU
          ipv4: $TUN_V4
          ipv6: '$TUN_V6'
        socks5:
          port: $port
          address: 127.0.0.1
          udp: 'udp'
          username: '$user'
          password: '$pass'
        misc:
          task-stack-size: 20480
          connect-timeout: 5000
          read-write-timeout: 60000
          log-file: stderr
          log-level: error
    """.trimIndent() + "\n"

    private fun freePort(): Int =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun token(): String {
        val b = ByteArray(12)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun friendly(t: Throwable): String = when (t) {
        is NoClassDefFoundError, is ClassNotFoundException -> getString(R.string.err_core_missing)
        is UnsatisfiedLinkError -> getString(R.string.err_native_missing)
        else -> t.message ?: t.javaClass.simpleName
    }

    // ------------------------------------------------------------------ notification

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PooyVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notification),
            getString(R.string.notif_action_disconnect),
            stop,
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(action)
            .build()
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun postNotification(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    companion object {
        const val ACTION_START = "com.pooy.vpn.START"
        const val ACTION_STOP = "com.pooy.vpn.STOP"
        const val EXTRA_PROFILE_ID = "profile_id"

        private const val TAG = "PooyVpnService"
        private const val CHANNEL_ID = "vpn"
        private const val NOTIF_ID = 1
        private const val TUN_CONFIG = "tun.yaml"
        private const val MTU = 1500
        private const val TUN_V4 = "10.10.14.1"
        private const val TUN_V6 = "fc00::1"
    }
}
