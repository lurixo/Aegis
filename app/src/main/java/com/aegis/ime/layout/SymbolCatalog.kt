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

import com.aegis.ime.R

object SymbolCatalog {

    data class Category(val id: String, val titleRes: Int, val symbols: List<String>)
    data class Pairing(val left: String, val right: String)

    const val RECENT_ID = "recent"
    val RECENT_TITLE_RES = R.string.sym_cat_recent

    val categories: List<Category> = listOf(
        Category("zh", R.string.sym_cat_zh, tokens("， 。 、 ； ： ？ ！ “ ” ‘ ’ （ ） 《 》 〈 〉 「 」 『 』 【 】 〔 〕 〖 〗 … — ～ · ※ ° ‖ ￥ 〃 ＿ ﹏ ﹋ ＃ ＆ ＊ ＠ ％ ＋ ＝ ｜ ＜ ＞ ／ ＼ ｀")),
        Category("en", R.string.sym_cat_en, listOf(
            ",", ".", ";", ":", "?", "!", "'", "\"", "`", "(", ")", "[", "]", "{", "}",
            "<", ">", "/", "\\", "|", "@", "#", "$", "%", "^", "&", "*", "-", "_", "+", "=", "~",
            "…", "•", "·", "—", "–", "°", "§", "¶", "™", "©", "®",
        )),
        Category("currency", R.string.sym_cat_currency, tokens("$ ¥ € £ ₩ ₹ ₽ ₺ ฿ ₫ ₴ ₦ ¢ ₱ ₪ ₸ ₮ ₭ ₲ ₡ ₵ ₿ ﷼ ₠ ₣ ₤ ₥ 元 円 圆")),
        Category("net", R.string.sym_cat_net, listOf(
            ".", "/", "@", "-", "_", "http://", "https://", "http://www.", "https://www.", ":", "#", "?", "&", "=", "%",
        )),
        Category("math", R.string.sym_cat_math, tokens("+ − × ÷ = ≠ ≈ ≡ ± ∓ < > ≤ ≥ ∞ √ ∛ ∑ ∏ ∫ ∬ ∭ ∮ ∂ ∇ ∆ ％ ‰ ∝ ∴ ∵ ∠ ⊥ ∥ ° ′ ″ π θ φ λ μ Σ Ω ½ ⅓ ¼ ¾ ⅔ ∈ ∉ ⊂ ⊃ ⊆ ⊇ ∪ ∩ ∅ ∀ ∃ ≅ ∽ ⊕ ⊗ ⊙ ℝ ℕ ℤ ℚ ℂ sin cos tan cot sec csc arcsin arccos arctan sinh cosh tanh ℃ ℉ ㎏ ㎜ ㎝ ㎞ ㎡ ㎥ ㎎ ㎖")),
        Category("greek", R.string.sym_cat_greek, tokens("α β γ δ ε ζ η θ ι κ λ μ ν ξ ο π ρ σ ς τ υ φ χ ψ ω Α Β Γ Δ Ε Ζ Η Θ Ι Κ Λ Μ Ν Ξ Ο Π Ρ Σ Τ Υ Φ Χ Ψ Ω")),
        Category("arrow", R.string.sym_cat_arrow, tokens("← → ↑ ↓ ↔ ↕ ↖ ↗ ↘ ↙ ⇐ ⇒ ⇑ ⇓ ⇔ ⇕ ↩ ↪ ↺ ↻ ➜ ➤ ➔ ⟶ ⟵ » « ‹ › ⬅ ➡ ⬆ ⬇ ⤴ ⤵")),
        Category("supsub", R.string.sym_cat_supsub, tokens("⁰ ¹ ² ³ ⁴ ⁵ ⁶ ⁷ ⁸ ⁹ ⁺ ⁻ ⁼ ⁽ ⁾ ⁿ ⁱ ₀ ₁ ₂ ₃ ₄ ₅ ₆ ₇ ₈ ₉ ₊ ₋ ₌ ₍ ₎ ₐ ₑ ₒ ₓ ℃ ℉ ㎡ ㎥ ㎏ ㎜ ㎝ ㎞ ㎎ ㎖")),
        Category("ordinal", R.string.sym_cat_ordinal, tokens("① ② ③ ④ ⑤ ⑥ ⑦ ⑧ ⑨ ⑩ ⑪ ⑫ ⑬ ⑭ ⑮ ⑯ ⑰ ⑱ ⑲ ⑳ ⒈ ⒉ ⒊ ⒋ ⒌ ⒍ ⒎ ⒏ ⒐ ⒑ ⑴ ⑵ ⑶ ⑷ ⑸ ⑹ ⑺ ⑻ ⑼ ⑽ Ⅰ Ⅱ Ⅲ Ⅳ Ⅴ Ⅵ Ⅶ Ⅷ Ⅸ Ⅹ ⅰ ⅱ ⅲ ⅳ ⅴ ㈠ ㈡ ㈢ ㈣ ㈤ ㈥ ㈦ ㈧ ㈨ ㈩ Ⓐ Ⓑ Ⓒ ⓐ ⓑ ⓒ")),
        Category("ipa", R.string.sym_cat_ipa, tokens("i ɪ e ɛ æ ə ɜ ʌ ɑ ɒ ɔ o ʊ u y ø θ ð ʃ ʒ ŋ ʤ ʧ ç x ɣ ʔ ɹ ɫ ɲ ˈ ˌ ː ˑ")),
        Category("pinyin", R.string.sym_cat_pinyin, tokens("a ā á ǎ à o ō ó ǒ ò e ē é ě è ê i ī í ǐ ì u ū ú ǔ ù ü ǖ ǘ ǚ ǜ n ń ň ǹ ḿ")),
    )

    fun categoryIdOf(symbol: String): String? = symbolToCategory[symbol]

    fun titleResOf(id: String): Int? =
        if (id == RECENT_ID) RECENT_TITLE_RES else categories.firstOrNull { it.id == id }?.titleRes

    fun foldFullWidth(s: String): String {
        var changed = false
        val out = StringBuilder(s.length)
        for (ch in s) {
            val c = ch.code
            when {
                c in 0xFF01..0xFF5E -> { out.append((c - 0xFEE0).toChar()); changed = true }
                c == 0x3000 -> { out.append(' '); changed = true }
                c == 0xFFE0 -> { out.append('¢'); changed = true }
                c == 0xFFE1 -> { out.append('£'); changed = true }
                c == 0xFFE2 -> { out.append('¬'); changed = true }
                c == 0xFFE3 -> { out.append('¯'); changed = true }
                c == 0xFFE4 -> { out.append('¦'); changed = true }
                c == 0xFFE5 -> { out.append('¥'); changed = true }
                c == 0xFFE6 -> { out.append('₩'); changed = true }
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
        for (c in categories) for (s in c.symbols) m.putIfAbsent(s, c.id)
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
