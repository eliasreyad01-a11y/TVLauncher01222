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
        val tiles: List<View> = listOf(R.id.tileLive, R.id.tileSports,
            R.id.tilePlaylist, R.id.btnSettings, R.id.btnReload, R.id.btnExit).map { findViewById<View>(it) }

        fun on(i: Int, f: () -> Unit) = tiles[i].setOnClickListener { Store.homeIndex = i; f() }
        on(0) { go(LiveActivity::class.java) }
        on(1) { go(LiveActivity::class.java) { putExtra("sports", true) } }
        on(2) { startActivity(Intent(this, PlaylistActivity::class.java)) }
        on(3) { startActivity(Intent(this, SettingsActivity::class.java)) }
        on(4) { reload() }
        on(5) { finishAffinity() }
        tiles[Store.homeIndex.coerceIn(0, 5)].requestFocus()   // remember last selected tile

        onBackPressedDispatcher.addCallback(this) {
            if (System.currentTimeMillis() - backAt < 2000) finishAffinity()
            else { backAt = System.currentTimeMillis(); toast(R.string.press_back_exit) }
        }
    }

    override fun onResume() {
        super.onResume()
        tvPlaylist.text = Store.active()?.name ?: getString(R.string.no_playlist)
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
