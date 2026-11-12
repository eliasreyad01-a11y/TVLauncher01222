package com.lite.iptv

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.Locale

/**
 * Full-screen player. Remote map:
 *  Live:  Up/Down or CH+/CH- = zap, OK/Menu = controls.      VOD: Left/Right = seek (hold = faster), OK = pause + controls.
 *  Back closes controls first, then leaves.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private class Opt(val label: String, val group: Tracks.Group?, val idx: Int, val kind: Int, val selected: Boolean)

    private val h = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var httpF: DefaultHttpDataSource.Factory? = null
    private var ok = false
    private var retry = 0
    private var retryRun: Runnable? = null
    private var subUrl: String? = null
    private var ticks = 0
    private var aspect = 0
    private val live: Boolean get() = PlayQueue.live

    private lateinit var pv: PlayerView
    private lateinit var status: View
    private lateinit var spinner: View
    private lateinit var tvStatus: TextView
    private lateinit var tvSeek: TextView
    private lateinit var infoBar: View
    private lateinit var controls: View
    private lateinit var tvTitle: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var pbar: ProgressBar
    private lateinit var btnPlay: Button
    private lateinit var btnFav: Button

    private val hideRun = Runnable { controls.visibility = View.GONE; infoBar.visibility = View.GONE }
    private val hideSeek = Runnable { tvSeek.visibility = View.GONE }
    private val ticker = object : Runnable {
        override fun run() { onTick(); h.postDelayed(this, 1000) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (PlayQueue.items.isEmpty()) { finish(); return }
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        pv = findViewById(R.id.pv); status = findViewById(R.id.status); spinner = findViewById(R.id.spinner)
        tvStatus = findViewById(R.id.tvStatus); tvSeek = findViewById(R.id.tvSeek)
        infoBar = findViewById(R.id.infoBar); controls = findViewById(R.id.controls)
        tvTitle = findViewById(R.id.tvTitle); tvSub = findViewById(R.id.tvSub); tvInfo = findViewById(R.id.tvInfo)
        tvPos = findViewById(R.id.tvPos); tvDur = findViewById(R.id.tvDur); pbar = findViewById(R.id.pbar)
        btnPlay = findViewById(R.id.btnPlay); btnFav = findViewById(R.id.btnFav)

        val btnRew = findViewById<Button>(R.id.btnRew)
        val btnFwd = findViewById<Button>(R.id.btnFwd)
        if (live) {
            btnRew.visibility = View.GONE; btnFwd.visibility = View.GONE
            findViewById<View>(R.id.progRow).visibility = View.GONE
            findViewById<View>(R.id.tvLive).visibility = View.VISIBLE
        } else btnFav.visibility = View.GONE

        btnRew.setOnClickListener { seekBy(-10_000); keepControls() }
        btnFwd.setOnClickListener { seekBy(10_000); keepControls() }
        btnPlay.setOnClickListener { toggle(); keepControls() }
        findViewById<Button>(R.id.btnAudio).setOnClickListener { pickTracks(C.TRACK_TYPE_AUDIO, R.string.audio) }
        findViewById<Button>(R.id.btnSubs).setOnClickListener { pickTracks(C.TRACK_TYPE_TEXT, R.string.subtitles) }
        findViewById<Button>(R.id.btnQuality).setOnClickListener { pickTracks(C.TRACK_TYPE_VIDEO, R.string.quality) }
        findViewById<Button>(R.id.btnAspect).setOnClickListener {
            val modes = intArrayOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL)
            aspect = (aspect + 1) % modes.size
            pv.resizeMode = modes[aspect]
            keepControls()
        }
        btnFav.setOnClickListener {
            val q = PlayQueue.items[PlayQueue.index]
            if (q.favKey.isNotEmpty()) { Store.toggleFav(q.favKey); updateMeta() }
            keepControls()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (controls.visibility == View.VISIBLE) hideRun.run()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        ok = true
    }

    override fun onStart() {
        super.onStart()
        if (!ok) return
        initPlayer()
        playIndex(PlayQueue.index)
        h.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        if (ok && isFinishing && live) {         // leaving full screen: give the running player back to the list screen
            val p = player
            if (p != null) {
                h.removeCallbacksAndMessages(null)
                p.removeListener(listener)
                pv.player = null
                SharedPlayer.player = p; SharedPlayer.http = httpF
                player = null
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!ok) return
        saveProgress()
        h.removeCallbacksAndMessages(null)
        pv.player = null
        player?.release()
        player = null
    }

    // ---------------- player setup ----------------

    private var adopted = false

    private fun initPlayer() {
        SharedPlayer.player?.let { p ->
            adopted = true
            httpF = SharedPlayer.http
            SharedPlayer.player = null
            p.addListener(listener)
            pv.player = p
            player = p
            if (p.playbackState == Player.STATE_BUFFERING) showStatus(getString(R.string.loading), true)
            return
        }
        val http = DefaultHttpDataSource.Factory().also { httpF = it }
            .setUserAgent(Net.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(12_000)
        val b = when (Store.buffer) {          // min, max, start, after-rebuffer (ms): small buffers = low RAM + fast zapping
            0 -> intArrayOf(3_000, 10_000, 500, 1_000)
            2 -> intArrayOf(20_000, 60_000, 2_000, 5_000)
            else -> intArrayOf(10_000, 30_000, 1_000, 2_500)
        }
        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(b[0], b[1], b[2], b[3])
            .setTargetBufferBytes(24 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val p = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(lc)
            .build()
        p.addListener(listener)
        pv.player = p
        player = p
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> showStatus(getString(R.string.loading), true)
                Player.STATE_READY -> { retry = 0; status.visibility = View.GONE }
                Player.STATE_ENDED -> onEnded()
            }
        }
        override fun onPlayerError(error: PlaybackException) = handleError(error)
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            btnPlay.setText(if (playWhenReady) R.string.pause else R.string.play)
        }
    }

    private fun playIndex(i: Int, startMs: Long? = null) {
        val p = player ?: return
        val q = PlayQueue.items[i]
        PlayQueue.index = i
        if (adopted) {
            adopted = false
            if (q.url == SharedPlayer.url) { if (live) Store.setStr("last_live", q.id); updateMeta(); return }
        }
        retryRun?.let { h.removeCallbacks(it) }
        retry = 0
        val (u, hd) = splitHeaders(q.url)
        httpF?.applyHeaders(hd)
        val mi = MediaItem.Builder().setUri(u)
        if (u.contains(".m3u8", true)) mi.setMimeType(MimeTypes.APPLICATION_M3U8)
        subUrl?.let { u ->
            val mime = if (u.contains(".vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
            mi.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(u)).setMimeType(mime).setLanguage("und")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
        }
        val start = startMs ?: if (live) C.TIME_UNSET else Store.progress(q.url).let { if (it > 3000) it - 3000 else C.TIME_UNSET }
        p.setMediaItem(mi.build(), start)
        p.prepare()
        p.play()
        if (live) Store.setStr("last_live", q.id)
        updateMeta()
    }

    private fun step(d: Int) {
        val n = PlayQueue.items.size
        if (n <= 1) return
        saveProgress()
        var i = PlayQueue.index + d
        if (i < 0) i = n - 1
        if (i >= n) i = 0
        playIndex(i)
        showBanner()
    }

    private fun onEnded() {
        if (live) return
        saveProgress()
        if (PlayQueue.index + 1 < PlayQueue.items.size) playIndex(PlayQueue.index + 1) else finish()
    }

    private fun handleError(e: PlaybackException) {
        val p = player ?: return
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) { p.seekToDefaultPosition(); p.prepare(); return }
        if (Store.reconnect && retry < 6) {
            retry++
            showStatus(getString(R.string.reconnecting, retry), true)
            val r = Runnable {
                val pl = player ?: return@Runnable
                if (live) pl.seekToDefaultPosition()
                pl.prepare(); pl.play()
            }
            retryRun = r
            h.postDelayed(r, minOf(1500L * retry, 8000L))
        } else showStatus(getString(R.string.play_error), false)
    }

    private fun showStatus(t: String, spin: Boolean) {
        status.visibility = View.VISIBLE
        spinner.visibility = if (spin) View.VISIBLE else View.GONE
        tvStatus.text = t
    }

    // ---------------- overlay ----------------

    private fun updateMeta() {
        val q = PlayQueue.items[PlayQueue.index]
        tvTitle.text = q.title
        tvSub.text = if (live) "${PlayQueue.index + 1}/${PlayQueue.items.size}  •  ${q.sub}" else q.sub
        if (q.favKey.isNotEmpty()) btnFav.setText(if (Store.isFav(q.favKey)) R.string.remove_fav else R.string.add_fav)
    }

    private fun showControls() {
        updateMeta()
        infoBar.visibility = View.VISIBLE
        controls.visibility = View.VISIBLE
        btnPlay.requestFocus()
        keepControls()
    }

    private fun keepControls() { h.removeCallbacks(hideRun); h.postDelayed(hideRun, 7000) }

    private fun showBanner() {
        updateMeta()
        infoBar.visibility = View.VISIBLE
        if (controls.visibility != View.VISIBLE) { h.removeCallbacks(hideRun); h.postDelayed(hideRun, 3500) }
    }

    private fun onTick() {
        val p = player ?: return
        if (infoBar.visibility == View.VISIBLE) {
            val vs = p.videoSize
            tvInfo.text = if (vs.height > 0) "${vs.width}x${vs.height}" else ""
        }
        if (controls.visibility == View.VISIBLE && !live) {
            val d = p.duration
            val pos = p.currentPosition
            if (d > 0) pbar.progress = (pos * 1000 / d).toInt()
            tvPos.text = fmtTime(pos); tvDur.text = if (d > 0) fmtTime(d) else ""
        }
        if (!live && ++ticks % 10 == 0) saveProgress()
    }

    private fun saveProgress() {
        val p = player ?: return
        if (live || p.duration <= 0) return
        Store.saveProgress(PlayQueue.items[PlayQueue.index].url, p.currentPosition, p.duration)
    }

    // ---------------- keys ----------------

    private fun toggle() { val p = player ?: return; if (p.playWhenReady) p.pause() else p.play() }

    private fun seekBy(ms: Long) {
        val p = player ?: return
        if (live) return
        val d = p.duration
        var t = (p.currentPosition + ms).coerceAtLeast(0)
        if (d > 0) t = t.coerceAtMost(d - 1000)
        p.seekTo(t)
        tvSeek.text = (if (ms > 0) "+" else "-") + (Math.abs(ms) / 1000) + "s   " + fmtTime(t)
        tvSeek.visibility = View.VISIBLE
        h.removeCallbacks(hideSeek); h.postDelayed(hideSeek, 1000)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (!ok || e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val k = e.keyCode
        when (k) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { toggle(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(30_000); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-30_000); return true }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> { step(1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { step(-1); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> { showControls(); return true }
        }
        if (controls.visibility == View.VISIBLE) { keepControls(); return super.dispatchKeyEvent(e) }
        when (k) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (live) { finish(); return true }   // OK on a live channel goes back to the lists
                toggle()
                showControls(); return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> { if (live) step(-1) else showControls(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (live) step(1) else showControls(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (live) showControls() else {
                    val d = if (k == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                    seekBy(d * when { e.repeatCount > 12 -> 60_000L; e.repeatCount > 4 -> 30_000L; else -> 10_000L })
                }
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    // ---------------- audio / subtitles / quality ----------------

    private fun label(f: Format, type: Int, n: Int): String = when (type) {
        C.TRACK_TYPE_VIDEO -> if (f.height > 0) "${f.height}p" + (if (f.frameRate > 0) "  ${f.frameRate.toInt()}fps" else "") else getString(R.string.track_n, n)
        else -> {
            val lang = f.language?.takeIf { it != "und" }?.let { Locale(it).displayLanguage }?.takeIf { it.isNotBlank() }
            val ch = if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) " (${f.channelCount}ch)" else ""
            f.label ?: ((lang ?: getString(R.string.track_n, n)) + ch)
        }
    }

    private fun pickTracks(type: Int, title: Int) {
        val p = player ?: return
        h.removeCallbacks(hideRun)
        val opts = ArrayList<Opt>()
        val groups = p.currentTracks.groups.filter { it.type == type && it.isSupported }
        val off = p.trackSelectionParameters.disabledTrackTypes.contains(type)
        val overridden = p.trackSelectionParameters.overrides.values.any { it.type == type }
        when (type) {
            C.TRACK_TYPE_TEXT -> opts.add(Opt(getString(R.string.sub_off), null, -1, 2, off))
            C.TRACK_TYPE_VIDEO -> opts.add(Opt(getString(R.string.auto), null, -1, 1, !overridden))
        }
        var n = 0
        val tracks = ArrayList<Triple<Tracks.Group, Int, Format>>()
        for (g in groups) for (i in 0 until g.length) tracks.add(Triple(g, i, g.getTrackFormat(i)))
        if (type == C.TRACK_TYPE_VIDEO) tracks.sortByDescending { it.third.height }
        for ((g, i, f) in tracks) { n++; opts.add(Opt(label(f, type, n), g, i, 0, g.isTrackSelected(i) && !off && (type != C.TRACK_TYPE_VIDEO || overridden))) }
        if (type == C.TRACK_TYPE_TEXT) opts.add(Opt(getString(R.string.sub_external), null, -1, 3, false))
        if (opts.isEmpty() || (type != C.TRACK_TYPE_TEXT && tracks.isEmpty())) { toast(R.string.none_available); keepControls(); return }

        val dlg = AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(opts.map { it.label }.toTypedArray(), opts.indexOfFirst { it.selected }) { d, which ->
                d.dismiss()
                applyOpt(type, opts[which])
            }
            .setOnDismissListener { keepControls() }
            .create()
        dlg.show()
    }

    private fun applyOpt(type: Int, o: Opt) {
        val p = player ?: return
        val pb = p.trackSelectionParameters.buildUpon()
        when (o.kind) {
            0 -> pb.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(o.group!!.mediaTrackGroup as TrackGroup, o.idx))
            1 -> pb.clearOverridesOfType(type)
            2 -> pb.setTrackTypeDisabled(type, true)
            3 -> { askSubtitleUrl(); return }
        }
        p.trackSelectionParameters = pb.build()
    }

    private fun askSubtitleUrl() {
        val et = EditText(this).apply { setHint(R.string.sub_url_hint); setSingleLine(); setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF9FB3C8.toInt()) }
        AlertDialog.Builder(this)
            .setTitle(R.string.sub_external)
            .setView(et)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val u = et.text.toString().trim()
                if (u.isNotEmpty()) {
                    subUrl = u
                    val pos = player?.currentPosition ?: 0L
                    player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build() }
                    playIndex(PlayQueue.index, if (live) C.TIME_UNSET else pos)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { keepControls() }
            .show()
    }
}
