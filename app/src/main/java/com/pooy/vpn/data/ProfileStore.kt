package com.pooy.vpn.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists profiles/subscriptions as one Keystore-encrypted file in app-private storage.
 * Nothing is kept in memory here; callers load on demand.
 */
class ProfileStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "profiles.dat")

    fun load(): StoreData {
        synchronized(LOCK) {
            if (!file.exists()) return StoreData()
            return try {
                fromJson(JSONObject(String(Crypto.decrypt(file.readBytes()), Charsets.UTF_8)))
            } catch (e: Exception) {
                // Unreadable (e.g. keystore key lost after a restore). Keep the bytes aside instead of silently destroying them.
                Log.w(TAG, "Could not read profile store: ${e.javaClass.simpleName}")
                file.renameTo(File(file.path + ".bad"))
                StoreData()
            }
        }
    }

    fun save(d: StoreData) {
        synchronized(LOCK) {
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(Crypto.encrypt(toJson(d).toString().toByteArray(Charsets.UTF_8)))
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw java.io.IOException("Could not write profile store")
            }
        }
    }

    private fun toJson(d: StoreData): JSONObject {
        val profiles = JSONArray()
        for (p in d.profiles) {
            val o = JSONObject().put("id", p.id).put("name", p.name).put("raw", p.raw)
            if (p.subId != null) o.put("sub", p.subId)
            profiles.put(o)
        }
        val subs = JSONArray()
        for (s in d.subscriptions) {
            subs.put(JSONObject().put("id", s.id).put("name", s.name).put("url", s.url))
        }
        val root = JSONObject().put("v", 1).put("profiles", profiles).put("subs", subs)
        if (d.activeId != null) root.put("active", d.activeId)
        return root
    }

    private fun fromJson(o: JSONObject): StoreData {
        val profiles = ArrayList<Profile>()
        val pa = o.optJSONArray("profiles") ?: JSONArray()
        for (i in 0 until pa.length()) {
            val p = pa.getJSONObject(i)
            profiles.add(Profile(p.getString("id"), p.getString("name"), p.getString("raw"), if (p.has("sub")) p.getString("sub") else null))
        }
        val subs = ArrayList<Subscription>()
        val sa = o.optJSONArray("subs") ?: JSONArray()
        for (i in 0 until sa.length()) {
            val s = sa.getJSONObject(i)
            subs.add(Subscription(s.getString("id"), s.getString("name"), s.getString("url")))
        }
        return StoreData(profiles, subs, if (o.has("active")) o.getString("active") else null)
    }

    private companion object {
        const val TAG = "ProfileStore"
        val LOCK = Any()
    }
}
