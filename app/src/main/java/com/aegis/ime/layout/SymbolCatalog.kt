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

object SymbolCatalog {

    data class Category(val id: String, val title: String, val symbols: List<String>)

    const val RECENT_ID = "recent"
    const val RECENT_TITLE = "常用"

    val categories: List<Category> = listOf(
        Category("zh", "中文", tokens("。 ， 、 ； ： ？ ！ “ ” ‘ ’ （ ） 《 》 〈 〉 「 」 『 』 【 】 〔 〕 〖 〗 … — ～ · ※ ° ‖ ￥ 〃 ＿ ﹏ ﹋")),
        Category("en", "英文", listOf(
            ".", ",", ";", ":", "?", "!", "'", "\"", "`", "(", ")", "[", "]", "{", "}",
            "<", ">", "/", "\\", "|", "@", "#", "$", "%", "^", "&", "*", "-", "_", "+", "=", "~",
            "…", "•", "·", "—", "°", "§",
        )),
        Category("net", "网络", listOf(
            ".", "/", "@", "-", "_", "http://", "https://", "www.", ".com", ".cn", ".net", ".org",
            "://", ":", "#", "?", "&", "=", "%",
        )),
        Category("math", "数学", tokens("+ − × ÷ = ≠ ≈ ≡ ± ∓ ≤ ≥ ∞ √ ∛ ∑ ∏ ∫ ∮ ∂ ∇ ∆ ％ ‰ ∝ ∠ ⊥ ∥ ° ′ ″ π θ φ λ μ Σ Ω ½ ⅓ ¼ ¾ ⅔ ∈ ∉ ⊂ ⊃ ⊆ ⊇ ∪ ∩ ∅ ∀ ∃")),
        Category("arrow", "箭头", tokens("← → ↑ ↓ ↔ ↕ ↖ ↗ ↘ ↙ ⇐ ⇒ ⇑ ⇓ ⇔ ⇕ ↩ ↪ ↺ ↻ ➜ ➤ ➔ ⟶ ⟵ » « ‹ › ⬅ ➡ ⬆ ⬇ ⤴ ⤵")),
        Category("supsub", "角标", tokens("⁰ ¹ ² ³ ⁴ ⁵ ⁶ ⁷ ⁸ ⁹ ⁺ ⁻ ⁼ ⁽ ⁾ ⁿ ⁱ ₀ ₁ ₂ ₃ ₄ ₅ ₆ ₇ ₈ ₉ ₊ ₋ ₌ ₍ ₎ ₐ ₑ ₒ ₓ ℃ ℉ ㎡ ㎥ ㎏ ㎜ ㎝ ㎞ ㎎ ㎖")),
        Category("ordinal", "序号", tokens("① ② ③ ④ ⑤ ⑥ ⑦ ⑧ ⑨ ⑩ ⑪ ⑫ ⑬ ⑭ ⑮ ⑯ ⑰ ⑱ ⑲ ⑳ ⒈ ⒉ ⒊ ⒋ ⒌ ⒍ ⒎ ⒏ ⒐ ⒑ ⑴ ⑵ ⑶ ⑷ ⑸ ⑹ ⑺ ⑻ ⑼ ⑽ Ⅰ Ⅱ Ⅲ Ⅳ Ⅴ Ⅵ Ⅶ Ⅷ Ⅸ Ⅹ ⅰ ⅱ ⅲ ⅳ ⅴ ㈠ ㈡ ㈢ ㈣ ㈤ ㈥ ㈦ ㈧ ㈨ ㈩ Ⓐ Ⓑ Ⓒ ⓐ ⓑ ⓒ")),
        Category("ipa", "音标", tokens("i ɪ e ɛ æ ə ɜ ʌ ɑ ɒ ɔ o ʊ u y ø θ ð ʃ ʒ ŋ ʤ ʧ ç x ɣ ʔ ɹ ɫ ɲ ˈ ˌ ː ˑ")),
        Category("pinyin", "拼音", tokens("a ā á ǎ à o ō ó ǒ ò e ē é ě è ê i ī í ǐ ì u ū ú ǔ ù ü ǖ ǘ ǚ ǜ n ń ň ǹ ḿ")),
    )

    private fun tokens(s: String): List<String> = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
}
