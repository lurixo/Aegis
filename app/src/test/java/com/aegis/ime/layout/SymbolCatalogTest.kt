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

package com.aegis.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the D symbols catalogue: category set/order, non-empty, no dups, and full pinyin tone coverage. */
class SymbolCatalogTest {

    @Test fun category_titles_match_the_expected_order() {
        // Chinese IME behavior note.
        assertEquals(
            listOf("中文", "英文", "货币", "网络", "数学", "希腊", "箭头", "角标", "序号", "音标", "拼音"),
            SymbolCatalog.categories.map { it.title },
        )
    }

    @Test fun comma_comes_before_period_in_chinese_and_english() {
        // P1(#5): both punctuation lists lead with the comma, then the period.
        val zh = SymbolCatalog.categories.first { it.id == "zh" }.symbols
        assertEquals("，", zh[0]); assertEquals("。", zh[1])
        val en = SymbolCatalog.categories.first { it.id == "en" }.symbols
        assertEquals(",", en[0]); assertEquals(".", en[1])
    }

    @Test fun category_title_lookup_drives_the_common_origin_badge() {
        // categoryTitleOf returns the FIRST catalogue category holding a symbol. It is now the FALLBACK badge
        // source (used only when a recent entry has no recorded origin); a recorded origin takes precedence.
        assertEquals("中文", SymbolCatalog.categoryTitleOf("，"))
        assertEquals("英文", SymbolCatalog.categoryTitleOf(","))   // ascii comma is an English mark
        assertEquals("货币", SymbolCatalog.categoryTitleOf("¥"))
        assertEquals("数学", SymbolCatalog.categoryTitleOf("±"))
        assertEquals(null, SymbolCatalog.categoryTitleOf("😀")) // 😀 not in any static category
        // Chinese IME behavior note.
        assertEquals("中", SymbolCatalog.categoryTitleOf("，")?.take(1))
        assertEquals("英", SymbolCatalog.categoryTitleOf(",")?.take(1))
    }

    @Test fun paired_symbol_insertion_uses_the_full_pair_only_at_the_end() {
        assertEquals(listOf("（", "）"), SymbolCatalog.insertionFor("（", hasTextAfterCursor = false))
        assertEquals(listOf("（"), SymbolCatalog.insertionFor("（", hasTextAfterCursor = true))
        assertEquals(listOf("\"", "\""), SymbolCatalog.insertionFor("\"", hasTextAfterCursor = false))
        assertEquals(listOf("'", "'"), SymbolCatalog.insertionFor("'", hasTextAfterCursor = false))
        assertEquals(listOf("[", "]"), SymbolCatalog.insertionFor("[", hasTextAfterCursor = false))
        assertEquals(listOf("`", "`"), SymbolCatalog.insertionFor("`", hasTextAfterCursor = false))
        assertEquals(listOf("'"), SymbolCatalog.insertionFor("'", hasTextAfterCursor = true))
        assertEquals(listOf("["), SymbolCatalog.insertionFor("[", hasTextAfterCursor = true))
        assertEquals(listOf("，"), SymbolCatalog.insertionFor("，", hasTextAfterCursor = false))
    }

    @Test fun paired_symbol_left_marks_are_present_in_the_catalogue() {
        for (left in listOf("（", "《", "「", "【", "“", "‘", "(", "[", "{", "<", "\"", "'", "`")) {
            assertTrue("paired left mark $left must be reachable from the symbol catalogue", SymbolCatalog.categoryTitleOf(left) != null)
        }
    }

    @Test fun currency_category_sits_between_english_and_net_with_common_symbols() {
        // Chinese IME behavior note.
        val ids = SymbolCatalog.categories.map { it.id }
        assertEquals(ids.indexOf("en") + 1, ids.indexOf("currency"))
        assertEquals(ids.indexOf("currency") + 1, ids.indexOf("net"))
        val cur = SymbolCatalog.categories.first { it.id == "currency" }.symbols
        for (c in listOf("$", "¥", "€", "£", "₩", "₹", "₽")) assertTrue("missing $c", c in cur)
    }

    @Test fun net_drops_domain_suffixes_but_keeps_url_completions() {
        // Chinese IME behavior note.
        val net = SymbolCatalog.categories.first { it.id == "net" }.symbols
        for (suffix in listOf(".com", ".cn", ".net", ".org")) assertTrue("$suffix should be removed", suffix !in net)
        for (c in listOf("http://", "https://", "www.", "://")) assertTrue("missing completion $c", c in net)
    }

    @Test fun every_category_is_non_empty_and_has_no_duplicates() {
        for (c in SymbolCatalog.categories) {
            assertTrue("${c.title} must not be empty", c.symbols.isNotEmpty())
            assertEquals("${c.title} has duplicate symbols", c.symbols.size, c.symbols.toSet().size)
        }
    }

    @Test fun pinyin_covers_every_base_vowel_in_all_four_tones() {
        val pinyin = SymbolCatalog.categories.first { it.id == "pinyin" }.symbols.toSet()
        // Chinese IME behavior note.
        val toned = listOf(
            "ā", "á", "ǎ", "à",
            "ō", "ó", "ǒ", "ò",
            "ē", "é", "ě", "è",
            "ī", "í", "ǐ", "ì",
            "ū", "ú", "ǔ", "ù",
            "ǖ", "ǘ", "ǚ", "ǜ",
        )
        for (t in toned) assertTrue("missing toned vowel $t", t in pinyin)
    }

    @Test fun english_includes_escaped_glyphs() {
        val en = SymbolCatalog.categories.first { it.id == "en" }.symbols
        assertTrue("backslash present", "\\" in en)
        assertTrue("double-quote present", "\"" in en)
    }

    @Test fun net_category_is_url_helpers_not_kaomoji() {
        // Chinese IME behavior note.
        val net = SymbolCatalog.categories.first { it.id == "net" }.symbols
        for (u in listOf(".", "/", "@", "-", "_", "http://", "https://")) assertTrue("missing $u", u in net)
        assertTrue(
            "no kaomoji/decoration in 网络",
            net.none { s -> s.contains("(") || s.contains("^") || s.contains("♥") || s.contains("★") || s.contains("≧") },
        )
    }

    // Chinese IME behavior note.

    private fun cat(id: String) = SymbolCatalog.categories.first { it.id == id }.symbols

    @Test fun chineseUsesSingleCellDashAndEllipsisOnly() {
        // Chinese IME behavior note.
        // Chinese IME behavior note.
        assertTrue("中文 single — / … present", cat("zh").containsAll(listOf("—", "…")))
        assertTrue("中文 双破折号 —— dropped", "——" !in cat("zh"))
        assertTrue("中文 双省略号 …… dropped", "……" !in cat("zh"))
        assertTrue("中文 full-width marks", cat("zh").containsAll(listOf("＃", "＆", "＊", "＠", "％", "＋", "＝", "｜", "＜", "＞", "／", "＼")))
        assertTrue("中文 has NO multi-char token at all", cat("zh").none { it.length > 1 })
    }

    @Test fun mathGainsTrigonometryAndUnits() {
        // debug.17 items 1+2: trig functions (multi-char grid cells) and common measurement units.
        assertTrue("数学 三角函数", cat("math").containsAll(listOf(
            "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh")))
        assertTrue("数学 计量单位", cat("math").containsAll(listOf(
            "℃", "℉", "㎏", "㎜", "㎝", "㎞", "㎡", "㎥", "㎎", "㎖")))
        // Chinese IME behavior note.
        assertEquals("数学", SymbolCatalog.categoryTitleOf("℃"))
    }

    @Test fun greekCategorySitsBetweenMathAndArrow() {
        // Chinese IME behavior note.
        // and uppercase Α…Ω — locked in full so a future accidental omission (e.g. ξ / υ) fails the test.
        val ids = SymbolCatalog.categories.map { it.id }
        assertEquals("希腊 right after 数学", ids.indexOf("math") + 1, ids.indexOf("greek"))
        assertEquals("箭头 right after 希腊", ids.indexOf("greek") + 1, ids.indexOf("arrow"))
        val lower = listOf("α", "β", "γ", "δ", "ε", "ζ", "η", "θ", "ι", "κ", "λ", "μ",
            "ν", "ξ", "ο", "π", "ρ", "σ", "ς", "τ", "υ", "φ", "χ", "ψ", "ω")
        val upper = listOf("Α", "Β", "Γ", "Δ", "Ε", "Ζ", "Η", "Θ", "Ι", "Κ", "Λ", "Μ",
            "Ν", "Ξ", "Ο", "Π", "Ρ", "Σ", "Τ", "Υ", "Φ", "Χ", "Ψ", "Ω")
        val greek = cat("greek")
        assertTrue("complete lowercase α…ω (incl. final sigma ς)", greek.containsAll(lower))
        assertTrue("complete uppercase Α…Ω", greek.containsAll(upper))
        assertEquals("希腊 is exactly the 25 lowercase + 24 uppercase letters", lower.size + upper.size, greek.size)
        assertTrue("希腊 letters are all single-cell (no multi-char tile)", greek.none { it.length > 1 })
    }

    @Test fun item4_englishGainsEnDashAndTrademarks() {
        assertTrue("英文 en-dash – distinct from em-dash —", "–" in cat("en") && "—" in cat("en"))
        assertTrue("英文 ™ © ® ¶", cat("en").containsAll(listOf("™", "©", "®", "¶")))
    }

    @Test fun item4_mathGainsThereforeBecauseCongruenceAndNumberSets() {
        assertTrue("数学 ∴ ∵", cat("math").containsAll(listOf("∴", "∵")))
        assertTrue("数学 全等/相似 ≅ ∽", cat("math").containsAll(listOf("≅", "∽")))
        assertTrue("数学 数集 ℝℕℤℚℂ", cat("math").containsAll(listOf("ℝ", "ℕ", "ℤ", "ℚ", "ℂ")))
        // regression guard: the operators the user expects must already be present.
        assertTrue("数学 keeps × ÷ ± ≈ ≠ ≤ ≥ √ ∞ ∑",
            cat("math").containsAll(listOf("×", "÷", "±", "≈", "≠", "≤", "≥", "√", "∞", "∑")))
    }

    // Mechanically-derived reference fold used by the exhaustive tests below: fold U+FF01–U+FF5E onto ASCII,
    // U+3000 onto a normal space, and the currency/technical block U+FFE0–U+FFE6 onto its half-width twin; leave
    // everything else untouched. The ASCII/space branches are derived independently (offset arithmetic vs the
    // production's per-code map); the exact FFE mapping is additionally pinned, code point by code point, by
    // foldFullWidth_folds_the_fullwidth_currency_block_onto_its_halfwidth_twins below.
    private fun expectedFold(s: String): String = buildString {
        for (ch in s) {
            val c = ch.code
            append(
                when {
                    c in 0xFF01..0xFF5E -> (c - 0xFEE0).toChar()
                    c == 0x3000 -> ' '
                    c == 0xFFE0 -> '¢'
                    c == 0xFFE1 -> '£'
                    c == 0xFFE2 -> '¬'
                    c == 0xFFE3 -> '¯'
                    c == 0xFFE4 -> '¦'
                    c == 0xFFE5 -> '¥'
                    c == 0xFFE6 -> '₩'
                    else -> ch
                },
            )
        }
    }

    private fun allSymbols(): List<String> = SymbolCatalog.categories.flatMap { it.symbols }

    @Test fun foldFullWidth_matches_the_narrow_reference_for_every_catalogue_symbol() {
        // Exhaustive, no sampling: EVERY symbol in EVERY category must fold exactly to the narrow reference.
        // This fails on any over-fold (e.g. ㎡ Ⅰ ℃ ① wrongly collapsed) or under-fold (a full-width mark left
        // un-normalized), symbol by symbol.
        for (s in allSymbols()) {
            assertEquals("fold mismatch for $s", expectedFold(s), SymbolCatalog.foldFullWidth(s))
        }
    }

    @Test fun every_fullwidth_catalogue_mark_folds_onto_a_halfwidth_twin_that_also_exists() {
        // Enumerate the complete set of full/half-width same-char PAIRS straight from the catalogue and assert
        // each full-width mark folds onto a half-width code point that is itself a catalogue symbol. Covers both
        // the ASCII block (FF01–FF5E, offset −0xFEE0) and the currency/technical block (FFE0–FFE6, explicit map).
        val fulls = LinkedHashSet<Char>()   // ％ appears in two tabs; count distinct pairs
        for (s in allSymbols()) {
            if (s.length != 1) continue
            val c = s[0].code
            val half: String? = when {
                c in 0xFF01..0xFF5E -> (c - 0xFEE0).toChar().toString()
                c in 0xFFE0..0xFFE6 -> expectedFold(s) // the explicit non-uniform FFE map (￥→¥ …)
                else -> null
            }
            if (half != null) {
                assertEquals("$s must fold to its half-width twin", half, SymbolCatalog.foldFullWidth(s))
                assertTrue("the twin $half of $s must exist in the catalogue", SymbolCatalog.categoryTitleOf(half) != null)
                fulls.add(s[0])
            }
        }
        // 22 distinct ASCII full-width marks + the full-width yen ￥ (U+FFE5, the renminbi fix) = 23.
        assertEquals("every distinct full/half-width pair in the catalogue is covered", 23, fulls.size)
    }

    @Test fun foldFullWidth_never_collapses_a_symbol_outside_the_fullwidth_block() {
        // Exhaustive negative: any catalogue symbol with NO character in a folded block (FF01–FF5E, U+3000, or
        // the currency/technical block FFE0–FFE6) must be returned byte-for-byte — this is where an unrestricted
        // NFKC pass would wrongly merge ㎡→m2, Ⅰ→I, ℃→°C, ①→1, ㈠→(一), ½→1⁄2, ²→2, ₂→2, and the look-alikes
        // – — · • × x.
        for (s in allSymbols()) {
            val inBlock = s.any { it.code in 0xFF01..0xFF5E || it.code == 0x3000 || it.code in 0xFFE0..0xFFE6 }
            if (!inBlock) assertEquals("$s must not be folded", s, SymbolCatalog.foldFullWidth(s))
        }
        // spot the cross-character look-alikes explicitly: neither side moves toward the other.
        for ((a, b) in listOf("–" to "—", "·" to "•", "×" to "x")) {
            assertEquals(a, SymbolCatalog.foldFullWidth(a))
            assertEquals(b, SymbolCatalog.foldFullWidth(b))
        }
        assertEquals("ideographic space folds to a normal space", " ", SymbolCatalog.foldFullWidth("　"))
    }

    @Test fun foldFullWidth_folds_the_fullwidth_currency_block_onto_its_halfwidth_twins() {
        // FFE extension (the fix behind the renminbi ￥/¥ report): each U+FFE0–U+FFE6 mark folds to its specific
        // half-width twin. The block has NO uniform offset (￢→¬ and ￦→₩ jump differently from ￥→¥), so this
        // pins every mapping explicitly, code point by code point.
        assertEquals("¢", SymbolCatalog.foldFullWidth("￠")) // U+FFE0 → U+00A2
        assertEquals("£", SymbolCatalog.foldFullWidth("￡")) // U+FFE1 → U+00A3
        assertEquals("¬", SymbolCatalog.foldFullWidth("￢")) // U+FFE2 → U+00AC
        assertEquals("¯", SymbolCatalog.foldFullWidth("￣")) // U+FFE3 → U+00AF
        assertEquals("¦", SymbolCatalog.foldFullWidth("￤")) // U+FFE4 → U+00A6
        assertEquals("¥", SymbolCatalog.foldFullWidth("￥")) // U+FFE5 → U+00A5  (the renminbi fix)
        assertEquals("₩", SymbolCatalog.foldFullWidth("￦")) // U+FFE6 → U+20A9
        // in a mixed string only the FFE mark moves; the half-width twins are already folded (idempotent).
        assertEquals("a¥b", SymbolCatalog.foldFullWidth("a￥b"))
        for (half in listOf("¢", "£", "¬", "¯", "¦", "¥", "₩")) assertEquals(half, SymbolCatalog.foldFullWidth(half))
        // boundaries: the code points just outside the block are untouched.
        assertEquals("￟", SymbolCatalog.foldFullWidth("￟")) // just below FFE0
        assertEquals("￧", SymbolCatalog.foldFullWidth("￧")) // just above FFE6
    }

    @Test fun nineFixedPunctuationStaysInSyncWithTheColumn() {
        // Chinese IME behavior note.
        // Chinese IME behavior note.
        assertEquals(Layouts.nineFixedPunctuation + "自定义", Layouts.ninePunctuation().map { it.label })
    }
}
