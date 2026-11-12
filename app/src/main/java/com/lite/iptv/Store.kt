package com.lite.iptv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** All persistent state: playlists, settings, favorites, watch progress. Plain SharedPreferences, no database. */
object Store {
    private lateinit var sp: SharedPreferences
    private lateinit var pr: SharedPreferences
    private var favs: HashSet<String>? = null

    fun init(c: Context) {
        sp = c.applicationContext.getSharedPreferences("iptv", Context.MODE_PRIVATE)
        pr = c.applicationContext.getSharedPreferences("progress", Context.MODE_PRIVATE)
        if (sp.getInt("seed_v", 0) < Defaults.VERSION) {
            sp.edit().putInt("seed_v", Defaults.VERSION).apply()
            val old = setOf("m3u0", "m3u1", "m3u2", "m3u3", "m3u4", "m3u5", "m3u6", "m3u7", "m3u8", "m3u9", "m3u10")                       // earlier built-in lists (12k channels)
            val cur = playlists().filter { it.id !in old }
            savePlaylists(Defaults.list() + cur)
            if (activeId.isEmpty() || activeId in old) activeId = Defaults.ID
        }
    }

    var lang: String
        get() = sp.getString("lang", "ar") ?: "ar"
        set(v) = sp.edit().putString("lang", v).apply()
    var liveTs: Boolean
        get() = sp.getBoolean("live_ts", false)
        set(v) = sp.edit().putBoolean("live_ts", v).apply()
    var buffer: Int
        get() = sp.getInt("buffer", 1)
        set(v) = sp.edit().putInt("buffer", v).apply()
    var reconnect: Boolean
        get() = sp.getBoolean("reconnect", true)
        set(v) = sp.edit().putBoolean("reconnect", v).apply()
    var homeIndex: Int
        get() = sp.getInt("home_idx", 0)
        set(v) = sp.edit().putInt("home_idx", v).apply()
    var activeId: String
        get() = sp.getString("active", "") ?: ""
        set(v) = sp.edit().putString("active", v).apply()

    fun getStr(k: String, d: String) = sp.getString(k, d) ?: d
    fun setStr(k: String, v: String) = sp.edit().putString(k, v).apply()
    fun setStrNow(k: String, v: String) { sp.edit().putString(k, v).commit() }

    // ---- playlists ----
    fun playlists(): List<Playlist> {
        val a = JSONArray(sp.getString("playlists", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Playlist(o.getString("id"), o.getString("name"), o.getString("type"), o.getString("url"), o.optString("user"), o.optString("pass"))
        }
    }

    fun savePlaylists(l: List<Playlist>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("type", it.type).put("url", it.url).put("user", it.user).put("pass", it.pass)) }
        sp.edit().putString("playlists", a.toString()).apply()
    }

    fun active(): Playlist? {
        val l = playlists()
        return l.firstOrNull { it.id == activeId } ?: l.firstOrNull()?.also { activeId = it.id }
    }

    fun pid(): String = activeId

    // ---- favorites ----
    private fun favSet(): HashSet<String> =
        favs ?: HashSet<String>(sp.getStringSet("favs", emptySet()) ?: emptySet()).also { favs = it }

    fun favKey(kind: String, id: String) = "${pid()}|$kind|$id"
    fun vodFavKey(v: Vod) = favKey(if (v.isSeries) "S" else "M", v.id)
    fun isFav(k: String) = favSet().contains(k)
    fun toggleFav(k: String): Boolean {
        val s = favSet()
        val on = if (s.contains(k)) { s.remove(k); false } else { s.add(k); true }
        sp.edit().putStringSet("favs", HashSet(s)).apply()
        return on
    }

    // ---- deleted (hidden) channels, per playlist ----
    private fun hiddenKey() = "hidden_${pid()}"
    private fun hiddenSet(): HashSet<String> = HashSet(sp.getStringSet(hiddenKey(), emptySet()) ?: emptySet())
    private var hiddenCache: HashSet<String>? = null
    private var hiddenFor = ""
    fun isHidden(k: String): Boolean {
        if (hiddenCache == null || hiddenFor != pid()) { hiddenCache = hiddenSet(); hiddenFor = pid() }
        return hiddenCache!!.contains(k)
    }
    fun hide(k: String) {
        val s = hiddenSet(); s.add(k)
        sp.edit().putStringSet(hiddenKey(), s).apply()
        hiddenCache = s; hiddenFor = pid()
    }
    fun hiddenCount() = hiddenSet().size
    fun clearHidden() {
        sp.edit().remove(hiddenKey()).apply()
        hiddenCache = HashSet(); hiddenFor = pid()
    }

    // ---- channels the user moved to another category, per playlist ----
    private fun movesKey() = "moves_${pid()}"
    private var movesCache: HashMap<String, String>? = null
    private var movesFor = ""
    private fun moves(): HashMap<String, String> {
        if (movesCache == null || movesFor != pid()) {
            val m = HashMap<String, String>()
            for (e in sp.getStringSet(movesKey(), emptySet()) ?: emptySet()) { val i = e.indexOf('\t'); if (i > 0) m[e.substring(0, i)] = e.substring(i + 1) }
            movesCache = m; movesFor = pid()
        }
        return movesCache!!
    }
    fun groupOverride(k: String): String? = moves()[k]
    fun setGroup(k: String, g: String) {
        moves()[k] = g
        sp.edit().putStringSet(movesKey(), moves().map { it.key + "\t" + it.value }.toSet()).apply()
    }
    fun clearMoves() { moves().clear(); sp.edit().remove(movesKey()).apply() }

    // ---- continue watching ----
    fun recent(): List<Vod> {
        val a = JSONArray(sp.getString("recent_${pid()}", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Vod(o.getString("id"), o.getString("name"), o.optString("poster"), o.optString("url"), o.optString("group"),
                o.optBoolean("s"), o.optString("year"), o.optString("rating"))
        }
    }

    fun pushRecent(v: Vod) {
        val l = recent().filterNot { it.id == v.id && it.isSeries == v.isSeries }.toMutableList()
        l.add(0, v)
        val a = JSONArray()
        l.take(40).forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("poster", it.poster).put("url", it.url)
                .put("group", it.group).put("s", it.isSeries).put("year", it.year).put("rating", it.rating))
        }
        sp.edit().putString("recent_${pid()}", a.toString()).apply()
    }

    // ---- playback position ----
    fun progress(url: String): Long = pr.getLong(url.hashCode().toString(), 0L)

    fun saveProgress(url: String, pos: Long, dur: Long) {
        val k = url.hashCode().toString()
        if (pos < 5000 || (dur > 0 && pos > dur * 0.95)) pr.edit().remove(k).apply()
        else pr.edit().putLong(k, pos).apply()
    }
}
