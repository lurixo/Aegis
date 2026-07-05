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

/**
 * Static symbol catalogue for the D symbols panel (SymbolsView). Pure data — no Android deps, so the
  * Chinese IME behavior note.
 * the view prepends it from the usage store. Order:
  * Chinese IME behavior note.
 */
object SymbolCatalog {

    data class Category(val id: String, val title: String, val symbols: List<String>)
    data class Pairing(val left: String, val right: String)

    /** Chinese IME behavior note. */
    const val RECENT_ID = "recent"
    const val RECENT_TITLE = "常用"

    val categories: List<Category> = listOf(
        // Chinese IME behavior note.
        // Chinese IME behavior note.
        // wide-tile layout problem is removed at the source). Plus full-width marks.
        Category("zh", "中文", tokens("， 。 、 ； ： ？ ！ “ ” ‘ ’ （ ） 《 》 〈 〉 「 」 『 』 【 】 〔 〕 〖 〗 … — ～ · ※ ° ‖ ￥ 〃 ＿ ﹏ ﹋ ＃ ＆ ＊ ＠ ％ ＋ ＝ ｜ ＜ ＞ ／ ＼ ｀")),
        // debug.16 item4: add en-dash – (was only em-dash —) and the ™©®¶ marks (previously only on the legacy
        // symbol() row keyboard, missing from this categorized panel).
        Category("en", "英文", listOf(
            ",", ".", ";", ":", "?", "!", "'", "\"", "`", "(", ")", "[", "]", "{", "}",
            "<", ">", "/", "\\", "|", "@", "#", "$", "%", "^", "&", "*", "-", "_", "+", "=", "~",
            "…", "•", "·", "—", "–", "°", "§", "¶", "™", "©", "®",
        )),
        // Chinese IME behavior note.
        Category("currency", "货币", tokens("$ ¥ € £ ₩ ₹ ₽ ₺ ฿ ₫ ₴ ₦ ¢ ₱ ₪ ₸ ₮ ₭ ₲ ₡ ₵ ₿ ﷼ ₠ ₣ ₤ ₥ 元 円 圆")),
        // Chinese IME behavior note.
        // suffixes (.com/.cn/.net/.org) removed; the multi-char completions (http:// https:// www. ://) are
        // rendered as full-width chips by SymbolsView so they no longer truncate in the single-glyph grid.
        Category("net", "网络", listOf(
            ".", "/", "@", "-", "_", "http://", "https://", "www.", "://", ":", "#", "?", "&", "=", "%",
        )),
        // Chinese IME behavior note.
        // Chinese IME behavior note.
        // Chinese IME behavior note.
        // Chinese IME behavior note.
        Category("math", "数学", tokens("+ − × ÷ = ≠ ≈ ≡ ± ∓ ≤ ≥ ∞ √ ∛ ∑ ∏ ∫ ∬ ∭ ∮ ∂ ∇ ∆ ％ ‰ ∝ ∴ ∵ ∠ ⊥ ∥ ° ′ ″ π θ φ λ μ Σ Ω ½ ⅓ ¼ ¾ ⅔ ∈ ∉ ⊂ ⊃ ⊆ ⊇ ∪ ∩ ∅ ∀ ∃ ≅ ∽ ⊕ ⊗ ⊙ ℝ ℕ ℤ ℚ ℂ sin cos tan cot sec csc arcsin arccos arctan sinh cosh tanh ℃ ℉ ㎏ ㎜ ㎝ ㎞ ㎡ ㎥ ㎎ ㎖")),
        // Chinese IME behavior note.
        Category("greek", "希腊", tokens("α β γ δ ε ζ η θ ι κ λ μ ν ξ ο π ρ σ ς τ υ φ χ ψ ω Α Β Γ Δ Ε Ζ Η Θ Ι Κ Λ Μ Ν Ξ Ο Π Ρ Σ Τ Υ Φ Χ Ψ Ω")),
        Category("arrow", "箭头", tokens("← → ↑ ↓ ↔ ↕ ↖ ↗ ↘ ↙ ⇐ ⇒ ⇑ ⇓ ⇔ ⇕ ↩ ↪ ↺ ↻ ➜ ➤ ➔ ⟶ ⟵ » « ‹ › ⬅ ➡ ⬆ ⬇ ⤴ ⤵")),
        Category("supsub", "角标", tokens("⁰ ¹ ² ³ ⁴ ⁵ ⁶ ⁷ ⁸ ⁹ ⁺ ⁻ ⁼ ⁽ ⁾ ⁿ ⁱ ₀ ₁ ₂ ₃ ₄ ₅ ₆ ₇ ₈ ₉ ₊ ₋ ₌ ₍ ₎ ₐ ₑ ₒ ₓ ℃ ℉ ㎡ ㎥ ㎏ ㎜ ㎝ ㎞ ㎎ ㎖")),
        Category("ordinal", "序号", tokens("① ② ③ ④ ⑤ ⑥ ⑦ ⑧ ⑨ ⑩ ⑪ ⑫ ⑬ ⑭ ⑮ ⑯ ⑰ ⑱ ⑲ ⑳ ⒈ ⒉ ⒊ ⒋ ⒌ ⒍ ⒎ ⒏ ⒐ ⒑ ⑴ ⑵ ⑶ ⑷ ⑸ ⑹ ⑺ ⑻ ⑼ ⑽ Ⅰ Ⅱ Ⅲ Ⅳ Ⅴ Ⅵ Ⅶ Ⅷ Ⅸ Ⅹ ⅰ ⅱ ⅲ ⅳ ⅴ ㈠ ㈡ ㈢ ㈣ ㈤ ㈥ ㈦ ㈧ ㈨ ㈩ Ⓐ Ⓑ Ⓒ ⓐ ⓑ ⓒ")),
        Category("ipa", "音标", tokens("i ɪ e ɛ æ ə ɜ ʌ ɑ ɒ ɔ o ʊ u y ø θ ð ʃ ʒ ŋ ʤ ʧ ç x ɣ ʔ ɹ ɫ ɲ ˈ ˌ ː ˑ")),
        // Chinese IME behavior note.
        Category("pinyin", "拼音", tokens("a ā á ǎ à o ō ó ǒ ò e ē é ě è ê i ī í ǐ ì u ū ú ǔ ù ü ǖ ǘ ǚ ǜ n ń ň ǹ ḿ")),
    )

    /**
     * P2(#6): the category a symbol belongs to (its FIRST listing in [categories] order) — used for the
      * Chinese IME behavior note.
     */
    fun categoryTitleOf(symbol: String): String? = symbolToCategory[symbol]

    /**
     * Full/half-width normalization for the full/half de-dup key ONLY (never for display). Folds the full-width
     * ASCII block (U+FF01–U+FF5E) onto ASCII (U+0021–U+007E), the ideographic space (U+3000) onto a normal
     * space, and the full-width currency/technical block (U+FFE0–U+FFE6) onto its half-width twin, so a
     * full-width and half-width form of the SAME character collapse to one key (e.g. ％→%, ！→!, ，→,, and
     * — the reason FFE was added — ￥(U+FFE5)→¥(U+00A5) so the yen sign no longer appears in both widths).
     * The FFE block has NO uniform offset (￢→¬, ￦→₩ jump differently), so it is an explicit per-code map.
     * It is deliberately narrow: it does NOT touch square units (㎡ ㎏ ℃), roman numerals (Ⅰ), enclosed
     * numbers (①), super/subscripts, fractions, or cross-character look-alikes (– vs —, · vs •, × vs x),
     * all of which an unrestricted NFKC pass would wrongly merge.
     */
    fun foldFullWidth(s: String): String {
        var changed = false
        val out = StringBuilder(s.length)
        for (ch in s) {
            val c = ch.code
            when {
                c in 0xFF01..0xFF5E -> { out.append((c - 0xFEE0).toChar()); changed = true }
                c == 0x3000 -> { out.append(' '); changed = true }
                // Full-width currency/technical block U+FFE0–U+FFE6 → half-width twin (non-uniform, explicit).
                c == 0xFFE0 -> { out.append('¢'); changed = true } // ￠ → ¢
                c == 0xFFE1 -> { out.append('£'); changed = true } // ￡ → £
                c == 0xFFE2 -> { out.append('¬'); changed = true } // ￢ → ¬
                c == 0xFFE3 -> { out.append('¯'); changed = true } // ￣ → ¯
                c == 0xFFE4 -> { out.append('¦'); changed = true } // ￤ → ¦
                c == 0xFFE5 -> { out.append('¥'); changed = true } // ￥ → ¥
                c == 0xFFE6 -> { out.append('₩'); changed = true } // ￦ → ₩
                else -> out.append(ch)
            }
        }
        return if (changed) out.toString() else s
    }

    fun pairingFor(symbol: String): Pairing? = pairedSymbols[symbol]?.let { Pairing(symbol, it) }

    fun insertionFor(symbol: String, hasTextAfterCursor: Boolean): List<String> {
        val pair = pairingFor(symbol)
        return if (pair != null && !hasTextAfterCursor) listOf(pair.left, pair.right) else listOf(symbol)
    }

    private val symbolToCategory: Map<String, String> by lazy {
        val m = LinkedHashMap<String, String>()
        for (c in categories) for (s in c.symbols) m.putIfAbsent(s, c.title)
        m
    }

    private val pairedSymbols: Map<String, String> = linkedMapOf(
        "（" to "）",
        "《" to "》",
        "〈" to "〉",
        "「" to "」",
        "『" to "』",
        "【" to "】",
        "〔" to "〕",
        "〖" to "〗",
        "“" to "”",
        "‘" to "’",
        "(" to ")",
        "[" to "]",
        "{" to "}",
        "<" to ">",
        "\"" to "\"",
        "'" to "'",
        "`" to "`",
    )

    private fun tokens(s: String): List<String> = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
}
