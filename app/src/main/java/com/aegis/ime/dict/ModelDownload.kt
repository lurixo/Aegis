package com.aegis.ime.dict

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Streams the optional enhancement model into filesDir/downloaded/ (picked up by the IME next session). */
object ModelDownload {

    /** Public wanxiang octagram LTS release. */
    const val GRAM_URL =
        "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/wanxiang-lts-zh-hans.gram"

    const val GRAM_NAME = "wanxiang-lts-zh-hans.gram"

    fun destFile(filesDir: File): File = File(File(filesDir, "downloaded"), GRAM_NAME)

    fun isDownloaded(filesDir: File): Boolean = destFile(filesDir).let { it.exists() && it.length() > 1024 }

    /**
     * Download [url] to [dest] (via a .part temp + atomic rename), reporting (bytesDone, total).
     * Blocking — call off the main thread. Returns true on success.
     */
    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit): Boolean {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
            }
            if (conn.responseCode !in 200..299) return false
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            dest.delete()
            tmp.renameTo(dest)
        } catch (e: Exception) {
            tmp.delete()
            false
        } finally {
            conn?.disconnect()
        }
    }
}
