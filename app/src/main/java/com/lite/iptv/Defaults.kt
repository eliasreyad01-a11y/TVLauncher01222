package com.lite.iptv

/** Playlists added on first launch. The first is a copy of the free iptv-org list bundled in the app; the second refreshes online. */
object Defaults {
    fun list() = listOf(
        Playlist("m3u0", "Free channels (built-in)", "m3u", "asset:index.country.m3u"),
        Playlist("m3u1", "Free channels (online)", "m3u", "https://iptv-org.github.io/iptv/index.country.m3u")
    )
}
