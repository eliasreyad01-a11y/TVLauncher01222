package com.lite.iptv

import java.io.Serializable

data class Playlist(val id: String, val name: String, val type: String, val url: String, val user: String = "", val pass: String = "")

data class Channel(val id: String, val name: String, val logo: String, val url: String, val group: String)

data class Vod(
    val id: String, val name: String, val poster: String, val url: String, val group: String,
    val isSeries: Boolean, val year: String = "", val rating: String = "", val plot: String = "", val extra: String = "",
    val added: Long = 0
) : Serializable

data class Episode(val id: String, val season: Int, val num: Int, val title: String, val url: String)

/** One playable entry. Live zapping and episode auto-next both walk this queue. */
data class QItem(val id: String, val title: String, val sub: String, val url: String, val favKey: String = "")

object PlayQueue {
    var items: List<QItem> = emptyList()
    var index = 0
    var live = false
    fun set(live: Boolean, items: List<QItem>, index: Int) {
        this.live = live; this.items = items; this.index = index
    }
}
