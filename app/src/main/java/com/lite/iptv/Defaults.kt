package com.lite.iptv

/** Built-in list: Arab-world channels plus well-known international ones (a trimmed copy of the free iptv-org list). */
object Defaults {
    const val VERSION = 9
    const val ID = "m3u9"
    fun list() = listOf(Playlist(ID, "Arab & world channels", "m3u", "asset:index.country.m3u"))
}
