// SPDX-License-Identifier: GPL-3.0-only
//
// Copyright (C) 2026 lurixo
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, version 3.
//
// This program is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
// PARTICULAR PURPOSE. See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with
// this program. If not, see <https://www.gnu.org/licenses/>.

package com.aegis.ime.translate

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray

class TranslateClient(
    private val endpoint: String = ENDPOINT,
    private val transport: ((String, String) -> String)? = null,
) {
    class Response(val translations: List<String>, val detected: List<String>)

    @Volatile private var live: HttpURLConnection? = null

    fun abort() {
        live?.disconnect()
    }

    fun translate(text: String, mode: TranslateMode): String {
        val lines = text.split('\n')
        val sent = lines.indices.filter { lines[it].isNotBlank() }
        if (sent.isEmpty()) return text
        val firstTarget = TranslateDirection.firstTarget(mode, text)
        val first = request(sent.map(lines::get), AUTO_SOURCE, firstTarget)
        val unchanged = sent.indices.all { first.translations[it].trim() == lines[sent[it]].trim() }
        val detected = dominant(first.detected)
        if (unchanged && TranslateDirection.hasHan(text) && TranslateDirection.misreadHan(detected)) {
            val retry = request(sent.map(lines::get), TranslateDirection.CHINESE, TranslateDirection.chineseTarget(mode))
            return merge(lines, sent, retry.translations)
        }
        val wanted = TranslateDirection.wantedTarget(mode, detected) ?: return text
        if (wanted == firstTarget) return merge(lines, sent, first.translations)
        val second = request(sent.map(lines::get), AUTO_SOURCE, wanted)
        return merge(lines, sent, second.translations)
    }

    private fun request(segments: List<String>, source: String, target: String): Response {
        val body = JSONArray().put(
            JSONArray().put(JSONArray(segments.map(::escapeHtml))).put(source).put(target),
        ).put(LIB).toString()
        val response = parse(transport?.invoke(endpoint, body) ?: post(endpoint, body))
        if (response.translations.size != segments.size) throw IOException("translation count mismatch")
        return response
    }

    private fun merge(lines: List<String>, sent: List<Int>, translations: List<String>): String {
        val out = lines.toMutableList()
        sent.forEachIndexed { i, index -> out[index] = translations[i] }
        return out.joinToString("\n")
    }

    private fun dominant(detected: List<String>): String =
        detected.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()

    companion object {
        const val ENDPOINT = "https://translate-pa.googleapis.com/v1/translateHtml"
        const val API_KEY = "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520"
        private const val AUTO_SOURCE = "auto"
        private const val LIB = "wt_lib"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 10_000

        fun parse(body: String): Response {
            val root = runCatching { JSONArray(body) }.getOrElse { throw IOException("unreadable translation response") }
            val translations = root.optJSONArray(0) ?: throw IOException("translation rejected: $body")
            val detected = root.optJSONArray(1)
            return Response(
                List(translations.length()) { unescapeHtml(translations.getString(it)) },
                if (detected == null) emptyList() else List(detected.length()) { detected.optString(it) },
            )
        }

        fun escapeHtml(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        fun unescapeHtml(text: String): String =
            text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    }

    private fun post(endpoint: String, body: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json+protobuf")
                setRequestProperty("X-Goog-API-Key", API_KEY)
            }
            live = conn
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            live = null
            conn?.disconnect()
        }
    }
}
