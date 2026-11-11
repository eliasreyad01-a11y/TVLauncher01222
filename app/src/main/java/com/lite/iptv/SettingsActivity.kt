package com.lite.iptv

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Minimal settings: playlists, language, player options, refresh, clear cache, about. */
class SettingsActivity : AppCompatActivity() {
    private class Row(val title: Int, val value: () -> String, val action: () -> Unit)

    private lateinit var rows: List<Row>
    private val ad = Adapter<Row>(R.layout.item_setting) { v, r, _ ->
        v.findViewById<TextView>(R.id.tvTitle).setText(r.title)
        v.findViewById<TextView>(R.id.tvValue).text = r.value()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_list)
        findViewById<TextView>(R.id.tvTitle).setText(R.string.settings)
        wireBack()
        rows = listOf(
            Row(R.string.playlists, { "" }) { startActivity(Intent(this, PlaylistActivity::class.java)) },
            Row(R.string.language, {
                getString(when (Store.lang) { "en" -> R.string.lang_en; "ar" -> R.string.lang_ar; else -> R.string.lang_auto })
            }) {
                Store.lang = when (Store.lang) { "" -> "en"; "en" -> "ar"; else -> "" }
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(Store.lang))
            },
            Row(R.string.live_format, { if (Store.liveTs) "TS" else "HLS (m3u8)" }) {
                Store.liveTs = !Store.liveTs; Repo.invalidateMemory()
            },
            Row(R.string.buffer, {
                getString(when (Store.buffer) { 0 -> R.string.buf_low; 2 -> R.string.buf_high; else -> R.string.buf_normal })
            }) { Store.buffer = (Store.buffer + 1) % 3 },
            Row(R.string.auto_reconnect, { getString(if (Store.reconnect) R.string.on else R.string.off) }) { Store.reconnect = !Store.reconnect },
            Row(R.string.restore_deleted, { Store.hiddenCount().toString() }) {
                val n = Store.hiddenCount(); Store.clearHidden(); toast(getString(R.string.restored_n, n))
            },
            Row(R.string.refresh_playlist, { "" }) { Repo.reset(this); toast(R.string.done) },
            Row(R.string.clear_cache, { "" }) { clearCache() },
            Row(R.string.about, { "" }) { about() }
        )
        findViewById<RecyclerView>(R.id.rv).setup(LinearLayoutManager(this), ad)
        ad.submit(rows)
        ad.onClick = { r, i -> r.action(); ad.notifyItemChanged(i) }
    }

    private fun clearCache() {
        Repo.reset(this)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                applicationContext.imageLoader.memoryCache?.clear()
                applicationContext.imageLoader.diskCache?.clear()
            }
            toast(R.string.done)
        }
    }

    private fun about() {
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.about_text, ver, Store.active()?.name ?: "-"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
