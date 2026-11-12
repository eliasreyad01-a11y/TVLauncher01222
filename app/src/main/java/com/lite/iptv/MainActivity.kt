package com.lite.iptv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private var backAt = 0L
    private lateinit var tvPlaylist: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        tvPlaylist = findViewById(R.id.tvPlaylist)
        val tiles: List<View> = listOf(R.id.tileLive,
            R.id.btnSettings, R.id.btnReload, R.id.btnExit).map { findViewById<View>(it) }

        fun on(i: Int, f: () -> Unit) = tiles[i].setOnClickListener { Store.homeIndex = i; f() }
        on(0) { go(LiveActivity::class.java) }
        on(1) { startActivity(Intent(this, SettingsActivity::class.java)) }
        on(2) { reload() }
        on(3) { finishAffinity() }
        tiles[Store.homeIndex.coerceIn(0, 3)].requestFocus()   // remember last selected tile

        onBackPressedDispatcher.addCallback(this) {
            if (System.currentTimeMillis() - backAt < 2000) finishAffinity()
            else { backAt = System.currentTimeMillis(); toast(R.string.press_back_exit) }
        }
    }

    override fun onResume() {
        super.onResume()
        val crash = Store.getStr("last_crash", "")
        if (crash.isNotEmpty()) {
            Store.setStr("last_crash", "")
            val tv = TextView(this).apply { text = crash; textSize = 11f; setTextColor(0xFFFFFFFF.toInt()); setPadding(32, 16, 32, 16); setTextIsSelectable(true) }
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Crash report (" + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }) + ")")
                .setView(android.widget.ScrollView(this).apply { addView(tv) })
                .setPositiveButton(android.R.string.ok, null).show()
        }
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        tvPlaylist.text = (Store.active()?.name ?: getString(R.string.no_playlist)) + "   •   " + ver
    }

    private fun go(cls: Class<*>, ex: Intent.() -> Unit = {}) {
        if (Store.active() == null) {
            Toast.makeText(this, R.string.no_playlist, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, PlaylistActivity::class.java))
            return
        }
        startActivity(Intent(this, cls).apply(ex))
    }

    private fun reload() {
        if (Store.active() == null) { go(LiveActivity::class.java); return }
        Repo.reset(this)
        toast(R.string.reloading)
        lifecycleScope.launch {
            try {
                toast(getString(R.string.loaded_n, Repo.live(applicationContext).size))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { toast(getString(R.string.error_load, e.message ?: "")) }
        }
    }
}
