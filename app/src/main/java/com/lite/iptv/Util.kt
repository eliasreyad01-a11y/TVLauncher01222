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
