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

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslateClientTest {

    private class Script(vararg val replies: String) {
        val bodies = ArrayList<String>()
        fun transport(): (String, String) -> String = { _, body ->
            bodies += body
            replies[bodies.size - 1]
        }
    }

    private fun client(script: Script) = TranslateClient(transport = script.transport())

    @Test fun english_in_auto_mode_goes_to_chinese_in_one_request() {
        val script = Script("""[["你好世界"],["en"]]""")
        assertEquals("你好世界", client(script).translate("Hello world", TranslateMode.AUTO))
        assertEquals(listOf("""[[["Hello world"],"auto","zh-CN"],"wt_lib"]"""), script.bodies)
    }

    @Test fun chinese_in_auto_mode_goes_to_english_in_one_request() {
        val script = Script("""[["Hello World"],["zh-CN"]]""")
        assertEquals("Hello World", client(script).translate("你好，世界", TranslateMode.AUTO))
        assertEquals(listOf("""[[["你好，世界"],"auto","en"],"wt_lib"]"""), script.bodies)
    }

    @Test fun google_detection_overrides_the_local_guess_with_a_second_request() {
        val script = Script("""[["Tokyo"],["ja"]]""", """[["东京"],["ja"]]""")
        assertEquals("东京", client(script).translate("東京", TranslateMode.AUTO))
        assertEquals(
            listOf("""[[["東京"],"auto","en"],"wt_lib"]""", """[[["東京"],"auto","zh-CN"],"wt_lib"]"""),
            script.bodies,
        )
    }

    @Test fun zh_en_mode_passes_a_third_language_through_untouched() {
        val script = Script("""[["大家好"],["fr"]]""")
        assertEquals("Bonjour tout le monde", client(script).translate("Bonjour tout le monde", TranslateMode.ZH_EN))
        assertEquals(1, script.bodies.size)
    }

    @Test fun zh_ja_mode_translates_both_directions_and_passes_english_through() {
        val toJapanese = Script("""[["こんにちは"],["zh-CN"]]""")
        assertEquals("こんにちは", client(toJapanese).translate("你好", TranslateMode.ZH_JA))
        assertEquals(listOf("""[[["你好"],"auto","ja"],"wt_lib"]"""), toJapanese.bodies)

        val toChinese = Script("""[["你好"],["ja"]]""")
        assertEquals("你好", client(toChinese).translate("こんにちは", TranslateMode.ZH_JA))
        assertEquals(listOf("""[[["こんにちは"],"auto","zh-CN"],"wt_lib"]"""), toChinese.bodies)

        val english = Script("""[["你好"],["en"]]""")
        assertEquals("Hello", client(english).translate("Hello", TranslateMode.ZH_JA))
    }

    @Test fun untranslated_han_text_is_retried_with_an_explicit_chinese_source() {
        val script = Script("""[["你好嗎"],["en"]]""", """[["Are you OK"]]""")
        assertEquals("Are you OK", client(script).translate("你好嗎", TranslateMode.ZH_EN))
        assertEquals(
            listOf("""[[["你好嗎"],"auto","en"],"wt_lib"]""", """[[["你好嗎"],"zh-CN","en"],"wt_lib"]"""),
            script.bodies,
        )
        val japanese = Script("""[["你好嗎"],["en"]]""", """[["お元気ですか"]]""")
        assertEquals("お元気ですか", client(japanese).translate("你好嗎", TranslateMode.ZH_JA))
        assertEquals("""[[["你好嗎"],"zh-CN","ja"],"wt_lib"]""", japanese.bodies[1])
    }

    @Test fun han_text_google_reads_as_japanese_is_not_forced_into_chinese() {
        val script = Script("""[["東京"],["ja"]]""")
        assertEquals("東京", client(script).translate("東京", TranslateMode.ZH_EN))
        assertEquals(1, script.bodies.size)
    }

    @Test fun lines_are_sent_as_separate_segments_and_reassembled() {
        val script = Script("""[["第一行","第二行"],["en","en"]]""")
        assertEquals("第一行\n\n第二行", client(script).translate("line one\n\nline two", TranslateMode.AUTO))
        assertEquals(listOf("""[[["line one","line two"],"auto","zh-CN"],"wt_lib"]"""), script.bodies)
    }

    @Test fun markup_characters_are_escaped_on_the_way_out_and_unescaped_on_the_way_back() {
        val script = Script("""[["a &lt; b &amp; &quot;c&quot; &#39;d&#39; &gt; e"],["en"]]""")
        assertEquals("a < b & \"c\" 'd' > e", client(script).translate("a < b & \"c\" 'd' > e", TranslateMode.AUTO))
        assertEquals(listOf("""[[["a &lt; b &amp; \"c\" 'd' &gt; e"],"auto","zh-CN"],"wt_lib"]"""), script.bodies)
    }

    @Test fun blank_text_never_reaches_the_network() {
        val script = Script()
        assertEquals("  \n ", client(script).translate("  \n ", TranslateMode.AUTO))
        assertTrue(script.bodies.isEmpty())
    }

    @Test fun rejections_and_unreadable_bodies_surface_as_errors() {
        assertThrows(IOException::class.java) {
            client(Script("""[3,"Request contains an invalid argument."]""")).translate("hi", TranslateMode.AUTO)
        }
        assertThrows(IOException::class.java) {
            client(Script("<!DOCTYPE html><html>502</html>")).translate("hi", TranslateMode.AUTO)
        }
        assertThrows(IOException::class.java) {
            client(Script("""[["only one"],["en","en"]]""")).translate("a\nb", TranslateMode.AUTO)
        }
    }

    @Test fun abort_cuts_a_stalled_request_short() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/slow") { exchange ->
                Thread.sleep(8_000)
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
        }
        server.start()
        try {
            val client = TranslateClient(endpoint = "http://127.0.0.1:" + server.address.port + "/slow")
            val outcome = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val worker = Thread {
                outcome.set(runCatching { client.translate("hello", TranslateMode.AUTO) }.exceptionOrNull())
            }
            val started = System.currentTimeMillis()
            worker.start()
            Thread.sleep(400)
            client.abort()
            worker.join(3_000)
            assertTrue("the aborted request returns well before the server would", System.currentTimeMillis() - started < 5_000)
            assertTrue("the aborted request surfaces as an error", outcome.get() != null)
        } finally {
            server.stop(0)
        }
    }

    @Test fun the_transport_posts_the_body_with_the_public_key_headers() {
        val seen = ArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/translateHtml") { exchange ->
                seen += exchange.requestMethod
                seen += exchange.requestHeaders.getFirst("Content-Type")
                seen += exchange.requestHeaders.getFirst("X-Goog-API-Key")
                seen += exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val reply = """[["你好世界"],["en"]]""".toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, reply.size.toLong())
                exchange.responseBody.use { it.write(reply) }
                exchange.close()
            }
            createContext("/broken") { exchange ->
                exchange.sendResponseHeaders(502, -1)
                exchange.close()
            }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val client = TranslateClient(endpoint = "$base/v1/translateHtml")
            assertEquals("你好世界", client.translate("Hello world", TranslateMode.AUTO))
            assertEquals(
                listOf("POST", "application/json+protobuf", TranslateClient.API_KEY, """[[["Hello world"],"auto","zh-CN"],"wt_lib"]"""),
                seen,
            )
            assertThrows(IOException::class.java) {
                TranslateClient(endpoint = "$base/broken").translate("Hello world", TranslateMode.AUTO)
            }
        } finally {
            server.stop(0)
        }
    }
}
