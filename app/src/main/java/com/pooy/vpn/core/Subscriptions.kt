package com.pooy.vpn.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTP(S) fetcher (no third-party HTTP library). Cleartext http:// is blocked by the manifest/OS policy. */
object Subscriptions {
    private const val MAX_BYTES = 5_000_000

    fun fetch(url: String): String {
        val u = URL(url.trim())
        if (u.protocol != "https" && u.protocol != "http") throw IOException("Only http(s) URLs are supported")
        val c = u.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "POOOY/1.0")
            val code = c.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            return c.inputStream.use { readLimited(it, MAX_BYTES) }
        } finally {
            c.disconnect()
        }
    }

    fun readLimited(s: InputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw IOException("Content too large")
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }
}
