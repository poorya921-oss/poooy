package com.pooy.vpn.core

import com.pooy.vpn.data.Profile
import java.util.UUID

/** Turns pasted / downloaded / scanned text into profiles. */
object Importer {
    class Result(val profiles: List<Profile>, val skipped: Int)

    fun newId(): String = UUID.randomUUID().toString()

    fun fromText(text: String, subId: String? = null): Result {
        val t = text.trim().removePrefix("\uFEFF").trim()
        if (t.isEmpty()) return Result(emptyList(), 0)

        if (t.startsWith("{")) {
            return try {
                val s = ConfigParser.parse(t)
                Result(listOf(Profile(newId(), s.name, t, subId)), 0)
            } catch (ignored: ConfigException) {
                Result(emptyList(), 1)
            }
        }

        // Subscriptions are usually Base64 of newline-separated links; plain lists work too.
        var body = t
        if (t.lineSequence().none { ConfigParser.isProxyLink(it) }) {
            body = try {
                String(ConfigParser.decodeBase64(t), Charsets.UTF_8)
            } catch (ignored: IllegalArgumentException) {
                t
            }
        }

        val out = ArrayList<Profile>()
        var skipped = 0
        for (line in body.lineSequence()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            if (!ConfigParser.isProxyLink(l)) {
                skipped++
                continue
            }
            try {
                val s = ConfigParser.parse(l)
                out.add(Profile(newId(), s.name, l, subId))
            } catch (ignored: ConfigException) {
                skipped++
            }
        }
        return Result(out, skipped)
    }
}
