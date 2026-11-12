package com.lite.iptv

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

fun Context.toast(@StringRes r: Int) = Toast.makeText(this, r, Toast.LENGTH_SHORT).show()
fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%02d:%02d", m, sec)
}

fun RecyclerView.setup(lm: RecyclerView.LayoutManager, ad: RecyclerView.Adapter<*>) {
    layoutManager = lm
    adapter = ad
    itemAnimator = null          // no animations: keeps focus stable and saves CPU
    setHasFixedSize(true)
    setItemViewCacheSize(6)
}

/** Touch "Back" button (remotes use the Back key). */
fun Activity.wireBack() { findViewById<android.view.View?>(R.id.btnBack)?.setOnClickListener { finish() } }

/** Reduce whatever the user pasted (".../c/", ".../get.php?username=..", trailing slash) to the panel's base address. */
fun normXtream(raw: String): String {
    var u = raw.trim().substringBefore("?")
    for (m in listOf("/player_api.php", "/get.php", "/panel_api.php", "/xmltv.php", "/portal.php", "/c/", "/c")) {
        val i = u.indexOf(m, 8)
        if (i > 0 && (m != "/c" || u.length == i + 2)) u = u.substring(0, i)
    }
    return u.trimEnd('/')
}

/** Reads username/password out of a pasted get.php link, if present. */
fun xtreamCreds(raw: String): Pair<String, String>? {
    val q = raw.substringAfter("?", "")
    if (q.isEmpty()) return null
    val m = q.split("&").mapNotNull { it.split("=", limit = 2).takeIf { p -> p.size == 2 } }.associate { it[0] to it[1] }
    val u = m["username"] ?: return null
    return u to (m["password"] ?: "")
}

/** Stream address + optional request headers, stored as "url|Referer=..&User-Agent=.." (same convention as Kodi). */
fun withHeaders(url: String, h: Map<String, String>): String =
    if (h.isEmpty() || url.contains("|")) url else url + "|" + h.entries.joinToString("&") { it.key + "=" + java.net.URLEncoder.encode(it.value, "UTF-8") }

fun splitHeaders(u: String): Pair<String, Map<String, String>> {
    val i = u.indexOf('|')
    if (i < 0) return u to emptyMap()
    val m = LinkedHashMap<String, String>()
    for (kv in u.substring(i + 1).split("&")) {
        val e = kv.indexOf('=')
        if (e > 0) m[kv.substring(0, e)] = try { java.net.URLDecoder.decode(kv.substring(e + 1), "UTF-8") } catch (_: Exception) { kv.substring(e + 1) }
    }
    return u.substring(0, i) to m
}

fun androidx.media3.datasource.DefaultHttpDataSource.Factory.applyHeaders(h: Map<String, String>) {
    setUserAgent(h["User-Agent"] ?: Net.UA)
    setDefaultRequestProperties(h.filterKeys { it != "User-Agent" })
}
