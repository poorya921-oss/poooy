package com.pooy.vpn.core

import org.json.JSONArray
import org.json.JSONObject

/** Generates the complete Xray JSON documents handed to the core. */
object XrayConfigBuilder {

    /** Used by the latency test. */
    const val TEST_URL = "https://www.gstatic.com/generate_204"

    private val PRIVATE_RANGES = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16",
        "fc00::/7", "fe80::/10",
    )

    /**
     * Runtime config: a loopback SOCKS5 inbound (password-protected with a per-session
     * random credential, UDP enabled) that hev-socks5-tunnel feeds from the TUN device.
     * Private/LAN destinations go direct; everything else uses the selected server.
     */
    fun runtime(server: ParsedServer, socksPort: Int, user: String, pass: String): String {
        val inbound = JSONObject()
            .put("tag", "socks-in")
            .put("listen", "127.0.0.1")
            .put("port", socksPort)
            .put("protocol", "socks")
            .put(
                "settings",
                JSONObject()
                    .put("auth", "password")
                    .put("accounts", JSONArray().put(JSONObject().put("user", user).put("pass", pass)))
                    .put("udp", true)
                    .put("ip", "127.0.0.1"),
            )

        val rule = JSONObject()
            .put("type", "field")
            .put("ip", JSONArray(PRIVATE_RANGES))
            .put("outboundTag", "direct")

        val root = JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("stats", JSONObject())
            .put(
                "policy",
                JSONObject().put(
                    "system",
                    JSONObject().put("statsOutboundUplink", true).put("statsOutboundDownlink", true),
                ),
            )
            // "localhost" = system resolver (outside the VPN), so the server's own hostname never loops through the tunnel
            .put("dns", JSONObject().put("servers", JSONArray().put("localhost")))
            .put("inbounds", JSONArray().put(inbound))
            .put(
                "outbounds",
                JSONArray()
                    .put(server.outbound)
                    .put(JSONObject().put("tag", "direct").put("protocol", "freedom")),
            )
            .put(
                "routing",
                JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray().put(rule)),
            )
        return root.toString()
    }

    /** Minimal config for a one-off delay measurement: no inbounds, so no port conflicts with a running tunnel. */
    fun speedTest(server: ParsedServer): String =
        JSONObject()
            .put("log", JSONObject().put("loglevel", "none"))
            .put("outbounds", JSONArray().put(server.outbound))
            .toString()
}
