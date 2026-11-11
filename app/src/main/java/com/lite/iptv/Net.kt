package com.lite.iptv

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object Net {
    /** Many panels answer 403/404 to unknown clients, so look like a common player first. */
    const val UA = "VLC/3.0.20 LibVLC/3.0.20"
    private val ALT_UA = listOf(UA, "okhttp/4.12.0", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36")

    fun open(url: String): InputStream {
        var last: IOException? = null
        for (ua in ALT_UA) {
            try { return openWith(url, ua) } catch (e: HttpError) {
                last = e
                if (e.code !in listOf(403, 404, 406, 429, 458)) throw e    // other errors won't change with another client name
            }
        }
        throw last ?: IOException("No response")
    }

    class HttpError(val code: Int, host: String) : IOException("HTTP $code ($host)")

    private fun openWith(url: String, ua: String): InputStream {
        var u = url
        var redirects = 0
        while (true) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Accept-Encoding", "gzip")
            val code = c.responseCode
            if (code in 300..399 && redirects < 5) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw HttpError(code, URL(u).host)
                u = URL(URL(u), loc).toString()
                redirects++
                continue
            }
            if (code !in 200..299) { c.disconnect(); throw HttpError(code, URL(u).host) }
            val s = c.inputStream
            return if ("gzip".equals(c.contentEncoding, true)) GZIPInputStream(s) else s
        }
    }

    fun text(url: String): String = open(url).bufferedReader().use { it.readText() }

    /** Download to a temp file, then swap, so a failed refresh never destroys the previous cache. */
    fun download(url: String, f: File) {
        val tmp = File(f.path + ".tmp")
        open(url).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        f.delete()
        tmp.renameTo(f)
    }
}
