package com.lite.iptv

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

/**
 * Live TV, and Sports (same list filtered to sports-looking categories).
 * OK on a channel plays it in the preview pane; OK again on the same channel opens full screen.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class LiveActivity : BrowseBase<Channel>() {

    override val screenKey get() = "live"
    override val titleRes get() = R.string.live
    override val itemLayout = R.layout.item_channel
    override fun makeLayoutManager(): RecyclerView.LayoutManager = LinearLayoutManager(this)
    override val hasPreview = true
    override suspend fun load() = Repo.live(applicationContext).filter { !Store.isHidden(favKey(it)) }
    // Built-in/M3U lists: world categories first, then Arab countries (Jordan first), then the other countries A-Z.
    // Xtream: same order as the provider's categories.
    override fun groupRank(g: String): Int {
        if (Store.active()?.type != "m3u") return Repo.rank("live", g)
        val w = WORLD.indexOf(g)
        if (w >= 0) return w
        if (g.startsWith("Arab Series & Movies")) return 8
        if (g == "Arab Kids & Cartoons") return 9
        val a = ARAB.indexOf(g)
        if (a >= 0) return 10 + a
        val l = g.lowercase().padEnd(3, ' ')
        return 1000 + l[0].code * 10000 + l[1].code * 100 + l[2].code
    }
    private val WORLD = listOf("News", "Sports", "Kids & Cartoons", "Movies & Series", "Documentary", "Music", "General")
    private val ARAB = listOf("Jordan", "Palestine", "Saudi Arabia", "United Arab Emirates", "Egypt", "Iraq", "Kuwait", "Qatar",
        "Bahrain", "Oman", "Lebanon", "Syria", "Yemen", "Libya", "Tunisia", "Algeria", "Morocco", "Sudan", "Mauritania")
    override fun groupOf(t: Channel) = t.group
    override fun nameOf(t: Channel) = t.name
    override fun favKey(t: Channel) = Store.favKey("L", t.id)

    override fun bind(v: View, t: Channel, i: Int) {
        v.findViewById<TextView>(R.id.tvNum).text = (i + 1).toString()
        v.findViewById<TextView>(R.id.tvName).text = t.name
        v.findViewById<View>(R.id.ivStar).visibility = if (Store.isFav(favKey(t))) View.VISIBLE else View.GONE
        val iv = v.findViewById<ImageView>(R.id.ivLogo)
        if (t.logo.isBlank()) { iv.dispose(); iv.setImageDrawable(null) } else iv.load(t.logo) { size(96) }
    }

    // ---------------- preview ----------------

    private var prev: ExoPlayer? = null
    private var prevCh: Channel? = null

    override fun onStart() {
        super.onStart()
        val pv = findViewById<PlayerView>(R.id.pvPreview)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Net.UA).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000).setReadTimeoutMs(12_000)
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) findViewById<TextView>(R.id.tvPrevStatus).text = ""
                else if (state == Player.STATE_BUFFERING) findViewById<TextView>(R.id.tvPrevStatus).setText(R.string.loading)
            }
            override fun onPlayerError(error: PlaybackException) {
                findViewById<TextView>(R.id.tvPrevStatus).setText(R.string.play_error)
            }
        })
        pv.player = p
        prev = p
        prevCh?.let { startPreview(it) }       // coming back from full screen: resume the same channel
    }

    override fun onStop() {
        findViewById<PlayerView>(R.id.pvPreview).player = null
        prev?.release()
        prev = null
        super.onStop()
    }

    private fun startPreview(c: Channel) {
        val p = prev ?: return
        prevCh = c
        findViewById<TextView>(R.id.tvPrevTitle).text = c.name
        val mi = MediaItem.Builder().setUri(c.url)
        if (c.url.contains(".m3u8", true)) mi.setMimeType(MimeTypes.APPLICATION_M3U8)
        p.setMediaItem(mi.build())
        p.prepare()
        p.playWhenReady = true
    }

    override fun open(list: List<Channel>, index: Int) {
        val c = list[index]
        if (prevCh?.url == c.url && prev != null) {
            prev?.stop()                        // many providers allow one connection: free it before full screen
            PlayQueue.set(true, list.map { QItem(it.id, it.name, it.group, it.url, favKey(it)) }, index)
            startActivity(Intent(this, PlayerActivity::class.java))
        } else startPreview(c)
    }

    override fun onLongItem(t: Channel): Boolean {
        val fav = Store.isFav(favKey(t))
        AlertDialog.Builder(this)
            .setTitle(t.name)
            .setItems(arrayOf(getString(if (fav) R.string.remove_fav else R.string.add_fav), getString(R.string.delete_channel))) { _, w ->
                if (w == 0) toggleFav(t)
                else {
                    if (prevCh?.url == t.url) { prev?.stop(); prevCh = null; findViewById<TextView>(R.id.tvPrevTitle).text = "" }
                    Store.hide(favKey(t))
                    removeItem(t)
                    toast(R.string.channel_deleted)
                }
            }.show()
        return true
    }
}
