package com.pooy.vpn.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64

class ConfigException(message: String) : Exception(message)

/** A parsed server: display info plus a ready-to-use Xray outbound (tag = "proxy"). */
class ParsedServer(
    val protocol: String,
    val host: String,
    val port: Int,
    val name: String,
    val outbound: JSONObject,
)

/**
 * Converts share links / JSON into Xray outbound objects.
 * Pure Kotlin + org.json + java.util.Base64, so it can be unit-tested on the JVM.
 */
object ConfigParser {

    private val LINK_SCHEMES = setOf("vless", "vmess", "ss", "trojan", "socks", "socks5", "http")
    private val JSON_PROTOCOLS = setOf("vless", "vmess", "shadowsocks", "trojan", "socks", "http")

    // ------------------------------------------------------------------ public API

    fun parse(raw: String): ParsedServer {
        val t = raw.trim()
        if (t.startsWith("{")) return parseJson(t)
        return when (schemeOf(t)) {
            "vless" -> vless(t)
            "vmess" -> vmess(t)
            "ss" -> shadowsocks(t)
            "trojan" -> trojan(t)
            "socks", "socks5" -> socks(t)
            "http" -> httpProxy(t)
            else -> throw ConfigException("Unsupported or malformed link")
        }
    }

    /** True if [line] looks like one of the supported proxy share links. */
    fun isProxyLink(line: String): Boolean {
        val t = line.trim()
        val scheme = schemeOf(t)
        if (scheme !in LINK_SCHEMES) return false
        return if (scheme == "http") isHttpProxyLink(t) else true
    }

    /**
     * True for a single-line http(s) URL that should be treated as a subscription URL.
     * An http:// link is an HTTP *proxy* instead when it has credentials (user:pass@host)
     * or the form http://host:port#name.
     */
    fun isSubscriptionUrl(text: String): Boolean {
        val t = text.trim()
        if (t.contains('\n') || t.contains(' ')) return false
        val s = schemeOf(t)
        return (s == "https" || s == "http") && !isHttpProxyLink(t)
    }

    /** Lenient Base64 (standard or URL-safe alphabet, padded or not). Throws IllegalArgumentException. */
    fun decodeBase64(input: String): ByteArray {
        val s = input.filter { !it.isWhitespace() }.trimEnd('=').replace('-', '+').replace('_', '/')
        if (s.length % 4 == 1) throw IllegalArgumentException("Invalid Base64 length")
        return Base64.getDecoder().decode(s + "=".repeat((4 - s.length % 4) % 4))
    }

    // ------------------------------------------------------------------ link plumbing

    private class Link(
        val scheme: String,
        val userInfo: String?,
        val host: String,
        val port: Int,
        val path: String,
        val query: Map<String, String>,
        val fragment: String?,
    ) {
        fun q(key: String): String? = query[key]?.takeIf { it.isNotEmpty() }
    }

    private fun schemeOf(t: String): String {
        val i = t.indexOf("://")
        if (i <= 0 || i > 10) return ""
        return t.substring(0, i).lowercase()
    }

    private fun urlDecode(s: String): String =
        try {
            URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (ignored: IllegalArgumentException) {
            s
        }

    private fun hostPort(hp: String): Pair<String, Int> {
        val host: String
        val portStr: String
        if (hp.startsWith("[")) {
            val e = hp.indexOf(']')
            if (e < 0) throw ConfigException("Invalid IPv6 address")
            host = hp.substring(1, e)
            portStr = hp.substring(e + 1).removePrefix(":")
        } else {
            val c = hp.lastIndexOf(':')
            if (c < 0) throw ConfigException("Missing port")
            host = hp.substring(0, c)
            portStr = hp.substring(c + 1)
        }
        val port = portStr.trim().toIntOrNull() ?: throw ConfigException("Invalid port")
        if (host.isEmpty() || port !in 1..65535) throw ConfigException("Invalid server address")
        return host to port
    }

    private fun parseQuery(q: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        if (q.isEmpty()) return m
        for (part in q.split('&')) {
            if (part.isEmpty()) continue
            val e = part.indexOf('=')
            val k = if (e >= 0) part.substring(0, e) else part
            val v = if (e >= 0) part.substring(e + 1) else ""
            m[urlDecode(k).lowercase()] = urlDecode(v)   // keys are stored lowercase
        }
        return m
    }

    private fun splitLink(s: String): Link {
        val si = s.indexOf("://")
        if (si <= 0) throw ConfigException("Malformed link")
        val scheme = s.substring(0, si).lowercase()
        var rest = s.substring(si + 3)

        var fragment: String? = null
        var i = rest.indexOf('#')
        if (i >= 0) {
            fragment = urlDecode(rest.substring(i + 1))
            rest = rest.substring(0, i)
        }
        var queryStr = ""
        i = rest.indexOf('?')
        if (i >= 0) {
            queryStr = rest.substring(i + 1)
            rest = rest.substring(0, i)
        }
        var path = ""
        i = rest.indexOf('/')
        if (i >= 0) {
            path = rest.substring(i)
            rest = rest.substring(0, i)
        }
        val at = rest.lastIndexOf('@')
        val userInfo = if (at >= 0) rest.substring(0, at) else null
        val (host, port) = hostPort(rest.substring(at + 1))
        return Link(scheme, userInfo, host, port, path, parseQuery(queryStr), fragment)
    }

    private fun isHttpProxyLink(t: String): Boolean {
        if (schemeOf(t) != "http") return false
        val l = try {
            splitLink(t)
        } catch (ignored: ConfigException) {
            return false
        }
        return l.userInfo != null || (l.path.isEmpty() && l.fragment != null)
    }

    private fun nameOf(fragment: String?, host: String, port: Int): String =
        fragment?.takeIf { it.isNotBlank() } ?: "$host:$port"

    private fun b64Text(s: String): String =
        try {
            String(decodeBase64(s), Charsets.UTF_8)
        } catch (ignored: IllegalArgumentException) {
            throw ConfigException("Invalid Base64 data in link")
        }

    // ------------------------------------------------------------------ outbound builders

    private fun vnextOutbound(protocol: String, host: String, port: Int, user: JSONObject, stream: JSONObject?): JSONObject {
        val server = JSONObject()
            .put("address", host)
            .put("port", port)
            .put("users", JSONArray().put(user))
        val ob = JSONObject()
            .put("tag", "proxy")
            .put("protocol", protocol)
            .put("settings", JSONObject().put("vnext", JSONArray().put(server)))
        if (stream != null) ob.put("streamSettings", stream)
        return ob
    }

    private fun serversOutbound(protocol: String, server: JSONObject, stream: JSONObject?): JSONObject {
        val ob = JSONObject()
            .put("tag", "proxy")
            .put("protocol", protocol)
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
        if (stream != null) ob.put("streamSettings", stream)
        return ob
    }

    /**
     * Builds Xray streamSettings from share-link style parameters.
     * Keys of [q] are lowercase: type, security, sni, host, path, fp, alpn, pbk, sid, spx,
     * headertype, servicename, mode, authority, extra, seed, allowinsecure.
     */
    private fun streamSettings(q: Map<String, String>, defaultSecurity: String): JSONObject {
        fun g(k: String): String? = q[k]?.takeIf { it.isNotEmpty() }

        val type = (g("type") ?: "tcp").lowercase()
        val network = when (type) {
            "tcp", "raw" -> "tcp"
            "ws", "websocket" -> "ws"
            "grpc", "gun" -> "grpc"
            "h2", "http" -> "h2"
            "httpupgrade" -> "httpupgrade"
            "xhttp", "splithttp" -> "xhttp"
            "kcp", "mkcp" -> "kcp"
            else -> throw ConfigException("Unsupported transport: $type")
        }
        val ss = JSONObject().put("network", network)
        val host = g("host")
        val path = g("path")

        when (network) {
            "tcp" -> {
                if ((g("headertype") ?: "none").lowercase() == "http") {
                    val headers = JSONObject()
                    if (host != null) headers.put("Host", JSONArray(host.split(',')))
                    val req = JSONObject()
                        .put("version", "1.1")
                        .put("method", "GET")
                        .put("path", JSONArray((path ?: "/").split(',')))
                        .put("headers", headers)
                    val header = JSONObject().put("type", "http").put("request", req)
                    ss.put("tcpSettings", JSONObject().put("header", header))
                }
            }
            "ws" -> {
                val ws = JSONObject().put("path", path ?: "/")
                if (host != null) ws.put("headers", JSONObject().put("Host", host))
                ss.put("wsSettings", ws)
            }
            "grpc" -> {
                val gr = JSONObject()
                    .put("serviceName", g("servicename") ?: path ?: "")
                    .put("multiMode", g("mode") == "multi")
                g("authority")?.let { gr.put("authority", it) }
                ss.put("grpcSettings", gr)
            }
            "httpupgrade" -> {
                val hu = JSONObject().put("path", path ?: "/")
                if (host != null) hu.put("host", host)
                ss.put("httpupgradeSettings", hu)
            }
            "xhttp" -> {
                val x = JSONObject().put("path", path ?: "/")
                if (host != null) x.put("host", host)
                g("mode")?.let { x.put("mode", it) }
                g("extra")?.let {
                    try {
                        x.put("extra", JSONObject(it))
                    } catch (ignored: JSONException) {
                        // ignore malformed "extra"
                    }
                }
                ss.put("xhttpSettings", x)
            }
            "kcp" -> {
                val k = JSONObject().put("header", JSONObject().put("type", g("headertype") ?: "none"))
                g("seed")?.let { k.put("seed", it) }
                ss.put("kcpSettings", k)
            }
            "h2" -> {
                val h = JSONObject().put("path", path ?: "/")
                if (host != null) h.put("host", JSONArray(host.split(',')))
                ss.put("httpSettings", h)
            }
        }

        val security = (g("security") ?: defaultSecurity).lowercase()
        val sni = g("sni") ?: g("peer") ?: host
        val insecure = g("allowinsecure") == "1" || g("allowinsecure") == "true" || g("insecure") == "1"
        when (security) {
            "tls" -> {
                ss.put("security", "tls")
                val t = JSONObject()
                if (sni != null) t.put("serverName", sni)
                g("fp")?.let { t.put("fingerprint", it) }
                g("alpn")?.let { t.put("alpn", JSONArray(it.split(','))) }
                if (insecure) t.put("allowInsecure", true)
                ss.put("tlsSettings", t)
            }
            "reality" -> {
                ss.put("security", "reality")
                val r = JSONObject()
                    .put("serverName", sni ?: "")
                    .put("fingerprint", g("fp") ?: "chrome")
                    .put("publicKey", g("pbk") ?: throw ConfigException("REALITY link is missing the public key (pbk)"))
                    .put("shortId", g("sid") ?: "")
                    .put("spiderX", g("spx") ?: "")
                ss.put("realitySettings", r)
            }
            "none", "" -> ss.put("security", "none")
            else -> throw ConfigException("Unsupported security: $security")
        }
        return ss
    }

    // ------------------------------------------------------------------ protocols

    private fun vless(s: String): ParsedServer {
        val l = splitLink(s)
        val id = urlDecode(l.userInfo ?: throw ConfigException("VLESS link has no UUID"))
        val user = JSONObject()
            .put("id", id)
            .put("encryption", l.q("encryption") ?: "none")
            .put("level", 0)
        l.q("flow")?.let { user.put("flow", it) }
        val ob = vnextOutbound("vless", l.host, l.port, user, streamSettings(l.query, "none"))
        return ParsedServer("vless", l.host, l.port, nameOf(l.fragment, l.host, l.port), ob)
    }

    private fun vmess(s: String): ParsedServer {
        val body = s.substring(s.indexOf("://") + 3).trim()
        if (body.contains('@')) return vmessUri(s)

        val j = try {
            JSONObject(String(decodeBase64(body.substringBefore('#')), Charsets.UTF_8))
        } catch (ignored: Exception) {
            throw ConfigException("Invalid VMess link")
        }
        val host = j.optString("add").trim()
        val port = j.opt("port")?.toString()?.trim()?.toIntOrNull() ?: 0
        val id = j.optString("id").trim()
        if (host.isEmpty() || port !in 1..65535 || id.isEmpty()) {
            throw ConfigException("VMess link is missing address, port or id")
        }

        val net = j.optString("net").ifEmpty { "tcp" }.lowercase()
        val q = LinkedHashMap<String, String>()
        fun put(k: String, v: String) {
            if (v.isNotEmpty()) q[k] = v
        }
        q["type"] = net
        q["security"] = if (j.optString("tls").equals("tls", ignoreCase = true)) "tls" else "none"
        put("host", j.optString("host"))
        put("sni", j.optString("sni"))
        put("alpn", j.optString("alpn"))
        put("fp", j.optString("fp"))
        val path = j.optString("path")
        val hdr = j.optString("type")
        when (net) {
            "grpc" -> {
                put("servicename", path)
                put("mode", hdr)
            }
            "kcp", "mkcp" -> {
                put("seed", path)
                put("headertype", hdr)
            }
            else -> {
                put("path", path)
                put("headertype", hdr)
            }
        }
        if (j.optString("allowInsecure") == "1" || j.optBoolean("allowInsecure", false)) q["allowinsecure"] = "1"

        val user = JSONObject()
            .put("id", id)
            .put("alterId", j.opt("aid")?.toString()?.trim()?.toIntOrNull() ?: 0)
            .put("security", j.optString("scy").ifEmpty { "auto" })
            .put("level", 0)
        val ob = vnextOutbound("vmess", host, port, user, streamSettings(q, "none"))
        val ps = j.optString("ps").takeIf { it.isNotBlank() }
        return ParsedServer("vmess", host, port, nameOf(ps, host, port), ob)
    }

    /** vmess://uuid@host:port?encryption=auto&security=tls&type=ws... (URI-style VMess links) */
    private fun vmessUri(s: String): ParsedServer {
        val l = splitLink(s)
        val id = urlDecode(l.userInfo ?: throw ConfigException("VMess link has no UUID"))
        val user = JSONObject()
            .put("id", id)
            .put("alterId", 0)
            .put("security", l.q("encryption") ?: "auto")
            .put("level", 0)
        val ob = vnextOutbound("vmess", l.host, l.port, user, streamSettings(l.query, "none"))
        return ParsedServer("vmess", l.host, l.port, nameOf(l.fragment, l.host, l.port), ob)
    }

    private fun shadowsocks(s: String): ParsedServer {
        var rest = s.substring(s.indexOf("://") + 3)
        var fragment: String? = null
        var i = rest.indexOf('#')
        if (i >= 0) {
            fragment = urlDecode(rest.substring(i + 1))
            rest = rest.substring(0, i)
        }
        var queryStr = ""
        i = rest.indexOf('?')
        if (i >= 0) {
            queryStr = rest.substring(i + 1)
            rest = rest.substring(0, i)
        }
        rest = rest.trimEnd('/')
        val query = parseQuery(queryStr)
        if (query.containsKey("plugin")) throw ConfigException("Shadowsocks plugins are not supported")

        val creds: String
        val hp: String
        val at = rest.lastIndexOf('@')
        if (at >= 0) {
            // SIP002: base64(method:password)@host:port   or   method:password@host:port (percent-encoded)
            val u = urlDecode(rest.substring(0, at))
            creds = if (u.contains(':')) u else b64Text(u)
            hp = rest.substring(at + 1)
        } else {
            // legacy: base64(method:password@host:port)
            val d = b64Text(urlDecode(rest))
            val a = d.lastIndexOf('@')
            if (a < 0) throw ConfigException("Malformed Shadowsocks link")
            creds = d.substring(0, a)
            hp = d.substring(a + 1)
        }
        val c = creds.indexOf(':')
        if (c <= 0) throw ConfigException("Malformed Shadowsocks credentials")
        val method = creds.substring(0, c).lowercase()
        val password = creds.substring(c + 1)
        val (host, port) = hostPort(hp)

        val server = JSONObject()
            .put("address", host)
            .put("port", port)
            .put("method", method)
            .put("password", password)
            .put("level", 0)
        val stream = if (query.containsKey("type") || query.containsKey("security")) streamSettings(query, "none") else null
        return ParsedServer("shadowsocks", host, port, nameOf(fragment, host, port), serversOutbound("shadowsocks", server, stream))
    }

    private fun trojan(s: String): ParsedServer {
        val l = splitLink(s)
        val password = urlDecode(l.userInfo ?: throw ConfigException("Trojan link has no password"))
        val server = JSONObject()
            .put("address", l.host)
            .put("port", l.port)
            .put("password", password)
            .put("level", 0)
        val ob = serversOutbound("trojan", server, streamSettings(l.query, "tls"))
        return ParsedServer("trojan", l.host, l.port, nameOf(l.fragment, l.host, l.port), ob)
    }

    private fun credentials(userInfo: String?): Pair<String?, String?> {
        if (userInfo == null) return null to null
        val d = urlDecode(userInfo)
        var cred = d
        if (!d.contains(':')) {
            // socks://base64(user:pass)@host:port
            val decoded = try {
                String(decodeBase64(d), Charsets.UTF_8)
            } catch (ignored: IllegalArgumentException) {
                d
            }
            if (decoded.contains(':')) cred = decoded
        }
        val c = cred.indexOf(':')
        return if (c >= 0) cred.substring(0, c) to cred.substring(c + 1) else cred to null
    }

    private fun userServer(l: Link): JSONObject {
        val (user, pass) = credentials(l.userInfo)
        val server = JSONObject().put("address", l.host).put("port", l.port)
        if (!user.isNullOrEmpty()) {
            server.put("users", JSONArray().put(JSONObject().put("user", user).put("pass", pass ?: "").put("level", 0)))
        }
        return server
    }

    private fun socks(s: String): ParsedServer {
        val l = splitLink(s)
        val ob = serversOutbound("socks", userServer(l), null)
        return ParsedServer("socks", l.host, l.port, nameOf(l.fragment, l.host, l.port), ob)
    }

    private fun httpProxy(s: String): ParsedServer {
        val l = splitLink(s)
        val ob = serversOutbound("http", userServer(l), null)
        return ParsedServer("http", l.host, l.port, nameOf(l.fragment, l.host, l.port), ob)
    }

    /** Accepts a full Xray config (first proxy outbound is used) or a bare outbound object. */
    private fun parseJson(t: String): ParsedServer {
        val root = try {
            JSONObject(t)
        } catch (e: JSONException) {
            throw ConfigException("Invalid JSON: ${e.message}")
        }
        val src: JSONObject = when {
            root.has("outbounds") -> {
                val arr = root.optJSONArray("outbounds") ?: throw ConfigException("Invalid \"outbounds\" array")
                (0 until arr.length())
                    .mapNotNull { arr.optJSONObject(it) }
                    .firstOrNull { it.optString("protocol") in JSON_PROTOCOLS }
                    ?: throw ConfigException("No supported proxy outbound found in the JSON config")
            }
            root.has("protocol") -> root
            else -> throw ConfigException("JSON is neither an Xray config nor an outbound object")
        }
        val ob = JSONObject(src.toString()).put("tag", "proxy")
        val protocol = ob.optString("protocol")
        val st = ob.optJSONObject("settings")
        val ep = st?.optJSONArray("vnext")?.optJSONObject(0)
            ?: st?.optJSONArray("servers")?.optJSONObject(0)
            ?: st
        val host = ep?.optString("address").orEmpty()
        val port = ep?.optInt("port", 0) ?: 0
        if (host.isEmpty() || port !in 1..65535) throw ConfigException("Could not read server address/port from the JSON")
        val name = root.optString("remarks").ifBlank { src.optString("remarks") }.ifBlank { "$protocol $host:$port" }
        return ParsedServer(protocol, host, port, name, ob)
    }
}
