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

package com.aegis.ime.ime

import com.aegis.ime.layout.EmojiCatalog
import com.aegis.ime.layout.EmojiVariants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GraphemeDeleteTest {

    private class FakeEditor(text: String) {
        val buf = StringBuilder(text)
        private fun before(n: Int) = buf.substring(maxOf(0, buf.length - n))
        private fun deleteLastCluster() {
            val n = GraphemeText.lastClusterLength(::before)
            val take = if (n > 0) n else 1
            buf.delete(buf.length - take, buf.length)
        }
        fun mainKeyBackspace() = deleteLastCluster()
        fun panelBackspace() = deleteLastCluster()
    }

    private fun hasLoneSurrogate(s: CharSequence): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                Character.isHighSurrogate(c) -> {
                    if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return true
                    i += 2
                }
                Character.isLowSurrogate(c) -> return true
                else -> i++
            }
        }
        return false
    }

    private val kinds = mapOf(
        "single-codepoint-BMP" to "©️",
        "surrogate-pair" to "😀",
        "VS16-emoji" to "❤️",
        "keycap" to "0️⃣",
        "flag" to "🇨🇳",
        "ZWJ-2" to "🏳️‍🌈",
        "ZWJ-family" to "👨‍👩‍👧‍👦",
        "skin-tone" to "👋🏽",
        "gender-ZWJ" to "👩‍🚀",
        "gender+skin" to "👨🏿‍⚕️",
    )

    private fun everyEmoji(): List<Pair<String, String>> =
        EmojiCatalog.categories.flatMap { c -> c.emoji.map { c.id to it } } + kinds.map { it.key to it.value }

    @Test fun every_emoji_kind_and_catalog_entry_backspaces_to_empty_with_no_replacement_char() {
        for ((label, emoji) in everyEmoji()) {
            FakeEditor(emoji).let { e ->
                e.mainKeyBackspace()
                assertEquals("$label '$emoji' must delete whole via the main key", "", e.buf.toString())
                assertFalse("$label '$emoji' left a lone surrogate (renders as �)", hasLoneSurrogate(e.buf))
            }
            FakeEditor("ab$emoji").let { e ->
                e.panelBackspace()
                assertEquals("$label '$emoji' after text must delete only the emoji", "ab", e.buf.toString())
                assertFalse("$label '$emoji' left a lone surrogate after text", hasLoneSurrogate(e.buf))
            }
            assertEquals("$label '$emoji' is a single grapheme cluster", emoji.length, GraphemeText.lastClusterLength(emoji))
        }
    }

    @Test fun every_reachable_variant_form_is_one_cluster_that_backspaces_clean() {
        val forms = LinkedHashSet<String>()
        for (c in EmojiCatalog.categories.flatMap { it.emoji }) {
            for (g in EmojiVariants.genderForms(c)) forms.addAll(EmojiVariants.skinForms(g))
        }
        assertTrue("expected the full reachable variant set", forms.size > 3000)
        for (f in forms) {
            assertEquals("'$f' is not a single grapheme cluster", f.length, GraphemeText.lastClusterLength(f))
            val e = FakeEditor("x$f")
            e.mainKeyBackspace()
            assertEquals("'$f' must delete whole", "x", e.buf.toString())
            assertFalse("'$f' left a lone surrogate (renders as �)", hasLoneSurrogate(e.buf))
        }
    }

    @Test fun plain_text_still_deletes_exactly_one_character() {
        for (s in listOf("ab", "中文", "a中", "1中2", "hello世界")) {
            val e = FakeEditor(s)
            e.mainKeyBackspace()
            assertEquals("plain '$s' must lose exactly its last char", s.substring(0, s.length - 1), e.buf.toString())
        }
        assertEquals(1, GraphemeText.lastClusterLength("好"))
        assertEquals(1, GraphemeText.lastClusterLength("a"))
        assertEquals(0, GraphemeText.lastClusterLength(""))
    }

    @Test fun clustersBeyondTheInitialWindowDeleteAsOneUnit() {
        val zwj = buildString {
            repeat(40) { index ->
                if (index > 0) append('\u200D')
                append("👩")
            }
        }
        val combining = "a" + "\u0301".repeat(100)
        for (cluster in listOf(zwj, combining)) {
            assertTrue(cluster.length > GraphemeText.WINDOW)
            assertEquals(cluster.length, GraphemeText.lastClusterLength(cluster))
            val editor = FakeEditor("x$cluster")
            editor.mainKeyBackspace()
            assertEquals("x", editor.buf.toString())
        }
    }

    @Test fun regionalIndicatorParityIsResolvedBeyondTheInitialWindow() {
        val flags = "🇨🇳".repeat(40)
        val editor = FakeEditor(flags)
        editor.mainKeyBackspace()
        assertEquals("🇨🇳".repeat(39), editor.buf.toString())
    }

    @Test fun both_delete_routes_in_the_service_use_the_cluster_helper() {
        val svc = File("src/main/java/com/aegis/ime/AegisInputMethodService.kt").readText()
        assertTrue("deleteBackward must delete a cluster", Regex("""override fun deleteBackward\(\)\s*\{[^}]*deleteLastEditorCluster\(\)""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(svc))
        assertTrue("deleteGraphemeBackward must delete a cluster", Regex("""override fun deleteGraphemeBackward\(\)\s*\{[^}]*deleteLastEditorCluster\(\)""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(svc))
        assertTrue("the helper must use GraphemeText.lastClusterLength", svc.contains("GraphemeText.lastClusterLength"))
        assertFalse("no route may fall back to a raw one-unit delete", svc.contains("deleteSurroundingText(1, 0)"))
        assertFalse("no route may fall back to a one-code-point delete", svc.contains("deleteSurroundingTextInCodePoints(1, 0)"))
        val kc = File("src/main/java/com/aegis/ime/ime/KeyboardController.kt").readText()
        assertTrue("KeyboardController backspace routes to host.deleteBackward", kc.contains("host.deleteBackward()"))
    }
}
