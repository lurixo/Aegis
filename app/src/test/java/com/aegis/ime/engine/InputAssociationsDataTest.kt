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

package com.aegis.ime.engine

import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.layout.EmojiCatalog
import com.aegis.ime.layout.SymbolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U23 data expansion: no-sampling audits of the association tables. Every assertion here walks the
 * WHOLE table / the WHOLE catalog — never a sample — so a single missing emoji keyword, invalid pinyin key
 * or silently skipped symbol fails the build mechanically.
 */
class InputAssociationsDataTest {

    // ------------------------------------------------------------------ key validity (full table)

    @Test fun every_key_is_lowercase_ascii_letters_only() {
        for (key in InputAssociations.entriesForTest().keys) {
            assertTrue("key '$key' must be non-empty a-z", key.isNotEmpty() && key.all { it in 'a'..'z' })
        }
    }

    @Test fun every_key_is_a_valid_toneless_full_pinyin_sequence() {
        for (key in InputAssociations.entriesForTest().keys) {
            assertTrue(
                "key '$key' must segment into whole Mandarin syllables (no jianpin, no prefixes)",
                T9Pinyin.segmentLetters(key) != null,
            )
        }
    }

    @Test fun every_entry_has_glyphs_and_no_duplicates() {
        for ((key, glyphs) in InputAssociations.entriesForTest()) {
            assertTrue("key '$key' has no glyphs", glyphs.isNotEmpty())
            assertEquals("key '$key' repeats a glyph: $glyphs", glyphs.size, glyphs.toSet().size)
        }
    }

    // ------------------------------------------------------------------ emoji coverage (count == catalog, 0 missing)

    private fun catalogEmoji(): List<String> = EmojiCatalog.categories.flatMap { it.emoji }.distinct()

    @Test fun every_catalog_emoji_has_exactly_one_row_in_catalog_order() {
        val catalog = catalogEmoji()
        val rows = EmojiAssociations.rows().map { it.emoji }
        assertEquals("row count must equal the unique catalog emoji count (0 missing, 0 invented)", catalog, rows)
    }

    @Test fun every_emoji_row_has_aligned_names_and_keys() {
        for (row in EmojiAssociations.rows()) {
            val names = row.names.split('/')
            assertTrue("${row.emoji}: empty names/keys", names.isNotEmpty() && row.keyList.isNotEmpty())
            assertEquals("${row.emoji}: names(${row.names}) and keys(${row.keys}) must align 1:1", names.size, row.keyList.size)
            assertTrue("${row.emoji}: blank name", names.none { it.isBlank() })
        }
    }

    @Test fun every_emoji_surfaces_within_cap_for_its_primary_key() {
        for (row in EmojiAssociations.rows()) {
            assertTrue(
                "${row.emoji} (${row.names}) must appear in lookup('${row.primaryKey}') within MAX_PER_QUERY",
                row.emoji in InputAssociations.lookup(row.primaryKey),
            )
        }
    }

    // ------------------------------------------------------------------ symbol coverage partition (covered XOR exempt)

    private fun catalogSymbols(): List<String> = SymbolCatalog.categories.flatMap { it.symbols }.distinct()

    /** Everything that actually surfaces: the capped lookup of every key. */
    private fun reachableGlyphs(): Set<String> {
        val r = HashSet<String>()
        for (key in InputAssociations.entriesForTest().keys) r.addAll(InputAssociations.lookup(key))
        return r
    }

    @Test fun every_catalog_symbol_is_reachable_or_exempted_never_both() {
        val reachable = reachableGlyphs()
        val exempt = HashMap<String, String>()
        for (ex in SymbolAssociations.exemptions) {
            assertTrue("exemption with blank reason", ex.reason.isNotBlank())
            for (g in ex.glyphList) {
                assertTrue("glyph '$g' exempted twice", exempt.put(g, ex.reason) == null)
            }
        }
        val catalog = catalogSymbols().toSet()
        for ((g, reason) in exempt) {
            assertTrue("exempt glyph '$g' is not in SymbolCatalog (dead exemption)", g in catalog)
            assertTrue("'$g' is exempted ($reason) but also reachable — resolve to one side", g !in reachable)
        }
        val missing = catalog.filter { it !in reachable && it !in exempt }
        assertTrue(
            "symbols neither reachable nor exempted (silent skip forbidden): $missing",
            missing.isEmpty(),
        )
        // Audit numbers for the submit summary / reviewers.
        val covered = catalog.count { it in reachable }
        println("symbol coverage: catalog=${catalog.size} covered=$covered exempt=${exempt.size}")
        println("emoji coverage: catalog=${catalogEmoji().size} rows=${EmojiAssociations.rows().size}")
    }

    @Test fun every_symbol_row_glyph_surfaces_within_cap_for_its_primary_key() {
        for (row in SymbolAssociations.rows()) {
            val hit = InputAssociations.lookup(row.primaryKey)
            for (g in row.glyphList) {
                assertTrue("'$g' (${row.name.ifEmpty { row.keys }}) must appear in lookup('${row.primaryKey}'), got $hit", g in hit)
            }
        }
    }

    /** Injected glyphs bypass the decoder's reading audits AND the E1-B leak audit excludes the
     *  injection channel positionally — so hanzi glyphs must stay a deliberate, allowlisted exception
     *  (円 = the Japanese yen sign, unreachable through the dictionary), never a silent habit. */
    @Test fun glyphs_carry_no_han_characters_beyond_the_allowlist() {
        val allowed = setOf("円")
        for ((key, glyphs) in InputAssociations.entriesForTest()) {
            for (g in glyphs) {
                if (g in allowed) continue
                val hasHan = g.codePoints().anyMatch { Character.isIdeographic(it) }
                assertTrue("key '$key' carries a Han-character glyph '$g' — allowlist it deliberately or drop it", !hasHan)
            }
        }
    }

    @Test fun symbol_rows_respect_the_reachability_cap() {
        for (row in SymbolAssociations.rows()) {
            assertTrue(
                "row '${row.keys}' lists ${row.glyphList.size} glyphs > MAX_PER_QUERY — unreachable tail",
                row.glyphList.size <= InputAssociations.MAX_PER_QUERY,
            )
        }
    }

    // ------------------------------------------------------------------ user-required examples

    @Test fun user_required_examples_hit() {
        assertTrue("sheshidu → ℃", "℃" in InputAssociations.lookup("sheshidu"))
        assertTrue("meijin → \$", "\$" in InputAssociations.lookup("meijin"))
        assertTrue("weixiao → 🙂", "🙂" in InputAssociations.lookup("weixiao"))
        assertTrue("aixin → ❤️", "❤️" in InputAssociations.lookup("aixin"))
        assertTrue("niu → 🐮", "🐮" in InputAssociations.lookup("niu"))
        assertTrue("niu → 🐂", "🐂" in InputAssociations.lookup("niu"))
        assertTrue("huashidu → ℉", "℉" in InputAssociations.lookup("huashidu"))
        assertTrue("pingfangmi → ㎡", "㎡" in InputAssociations.lookup("pingfangmi"))
        assertTrue("bitebi → ₿", "₿" in InputAssociations.lookup("bitebi"))
        assertTrue("aerfa → α", "α" in InputAssociations.lookup("aerfa"))
        assertTrue("youjiantou → →", "→" in InputAssociations.lookup("youjiantou"))
        assertTrue("quanyi → ①", "①" in InputAssociations.lookup("quanyi"))
    }

    // ------------------------------------------------------------------ separator / connected equivalence (full table)

    @Test fun separated_and_connected_input_forms_are_equivalent_for_every_key() {
        for (key in InputAssociations.entriesForTest().keys) {
            val syllables = T9Pinyin.segmentLetters(key) ?: continue // validity asserted elsewhere
            val separated = syllables.joinToString("'")
            assertEquals(
                "lookup('$separated') must equal lookup('$key')",
                InputAssociations.lookup(key),
                InputAssociations.lookup(separated),
            )
        }
    }

    @Test fun lookup_is_case_insensitive_and_caps_at_max_per_query() {
        for ((key, glyphs) in InputAssociations.entriesForTest()) {
            val hit = InputAssociations.lookup(key)
            assertTrue("lookup('$key') exceeds MAX_PER_QUERY", hit.size <= InputAssociations.MAX_PER_QUERY)
            assertEquals("lookup must be the capped prefix of the merged entry", glyphs.take(InputAssociations.MAX_PER_QUERY), hit)
            assertEquals("uppercase form must hit the same entry", hit, InputAssociations.lookup(key.uppercase()))
        }
    }

    // ------------------------------------------------------------------ legacy regression: original table kept verbatim

    /** The COMPLETE pre-expansion table (the old hardcoded map, copied verbatim). Every entry must still
     *  resolve with its original glyphs in the original order, at most gaining appended glyphs. */
    private val legacyTable: Map<String, List<String>> = mapOf(
        "haode" to listOf("👌"),
        "hao" to listOf("👍"),
        "zan" to listOf("👍"),
        "bang" to listOf("👍"),
        "guzhang" to listOf("👏"),
        "zaijian" to listOf("👋"),
        "baibai" to listOf("👋"),
        "xiexie" to listOf("🙏"),
        "xie" to listOf("🙏"),
        "qiu" to listOf("🙏"),
        "haha" to listOf("😂"),
        "xiao" to listOf("😄", "😂"),
        "kaixin" to listOf("😄"),
        "ku" to listOf("😭"),
        "shangxin" to listOf("😢"),
        "nu" to listOf("😡"),
        "shengqi" to listOf("😡"),
        "ai" to listOf("❤️"),
        "aini" to listOf("❤️"),
        "xin" to listOf("❤️"),
        "shuijiao" to listOf("😴"),
        "huo" to listOf("🔥"),
        "xing" to listOf("⭐"),
        "yueliang" to listOf("🌙"),
        "taiyang" to listOf("☀️"),
        "yu" to listOf("☔"),
        "xue" to listOf("❄️"),
        "hua" to listOf("🌸"),
        "liwu" to listOf("🎁"),
        "dangao" to listOf("🎂"),
        "shengri" to listOf("🎂", "🎉"),
        "qingzhu" to listOf("🎉"),
        "yinyue" to listOf("🎵"),
        "qian" to listOf("💰"),
        "diannao" to listOf("💻"),
        "shouji" to listOf("📱"),
        "jia" to listOf("+"),
        "jian" to listOf("−"),
        "cheng" to listOf("×"),
        "chu" to listOf("÷"),
        "dengyu" to listOf("="),
        "deng" to listOf("="),
        "baifen" to listOf("%"),
        "baifenzhi" to listOf("%"),
        "du" to listOf("°"),
        "renminbi" to listOf("￥"),
        "meiyuan" to listOf("\$"),
        "ouyuan" to listOf("€"),
    )

    /**
     * Noise-key regression lock (U23 review round 2): a glyph must never be keyed on a bare single syllable
     * (or an ultra-common word) whose everyday mainstream reading is unrelated to it — the "typing 好 shows
     * 🦪 oyster" class. Each pair below was trimmed to an unambiguous multi-syllable key; this asserts the
     * bare/colliding key no longer surfaces the glyph, so a future data edit cannot silently reintroduce it.
     * (Animals on their recognised bare-syllable reading, legacy entries, and sole-name glyphs like flags are
     * deliberately NOT here — see the submit note's kept-classes table.)
     */
    @Test fun trimmed_noise_keys_no_longer_surface_their_glyph() {
        val forbidden = listOf(
            // the nine reported high-frequency noise keys
            "hao" to "🦪", "chi" to "📏", "gao" to "⛏️", "dong" to "🕳️", "fei" to "🫁",
            "huan" to "🦡", "dou" to "🫘", "suan" to "🧄", "dasuan" to "🧄", "jiandan" to "🍳",
            // same-class bare single-syllable keys found by the full-table dominant-reading scan
            "cai" to "👎", "dan" to "🥚", "dao" to "🔪", "dian" to "⚡", "bi" to "🖊️",
            "bing" to "🧊", "jiang" to "🫚", "jiao" to "🦶", "li" to "🍐", "mi" to "㊙️",
            "pai" to "🥧", "cheng" to "⚖️", "mao" to "⚓", "hua" to "🖼️", "lei" to "😫",
            "lei" to "🌩️", "wu" to "🌫️", "wu" to "🌁", "xin" to "✉️", "shu" to "🌳",
            "ye" to "✌️", "qing" to "☀️", "san" to "☂️", "quan" to "⭕", "suo" to "🔒",
            "jin" to "🈲", "cha" to "❌", "jian" to "➖", "wan" to "🥣", "yao" to "💊",
            "qiang" to "🔫", "tong" to "🪣", "ya" to "🦷", "xue" to "🩸",
            // same-class ultra-common two-syllable words (toneless homophone of the glyph's rarer word)
            "biye" to "✌️", "youhua" to "🖼️", "yali" to "🍐", "zhichi" to "📏", "zhishi" to "🧀",
            "zuowei" to "💺", "touzi" to "🎲", "zhijin" to "🧻", "shuzi" to "🪮", "keji" to "✈️",
            "yifen" to "🍝", "jiazhi" to "🦿", "xiaoshi" to "🫥", "youyu" to "😔", "shiyan" to "🧂",
            "ziyuan" to "🟣",
        )
        for ((key, glyph) in forbidden) {
            assertTrue(
                "'$key' must no longer surface '$glyph' (it is a bare/colliding key whose mainstream reading is unrelated)",
                glyph !in InputAssociations.lookup(key),
            )
        }
    }

    @Test fun all_48_legacy_entries_keep_their_glyphs_first_in_order() {
        assertEquals(48, legacyTable.size)
        for ((key, glyphs) in legacyTable) {
            val hit = InputAssociations.lookup(key)
            assertEquals(
                "legacy '$key' must keep its original glyphs first (got $hit)",
                glyphs,
                hit.take(glyphs.size),
            )
        }
    }
}
