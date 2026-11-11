package com.lite.iptv

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import java.util.UUID

/** Add / edit / delete / activate playlists (Xtream Codes or M3U / M3U8 URL). */
class PlaylistActivity : AppCompatActivity() {
    private val ad = Adapter<Playlist>(R.layout.item_setting) { v, p, _ ->
        v.findViewById<TextView>(R.id.tvTitle).text = p.name
        val t = if (p.type == "xtream") "Xtream" else "M3U"
        v.findViewById<TextView>(R.id.tvValue).text = if (p.id == Store.active()?.id) "$t  ${getString(R.string.active)}" else t
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val txt = contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val n = importBackup(txt)
            toast(getString(R.string.imported_n, n))
            refresh()
        } catch (e: Exception) { toast(R.string.import_fail) }
    }

    /** IPTVnator backup JSON, or a simple {"playlists":[...]} file: adds Xtream logins and M3U links (not the bulky channel text). */
    private fun importBackup(txt: String): Int {
        val arr = JSONObject(txt).getJSONArray("playlists")
        val all = Store.playlists().toMutableList()
        var added = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val title = o.optString("title")
            val c = o.optJSONObject("connection")
            val s = o.optJSONObject("source")
            val p = when {
                c != null && c.optString("serverUrl").isNotBlank() ->
                    Playlist(UUID.randomUUID().toString(), title.ifBlank { "Xtream" }, "xtream", normXtream(c.optString("serverUrl")),
                        c.optString("username"), c.optString("password"))
                s != null && s.optString("url").isNotBlank() ->
                    Playlist(UUID.randomUUID().toString(), title.ifBlank { "M3U" }, "m3u", s.optString("url").substringBefore("?utm_source"))
                else -> null
            } ?: continue
            if (all.any { it.url == p.url && it.user == p.user }) continue
            all.add(p); added++
        }
        if (added > 0) {
            Store.savePlaylists(all)
            if (Store.active() == null) Store.activeId = all.last().id
            Repo.reset(this)
        }
        return added
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_list)
        findViewById<TextView>(R.id.tvTitle).setText(R.string.playlists)
        wireBack()
        findViewById<RecyclerView>(R.id.rv).setup(LinearLayoutManager(this), ad)
        findViewById<View>(R.id.bar).visibility = View.VISIBLE
        findViewById<Button>(R.id.btnA).apply { setText(R.string.add_xtream); setOnClickListener { editor("xtream", null) } }
        findViewById<Button>(R.id.btnB).apply { setText(R.string.add_m3u); setOnClickListener { editor("m3u", null) } }
        findViewById<Button>(R.id.btnC).apply {
            visibility = View.VISIBLE; setText(R.string.import_json)
            setOnClickListener { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }
        }
        ad.onClick = { p, _ -> actions(p) }
        refresh()
        if (ad.itemCount == 0) findViewById<Button>(R.id.btnA).requestFocus()
    }

    private fun refresh() { ad.submit(Store.playlists()) }

    private fun actions(p: Playlist) {
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(arrayOf(getString(R.string.activate), getString(R.string.edit), getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> { Store.activeId = p.id; Repo.invalidateMemory(); refresh(); toast(R.string.done) }
                    1 -> editor(p.type, p)
                    2 -> {
                        val l = Store.playlists().filter { it.id != p.id }
                        Store.savePlaylists(l)
                        if (Store.activeId == p.id) Store.activeId = l.firstOrNull()?.id ?: ""
                        Repo.reset(this)
                        refresh()
                    }
                }
            }.show()
    }

    private fun editor(type: String, old: Playlist?) {
        val v = layoutInflater.inflate(R.layout.dialog_playlist, null)
        val etName = v.findViewById<EditText>(R.id.etName)
        val etUrl = v.findViewById<EditText>(R.id.etUrl)
        val etUser = v.findViewById<EditText>(R.id.etUser)
        val etPass = v.findViewById<EditText>(R.id.etPass)
        etUrl.setHint(if (type == "m3u") R.string.m3u_url else R.string.server_url)
        if (type == "m3u") { etUser.visibility = View.GONE; etPass.visibility = View.GONE }
        old?.let { etName.setText(it.name); etUrl.setText(it.url); etUser.setText(it.user); etPass.setText(it.pass) }

        AlertDialog.Builder(this)
            .setTitle(if (type == "m3u") R.string.add_m3u else R.string.add_xtream)
            .setView(v)
            .setPositiveButton(R.string.save) { _, _ ->
                var url = etUrl.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                if (url.startsWith("index.country", true)) url = "https://iptv-org.github.io/iptv/" + url.substringBefore("?")
                else if (!url.startsWith("http", true)) url = "http://$url"
                val name = etName.text.toString().trim().ifEmpty { if (type == "m3u") "M3U" else "Xtream" }
                var user = etUser.text.toString().trim(); var pass = etPass.text.toString().trim()
                if (type == "xtream") {
                    if (user.isEmpty()) xtreamCreds(url)?.let { user = it.first; pass = it.second }
                    url = normXtream(url)
                }
                val p = Playlist(old?.id ?: UUID.randomUUID().toString(), name, type, url, user, pass)
                val all = Store.playlists()
                Store.savePlaylists(if (old != null) all.map { if (it.id == p.id) p else it } else all + p)
                Store.activeId = p.id
                Repo.reset(this)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
