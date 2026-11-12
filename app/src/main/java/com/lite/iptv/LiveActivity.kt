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
    override suspend fun load() = Repo.live(applicationContext)
        .filter { !Store.isHidden(favKey(it)) }
        .map { c -> Store.groupOverride(favKey(c))?.let { c.copy(group = it) } ?: c }
    // Built-in/M3U lists: world categories first, then Arab countries (Jordan first), then the other countries A-Z.
    // Xtream: same order as the provider's categories.
    override fun groupRank(g: String): Int {
        if (Store.active()?.type != "m3u") return Repo.rank("live", g)
        val w = WORLD.indexOf(g)
        if (w >= 0) return w
        val a = ARAB.indexOf(g)
        if (a >= 0) return 10 + a
        val l = g.lowercase().padEnd(3, ' ')
        return 1000 + l[0].code * 10000 + l[1].code * 100 + l[2].code
    }
    private val WORLD = listOf("News", "Sports", "Kids & Cartoons", "Arab Series & Movies", "World Movies", "Documentary", "Music", "General")
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
        iv.dispose(); iv.setImageDrawable(null)
        iv.tag = t.logo
        if (t.logo.isNotBlank()) iv.postDelayed({ if (iv.tag == t.logo && iv.isAttachedToWindow) iv.load(t.logo) { size(96) } }, 250)
    }

    // ---------------- preview ----------------

    private var prev: ExoPlayer? = null
    private var prevCh: Channel? = null
    private var httpF: DefaultHttpDataSource.Factory? = null

    private val prevListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) findViewById<TextView>(R.id.tvPrevStatus).text = ""
            else if (state == Player.STATE_BUFFERING) findViewById<TextView>(R.id.tvPrevStatus).setText(R.string.loading)
        }
        override fun onPlayerError(error: PlaybackException) {
            findViewById<TextView>(R.id.tvPrevStatus).setText(R.string.play_error)
        }
    }

    override fun onStart() {
        super.onStart()
        val pv = findViewById<PlayerView>(R.id.pvPreview)
        val shared = SharedPlayer.player
        if (shared != null) {                   // back from full screen: same player, same stream, no restart
            SharedPlayer.player = null
            httpF = SharedPlayer.http
            shared.addListener(prevListener)
            pv.player = shared
            prev = shared
            if (PlayQueue.live && PlayQueue.items.isNotEmpty()) {
                val q = PlayQueue.items[PlayQueue.index.coerceIn(0, PlayQueue.items.size - 1)]
                prevCh = Channel(q.id, q.title, "", q.url, q.sub)
                findViewById<TextView>(R.id.tvPrevTitle).text = q.title
            }
            focusItem(PlayQueue.index)
            if (shared.playbackState == Player.STATE_READY) findViewById<TextView>(R.id.tvPrevStatus).text = ""
            return
        }
        val http = DefaultHttpDataSource.Factory().also { httpF = it }
            .setUserAgent(Net.UA).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000).setReadTimeoutMs(12_000)
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
        p.addListener(prevListener)
        pv.player = p
        prev = p
        prevCh?.let { startPreview(it) }
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
        val (u, hd) = splitHeaders(c.url)
        httpF?.applyHeaders(hd)
        val mi = MediaItem.Builder().setUri(u)
        if (u.contains(".m3u8", true)) mi.setMimeType(MimeTypes.APPLICATION_M3U8)
        p.setMediaItem(mi.build())
        p.prepare()
        p.playWhenReady = true
    }

    override fun open(list: List<Channel>, index: Int) {
        val c = list[index]
        if (prevCh?.url == c.url && prev != null) {
            PlayQueue.set(true, list.map { QItem(it.id, it.name, it.group, it.url, favKey(it)) }, index)
            val p = prev!!                      // hand the running player to full screen: no reconnect, no black gap
            p.removeListener(prevListener)
            findViewById<PlayerView>(R.id.pvPreview).player = null
            SharedPlayer.player = p; SharedPlayer.http = httpF; SharedPlayer.url = c.url
            prev = null
            startActivity(Intent(this, PlayerActivity::class.java))
        } else startPreview(c)
    }

    override fun onLongItem(t: Channel): Boolean {
        val fav = Store.isFav(favKey(t))
        AlertDialog.Builder(this)
            .setTitle(t.name)
            .setItems(arrayOf(getString(if (fav) R.string.remove_fav else R.string.add_fav),
                getString(R.string.move_channel), getString(R.string.delete_channel))) { _, w ->
                when (w) {
                    0 -> toggleFav(t)
                    1 -> pickCategory(t)
                    else -> {
                        if (prevCh?.url == t.url) { prev?.stop(); prevCh = null; findViewById<TextView>(R.id.tvPrevTitle).text = "" }
                        Store.hide(favKey(t))
                        removeItem(t)
                        toast(R.string.channel_deleted)
                    }
                }
            }.show()
        return true
    }

    override fun onLongCat(key: String): Boolean {
        if (key.startsWith("*")) return false
        val items = groupItems(key)
        AlertDialog.Builder(this).setTitle(key)
            .setItems(arrayOf(getString(R.string.rename_category), getString(R.string.delete_category))) { _, w ->
                if (w == 0) {
                    val et = android.widget.EditText(this).apply { setText(key); setSelectAllOnFocus(true); setSingleLine() }
                    AlertDialog.Builder(this).setTitle(R.string.rename_category).setView(et)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val n = et.text.toString().trim()
                            if (n.isNotEmpty() && n != key) { Store.setGroups(items.map { favKey(it) }, n); reloadKeepCats() }
                        }.setNegativeButton(android.R.string.cancel, null).show()
                } else {
                    AlertDialog.Builder(this).setTitle(key)
                        .setMessage(getString(R.string.confirm_delete_category, items.size))
                        .setPositiveButton(R.string.delete_category) { _, _ ->
                            Store.hideMany(items.map { favKey(it) }); reloadKeepCats()
                            toast(R.string.channel_deleted)
                        }.setNegativeButton(android.R.string.cancel, null).show()
                }
            }.show()
        return true
    }

    /** "Move to category": choose an existing category or type a new one. */
    private fun pickCategory(t: Channel) {
        val names = groupNames().filter { it != t.group }
        val items = (names + getString(R.string.new_category)).toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.move_channel)
            .setItems(items) { _, i ->
                if (i < names.size) doMove(t, names[i])
                else {
                    val et = android.widget.EditText(this)
                    AlertDialog.Builder(this).setTitle(R.string.new_category).setView(et)
                        .setPositiveButton(android.R.string.ok) { _, _ -> et.text.toString().trim().takeIf { it.isNotEmpty() }?.let { doMove(t, it) } }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            }.show()
    }

    private fun doMove(t: Channel, g: String) {
        val i = curIndex(t)
        Store.setGroup(favKey(t), g)
        toast(getString(R.string.moved_to, g))
        rebuild(i)
    }
}
