package com.lite.iptv

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
import java.net.URLEncoder

/**
 * Loads Xtream / M3U content lazily per section. Raw responses are cached on disk (12h) and parsed with
 * streaming readers, so memory stays flat even for 50k-item playlists.
 */
object Repo {
    private const val TTL = 12L * 3600 * 1000
    private var pid = ""
    private var live: List<Channel>? = null
    private var movies: List<Vod>? = null
    private var series: List<Vod>? = null
    private var m3uEps: Map<String, List<Episode>> = emptyMap()
    /** Category name -> position as the provider lists it (live / vod / series). */
    private val ranks = HashMap<String, Map<String, Int>>()
    fun rank(kind: String, group: String): Int = ranks[kind]?.get(group) ?: Int.MAX_VALUE

    private fun dir(c: Context) = File(c.cacheDir, "data").also { it.mkdirs() }

    fun invalidateMemory() { live = null; movies = null; series = null; m3uEps = emptyMap() }

    fun reset(c: Context) {
        invalidateMemory()
        dir(c).listFiles()?.forEach { it.delete() }
    }

    private fun prov(): Playlist {
        val p = Store.active() ?: throw IllegalStateException("No playlist")
        if (p.id != pid) { invalidateMemory(); pid = p.id }
        return p
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun api(p: Playlist, action: String, extra: String = "") =
        "${normXtream(p.url)}/player_api.php?username=${enc(p.user)}&password=${enc(p.pass)}&action=$action$extra"

    private fun fetch(c: Context, p: Playlist, key: String, url: String): File {
        val f = File(dir(c), "${p.id}_$key")
        if (url.startsWith("asset:")) {          // playlist bundled inside the app
            if (!f.exists()) c.assets.open(url.removePrefix("asset:")).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            return f
        }
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > TTL) {
            try { Net.download(url, f) } catch (e: Exception) { if (!f.exists()) throw e }
        }
        return f
    }

    private fun forEachObj(f: File, onObj: (Map<String, String>) -> Unit) {
        JsonReader(InputStreamReader(f.inputStream().buffered(), "UTF-8")).use { r ->
            if (r.peek() != JsonToken.BEGIN_ARRAY) return
            r.beginArray()
            while (r.hasNext()) {
                val m = HashMap<String, String>(12)
                r.beginObject()
                while (r.hasNext()) {
                    val n = r.nextName()
                    when (r.peek()) {
                        JsonToken.STRING, JsonToken.NUMBER -> m[n] = r.nextString()
                        JsonToken.NULL -> r.nextNull()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                onObj(m)
            }
            r.endArray()
        }
    }

    // ---------------- public API ----------------

    suspend fun live(c: Context): List<Channel> = withContext(Dispatchers.IO) {
        val p = prov()
        live ?: synchronized(this@Repo) {
            live ?: run {
                if (p.type == "m3u") { loadM3u(c, p); live ?: emptyList() } else xLive(c, p).also { live = it }
            }
        }
    }

    suspend fun movies(c: Context): List<Vod> = withContext(Dispatchers.IO) {
        val p = prov()
        movies ?: synchronized(this@Repo) {
            movies ?: run {
                if (p.type == "m3u") { loadM3u(c, p); movies ?: emptyList() } else xMovies(c, p).also { movies = it }
            }
        }
    }

    suspend fun series(c: Context): List<Vod> = withContext(Dispatchers.IO) {
        val p = prov()
        series ?: synchronized(this@Repo) {
            series ?: run {
                if (p.type == "m3u") { loadM3u(c, p); series ?: emptyList() } else xSeries(c, p).also { series = it }
            }
        }
    }

    /** Fills plot/year/rating for a movie. Falls back to the basic item if the server has no info call. */
    suspend fun vodInfo(c: Context, v: Vod): Vod = withContext(Dispatchers.IO) {
        val p = prov()
        if (p.type != "xtream") return@withContext v
        try {
            val info = JSONObject(Net.text(api(p, "get_vod_info", "&vod_id=${enc(v.id)}"))).optJSONObject("info") ?: return@withContext v
            v.copy(
                plot = info.optString("plot").ifBlank { info.optString("description") },
                year = info.optString("releasedate").take(4).ifBlank { v.year },
                rating = info.optString("rating").ifBlank { v.rating },
                extra = info.optString("duration").ifBlank { info.optString("genre") }
            )
        } catch (e: Exception) { v }
    }

    suspend fun seriesInfo(c: Context, v: Vod): Pair<Vod, List<Episode>> = withContext(Dispatchers.IO) {
        val p = prov()
        if (p.type == "m3u") return@withContext v to (m3uEps[v.id] ?: emptyList())
        val o = JSONObject(Net.text(api(p, "get_series_info", "&series_id=${enc(v.id)}")))
        val base = normXtream(p.url)
        val out = ArrayList<Episode>()
        fun addSeason(arr: JSONArray, season: Int) {
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val id = e.optString("id")
                if (id.isEmpty()) continue
                val ext = e.optString("container_extension").ifBlank { "mp4" }
                out.add(Episode(id, e.optInt("season", season), e.optInt("episode_num", i + 1), e.optString("title"),
                    "$base/series/${p.user}/${p.pass}/$id.$ext"))
            }
        }
        when (val raw = o.opt("episodes")) {
            is JSONObject -> raw.keys().forEach { k -> raw.optJSONArray(k)?.let { addSeason(it, k.toIntOrNull() ?: 1) } }
            is JSONArray -> for (i in 0 until raw.length()) raw.optJSONArray(i)?.let { addSeason(it, i + 1) }
        }
        out.sortWith(compareBy({ it.season }, { it.num }))
        val info = o.optJSONObject("info")
        val nv = if (info == null) v else v.copy(
            plot = info.optString("plot").ifBlank { v.plot },
            year = info.optString("releaseDate").take(4).ifBlank { v.year },
            rating = info.optString("rating").ifBlank { v.rating },
            poster = info.optString("cover").ifBlank { v.poster },
            extra = info.optString("genre")
        )
        nv to out
    }

    // ---------------- Xtream ----------------

    private fun xCats(c: Context, p: Playlist, kind: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        try {
            forEachObj(fetch(c, p, "cat_$kind", api(p, "get_${kind}_categories"))) { m[it["category_id"] ?: ""] = it["category_name"] ?: "" }
        } catch (e: Exception) { /* categories are optional */ }
        val r = HashMap<String, Int>()
        m.values.forEachIndexed { i, n -> if (!r.containsKey(n)) r[n] = i }
        ranks[kind] = r
        return m
    }

    private fun xLive(c: Context, p: Playlist): List<Channel> {
        val cats = xCats(c, p, "live")
        val f = fetch(c, p, "live", api(p, "get_live_streams"))
        val base = normXtream(p.url)
        val ext = if (Store.liveTs) "ts" else "m3u8"
        val out = ArrayList<Channel>(2048)
        forEachObj(f) { m ->
            val id = m["stream_id"]
            if (id != null) out.add(Channel(id, (m["name"] ?: "").trim(), m["stream_icon"] ?: "",
                "$base/live/${p.user}/${p.pass}/$id.$ext", cats[m["category_id"]] ?: "Other"))
        }
        return out
    }

    private fun xMovies(c: Context, p: Playlist): List<Vod> {
        val cats = xCats(c, p, "vod")
        val f = fetch(c, p, "vod", api(p, "get_vod_streams"))
        val base = normXtream(p.url)
        val out = ArrayList<Vod>(4096)
        forEachObj(f) { m ->
            val id = m["stream_id"]
            if (id != null) {
                val ext = (m["container_extension"] ?: "").ifBlank { "mp4" }
                out.add(Vod(id, (m["name"] ?: "").trim(), m["stream_icon"] ?: "", "$base/movie/${p.user}/${p.pass}/$id.$ext",
                    cats[m["category_id"]] ?: "Other", false, m["year"] ?: "", m["rating"] ?: "", added = m["added"]?.toLongOrNull() ?: 0L))
            }
        }
        out.sortByDescending { it.added }   // newest first, like "Order by added"
        return out
    }

    private fun xSeries(c: Context, p: Playlist): List<Vod> {
        val cats = xCats(c, p, "series")
        val f = fetch(c, p, "series", api(p, "get_series"))
        val out = ArrayList<Vod>(2048)
        forEachObj(f) { m ->
            val id = m["series_id"]
            if (id != null) out.add(Vod(id, (m["name"] ?: "").trim(), m["cover"] ?: "", "", cats[m["category_id"]] ?: "Other", true,
                (m["year"] ?: m["releaseDate"] ?: "").take(4), m["rating"] ?: "", m["plot"] ?: "", added = m["last_modified"]?.toLongOrNull() ?: 0L))
        }
        out.sortByDescending { it.added }
        return out
    }

    // ---------------- M3U ----------------

    private val attr = Regex("([\\w-]+)=\"([^\"]*)\"")
    private val epRx = Regex("^(.*?)[\\s._-]*[Ss](\\d{1,2})[\\s._-]*[Ee](\\d{1,3})")

    private fun loadM3u(c: Context, p: Playlist) {
        val f = fetch(c, p, "m3u", p.url)
        val ch = ArrayList<Channel>(2048)
        val mv = ArrayList<Vod>()
        val sr = LinkedHashMap<String, Vod>()
        val eps = HashMap<String, ArrayList<Episode>>()
        var name = ""; var logo = ""; var group = ""; var idx = 0
        f.bufferedReader().use { r ->
            val hdr = LinkedHashMap<String, String>()
            var line = r.readLine()
            while (line != null) {
                val t = line.trim()
                if (t.startsWith("#EXTINF")) {
                    val q = t.lastIndexOf('"')
                    val comma = t.indexOf(',', if (q >= 0) q else 0)
                    name = if (comma >= 0) t.substring(comma + 1).trim() else ""
                    logo = ""; group = ""
                    for (m in attr.findAll(t)) when (m.groupValues[1]) {
                        "tvg-logo" -> logo = m.groupValues[2]
                        "group-title" -> group = m.groupValues[2]
                    }
                } else if (t.startsWith("#EXTVLCOPT:http-referrer=", true) || t.startsWith("#EXTVLCOPT:http-referer=", true)) {
                    hdr["Referer"] = t.substringAfter("=").trim()
                } else if (t.startsWith("#EXTVLCOPT:http-user-agent=", true)) {
                    hdr["User-Agent"] = t.substringAfter("=").trim()
                } else if (t.startsWith("#EXTGRP:")) {
                    group = t.substring(8).trim()
                } else if (t.isNotEmpty() && !t.startsWith("#")) {
                    idx++
                    val id = idx.toString()
                    when {
                        t.contains("/movie/") -> mv.add(Vod(id, name, logo, t, group.ifEmpty { "Other" }, false))
                        t.contains("/series/") || (!t.contains("/live/") && epRx.containsMatchIn(name)) -> {
                            val m = epRx.find(name)
                            val base = (m?.groupValues?.get(1)?.trim() ?: name).ifEmpty { name }
                            sr.getOrPut(base) { Vod(base, base, logo, "", group.ifEmpty { "Other" }, true) }
                            eps.getOrPut(base) { ArrayList() }.add(Episode(id, m?.groupValues?.get(2)?.toIntOrNull() ?: 1,
                                m?.groupValues?.get(3)?.toIntOrNull() ?: eps[base]!!.size + 1, name, t))
                        }
                        else -> ch.add(Channel(id, name, logo, withHeaders(t, hdr), group.ifEmpty { "Other" }))
                    }
                    name = ""; logo = ""; hdr.clear()
                }
                line = r.readLine()
            }
        }
        live = ch; movies = mv; series = ArrayList(sr.values)
        m3uEps = eps.mapValues { e -> e.value.sortedWith(compareBy({ it.season }, { it.num })) }
    }
}
