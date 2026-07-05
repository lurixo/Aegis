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

/**
 * A backspace over a committed emoji used to leave half a surrogate that renders as � — the main key
 * did `deleteSurroundingText(1, 0)` (one UTF-16 unit) and even the panels' code-point ⌫ split ZWJ / flag /
 * keycap clusters. Both routes now delete the last grapheme cluster. These prove, by mechanical enumeration
 * (no sampling), that EVERY catalog emoji plus one of every multi-code-point KIND deletes to empty with no
 * lone surrogate left behind, that plain ASCII / Han deletes exactly one character, and — through a fake
 * editor that mirrors the service's two delete methods — that BOTH the main key and the panel ⌫ do so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GraphemeDeleteTest {

    /** A minimal editor mirroring InputConnection.getTextBeforeCursor + deleteSurroundingText. */
    private class FakeEditor(text: String) {
        val buf = StringBuilder(text)
        private fun before(n: Int) = buf.substring(maxOf(0, buf.length - n))
        // The service's deleteLastEditorCluster, verbatim in behavior.
        private fun deleteLastCluster() {
            val n = GraphemeText.lastClusterLength(before(GraphemeText.WINDOW))
            val take = if (n > 0) n else 1
            buf.delete(buf.length - take, buf.length)
        }
        fun mainKeyBackspace() = deleteLastCluster()   // service.deleteBackward → deleteLastEditorCluster
        fun panelBackspace() = deleteLastCluster()     // service.deleteGraphemeBackward → deleteLastEditorCluster
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

    /** One representative of every multi-code-point KIND the bug touched. */
    private val kinds = mapOf(
        "single-codepoint-BMP" to "©️",          // U+00A9 U+FE0F (VS16 on a BMP base)
        "surrogate-pair" to "😀",                 // U+1F600 (2 code units)
        "VS16-emoji" to "❤️",                     // U+2764 U+FE0F
        "keycap" to "0️⃣",                        // digit + VS16 + U+20E3
        "flag" to "🇨🇳",                          // two regional indicators
        "ZWJ-2" to "🏳️‍🌈",                        // ZWJ sequence
        "ZWJ-family" to "👨‍👩‍👧‍👦",                  // 11 code units
        "skin-tone" to "👋🏽",                     // base + modifier
        "gender-ZWJ" to "👩‍🚀",                    // woman + ZWJ + rocket
        "gender+skin" to "👨🏿‍⚕️",                  // man + modifier + ZWJ + medical + VS16
    )

    private fun everyEmoji(): List<Pair<String, String>> =
        EmojiCatalog.categories.flatMap { c -> c.emoji.map { c.id to it } } + kinds.map { it.key to it.value }

    @Test fun every_emoji_kind_and_catalog_entry_backspaces_to_empty_with_no_replacement_char() {
        for ((label, emoji) in everyEmoji()) {
            // committed on its own → main key ⌫ empties it, leaving no lone surrogate (no �).
            FakeEditor(emoji).let { e ->
                e.mainKeyBackspace()
                assertEquals("$label '$emoji' must delete whole via the main key", "", e.buf.toString())
                assertFalse("$label '$emoji' left a lone surrogate (renders as �)", hasLoneSurrogate(e.buf))
            }
            // committed after plain text → panel ⌫ removes only the emoji cluster, keeps the text.
            FakeEditor("ab$emoji").let { e ->
                e.panelBackspace()
                assertEquals("$label '$emoji' after text must delete only the emoji", "ab", e.buf.toString())
                assertFalse("$label '$emoji' left a lone surrogate after text", hasLoneSurrogate(e.buf))
            }
            // the whole cluster is exactly one grapheme.
            assertEquals("$label '$emoji' is a single grapheme cluster", emoji.length, GraphemeText.lastClusterLength(emoji))
        }
    }

    @Test fun every_reachable_variant_form_is_one_cluster_that_backspaces_clean() {
        // Not just the base cells: enumerate EVERY skin-toned and man/woman form the selector can commit
        // (cells ∪ gender forms ∪ each form's five skin tones) and prove each is a single grapheme cluster that
        // ⌫ removes whole with no lone surrogate. So committing ANY emoji the keyboard can produce, then one
        // backspace, always empties it — no sampling.
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
        // last-char is Han (BMP, 1 unit) and precedes nothing multi-unit.
        assertEquals(1, GraphemeText.lastClusterLength("好"))
        assertEquals(1, GraphemeText.lastClusterLength("a"))
        assertEquals(0, GraphemeText.lastClusterLength(""))
    }

    @Test fun both_delete_routes_in_the_service_use_the_cluster_helper() {
        // The service is too heavy to instantiate under Robolectric (parses the bundled dictionaries), so pin
        // at source that BOTH the main key (deleteBackward) and the panel ⌫ (deleteGraphemeBackward) delegate
        // to the single grapheme-cluster helper, and that helper uses GraphemeText — a refactor could not
        // silently revert one route to a code-unit / code-point deletion without failing this.
        val svc = File("src/main/java/com/aegis/ime/AegisInputMethodService.kt").readText()
        assertTrue("deleteBackward must delete a cluster", Regex("""override fun deleteBackward\(\)\s*\{[^}]*deleteLastEditorCluster\(\)""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(svc))
        assertTrue("deleteGraphemeBackward must delete a cluster", Regex("""override fun deleteGraphemeBackward\(\)\s*\{[^}]*deleteLastEditorCluster\(\)""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(svc))
        assertTrue("the helper must use GraphemeText.lastClusterLength", svc.contains("GraphemeText.lastClusterLength"))
        assertFalse("no route may fall back to a raw one-unit delete", svc.contains("deleteSurroundingText(1, 0)"))
        assertFalse("no route may fall back to a one-code-point delete", svc.contains("deleteSurroundingTextInCodePoints(1, 0)"))
        // The main keyboard's no-composing backspace still routes through the host.
        val kc = File("src/main/java/com/aegis/ime/ime/KeyboardController.kt").readText()
        assertTrue("KeyboardController backspace routes to host.deleteBackward", kc.contains("host.deleteBackward()"))
    }
}
