package com.pooy.vpn

import com.pooy.vpn.core.ConfigException
import com.pooy.vpn.core.ConfigParser
import com.pooy.vpn.core.Importer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class ConfigParserTest {

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    @Test
    fun vlessReality() {
        val link = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?type=tcp&security=reality&sni=www.microsoft.com&fp=chrome&pbk=PUBKEY&sid=ab12&flow=xtls-rprx-vision#My%20Server"
        val s = ConfigParser.parse(link)
        assertEquals("My Server", s.name)
        assertEquals("example.com", s.host)
        assertEquals(443, s.port)
        val stream = s.outbound.getJSONObject("streamSettings")
        assertEquals("reality", stream.getString("security"))
        assertEquals("PUBKEY", stream.getJSONObject("realitySettings").getString("publicKey"))
        val user = s.outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
            .getJSONArray("users").getJSONObject(0)
        assertEquals("xtls-rprx-vision", user.getString("flow"))
    }

    @Test
    fun vlessWebSocketTls() {
        val link = "vless://id@h.example.org:8443?type=ws&security=tls&host=cdn.example.org&path=%2Fws&sni=cdn.example.org#ws"
        val stream = ConfigParser.parse(link).outbound.getJSONObject("streamSettings")
        assertEquals("ws", stream.getString("network"))
        assertEquals("/ws", stream.getJSONObject("wsSettings").getString("path"))
        assertEquals("cdn.example.org", stream.getJSONObject("tlsSettings").getString("serverName"))
    }

    @Test
    fun vmessBase64() {
        val json = JSONObject().put("v", "2").put("ps", "vm").put("add", "1.2.3.4").put("port", "443")
            .put("id", "22222222-2222-2222-2222-222222222222").put("aid", "0").put("scy", "auto")
            .put("net", "ws").put("host", "a.b").put("path", "/p").put("tls", "tls").toString()
        val s = ConfigParser.parse("vmess://" + b64(json))
        assertEquals("vm", s.name)
        assertEquals(443, s.port)
        assertEquals("tls", s.outbound.getJSONObject("streamSettings").getString("security"))
    }

    @Test
    fun shadowsocksSip002() {
        val s = ConfigParser.parse("ss://" + b64("aes-256-gcm:secret") + "@1.2.3.4:8388#ss1")
        val server = s.outbound.getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
        assertEquals("aes-256-gcm", server.getString("method"))
        assertEquals("secret", server.getString("password"))
        assertEquals("ss1", s.name)
    }

    @Test
    fun shadowsocksLegacy() {
        val s = ConfigParser.parse("ss://" + b64("chacha20-ietf-poly1305:pw@example.net:1234") + "#legacy")
        assertEquals("example.net", s.host)
        assertEquals(1234, s.port)
    }

    @Test
    fun trojanDefaultsToTls() {
        val s = ConfigParser.parse("trojan://pass@t.example.com:443?sni=t.example.com#tj")
        assertEquals("tls", s.outbound.getJSONObject("streamSettings").getString("security"))
    }

    @Test
    fun socksWithCredentials() {
        val s = ConfigParser.parse("socks://user:pw@1.1.1.1:1080#s")
        val users = s.outbound.getJSONObject("settings").getJSONArray("servers").getJSONObject(0).getJSONArray("users")
        assertEquals("user", users.getJSONObject(0).getString("user"))
        assertEquals("pw", users.getJSONObject(0).getString("pass"))
    }

    @Test
    fun httpProxyVsSubscription() {
        assertTrue(ConfigParser.isProxyLink("http://u:p@10.0.0.1:8080#x"))
        assertFalse(ConfigParser.isProxyLink("https://example.com/sub"))
        assertTrue(ConfigParser.isSubscriptionUrl("https://example.com/sub?token=abc"))
        assertFalse(ConfigParser.isSubscriptionUrl("http://u:p@10.0.0.1:8080#x"))
    }

    @Test
    fun invalidLinksThrow() {
        for (bad in listOf("vless://nohost", "vless://id@h:99999", "foo://x", "vmess://@@@", "ss://plugin@h:1?plugin=x")) {
            try {
                ConfigParser.parse(bad)
                fail("expected ConfigException for $bad")
            } catch (e: ConfigException) {
                // expected
            }
        }
    }

    @Test
    fun jsonOutboundImport() {
        val cfg = """{"remarks":"j","outbounds":[{"protocol":"freedom","tag":"direct"},
            {"protocol":"trojan","tag":"x","settings":{"servers":[{"address":"a.b","port":443,"password":"p"}]}}]}"""
        val s = ConfigParser.parse(cfg)
        assertEquals("j", s.name)
        assertEquals("proxy", s.outbound.getString("tag"))
        assertEquals("a.b", s.host)
    }

    @Test
    fun base64SubscriptionImport() {
        val body = "vless://id@a.com:443?security=tls#A\nss://" + b64("aes-128-gcm:p") + "@b.com:1#B\ngarbage line\n"
        val r = Importer.fromText(b64(body))
        assertEquals(2, r.profiles.size)
        assertEquals(1, r.skipped)
    }
}
