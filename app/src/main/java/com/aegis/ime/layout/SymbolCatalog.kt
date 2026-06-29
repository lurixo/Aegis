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
 * category contents are unit-testable. The dynamic "常用" category (recent + frequency) is NOT here;
 * the view prepends it from the usage store. Order:
 * 常用 / 中文 / 英文 / 网络 / 数学 / 箭头 / 角标 / 序号 / 音标 / 拼音.
 */
object SymbolCatalog {

    data class Category(val id: String, val title: String, val symbols: List<String>)

    /** The "常用" tab is dynamic; this id lets the view slot it in at the front. */
    const val RECENT_ID = "recent"
    const val RECENT_TITLE = "常用"

    val categories: List<Category> = listOf(
        // P1(#5): comma before period in both 中文 and 英文. debug.16: the standard Chinese 破折号 —— (double
        // em-dash) and 省略号 …… (double ellipsis) — SymbolsView renders these multi-char marks as ordinary
        // insertable chips (the 网址补全 chip treatment is scoped to net/url-like tokens). Plus full-width marks.
        Category("zh", "中文", tokens("， 。 、 ； ： ？ ！ “ ” ‘ ’ （ ） 《 》 〈 〉 「 」 『 』 【 】 〔 〕 〖 〗 … …… — —— ～ · ※ ° ‖ ￥ 〃 ＿ ﹏ ﹋ ＃ ＆ ＊ ＠ ％ ＋ ＝ ｜ ＜ ＞ ／ ＼ ｀")),
        // debug.16 item4: add en-dash – (was only em-dash —) and the ™©®¶ marks (previously only on the legacy
        // symbol() row keyboard, missing from this categorized panel).
        Category("en", "英文", listOf(
            ",", ".", ";", ":", "?", "!", "'", "\"", "`", "(", ")", "[", "]", "{", "}",
            "<", ">", "/", "\\", "|", "@", "#", "$", "%", "^", "&", "*", "-", "_", "+", "=", "~",
            "…", "•", "·", "—", "–", "°", "§", "¶", "™", "©", "®",
        )),
        // U24: 货币 — between 英文 and 网络.
        Category("currency", "货币", tokens("$ ¥ € £ ₩ ₹ ₽ ₺ ฿ ₫ ₴ ₦ ¢ ₱ ₪ ₸ ₮ ₭ ₲ ₡ ₵ ₿ ﷼ ₠ ₣ ₤ ₥ 元 円 圆")),
        // 网络 = URL-building helpers (NOT kaomoji/decoration). debug.12 P5: domain
        // suffixes (.com/.cn/.net/.org) removed; the multi-char completions (http:// https:// www. ://) are
        // rendered as full-width chips by SymbolsView so they no longer truncate in the single-glyph grid.
        Category("net", "网络", listOf(
            ".", "/", "@", "-", "_", "http://", "https://", "www.", "://", ":", "#", "?", "&", "=", "%",
        )),
        // debug.16 item4: add ∴∵ (所以/因为), 全等/相似 ≅∽, 圈运算 ⊕⊗⊙, 重积分 ∬∭, 数集 ℝℕℤℚℂ.
        Category("math", "数学", tokens("+ − × ÷ = ≠ ≈ ≡ ± ∓ ≤ ≥ ∞ √ ∛ ∑ ∏ ∫ ∬ ∭ ∮ ∂ ∇ ∆ ％ ‰ ∝ ∴ ∵ ∠ ⊥ ∥ ° ′ ″ π θ φ λ μ Σ Ω ½ ⅓ ¼ ¾ ⅔ ∈ ∉ ⊂ ⊃ ⊆ ⊇ ∪ ∩ ∅ ∀ ∃ ≅ ∽ ⊕ ⊗ ⊙ ℝ ℕ ℤ ℚ ℂ")),
        Category("arrow", "箭头", tokens("← → ↑ ↓ ↔ ↕ ↖ ↗ ↘ ↙ ⇐ ⇒ ⇑ ⇓ ⇔ ⇕ ↩ ↪ ↺ ↻ ➜ ➤ ➔ ⟶ ⟵ » « ‹ › ⬅ ➡ ⬆ ⬇ ⤴ ⤵")),
        Category("supsub", "角标", tokens("⁰ ¹ ² ³ ⁴ ⁵ ⁶ ⁷ ⁸ ⁹ ⁺ ⁻ ⁼ ⁽ ⁾ ⁿ ⁱ ₀ ₁ ₂ ₃ ₄ ₅ ₆ ₇ ₈ ₉ ₊ ₋ ₌ ₍ ₎ ₐ ₑ ₒ ₓ ℃ ℉ ㎡ ㎥ ㎏ ㎜ ㎝ ㎞ ㎎ ㎖")),
        Category("ordinal", "序号", tokens("① ② ③ ④ ⑤ ⑥ ⑦ ⑧ ⑨ ⑩ ⑪ ⑫ ⑬ ⑭ ⑮ ⑯ ⑰ ⑱ ⑲ ⑳ ⒈ ⒉ ⒊ ⒋ ⒌ ⒍ ⒎ ⒏ ⒐ ⒑ ⑴ ⑵ ⑶ ⑷ ⑸ ⑹ ⑺ ⑻ ⑼ ⑽ Ⅰ Ⅱ Ⅲ Ⅳ Ⅴ Ⅵ Ⅶ Ⅷ Ⅸ Ⅹ ⅰ ⅱ ⅲ ⅳ ⅴ ㈠ ㈡ ㈢ ㈣ ㈤ ㈥ ㈦ ㈧ ㈨ ㈩ Ⓐ Ⓑ Ⓒ ⓐ ⓑ ⓒ")),
        Category("ipa", "音标", tokens("i ɪ e ɛ æ ə ɜ ʌ ɑ ɒ ɔ o ʊ u y ø θ ð ʃ ʒ ŋ ʤ ʧ ç x ɣ ʔ ɹ ɫ ɲ ˈ ˌ ː ˑ")),
        // 拼音: every base vowel ā ō ē ī ū ǖ across all four tones (拼音 āōēīūǖ 全声调).
        Category("pinyin", "拼音", tokens("a ā á ǎ à o ō ó ǒ ò e ē é ě è ê i ī í ǐ ì u ū ú ǔ ù ü ǖ ǘ ǚ ǜ n ń ň ǹ ḿ")),
    )

    /**
     * P2(#6): the category a symbol belongs to (its FIRST listing in [categories] order) — used for the
     * 常用 origin badge (中文→"中", 英文→"英", …). null when the symbol isn't in any static category.
     */
    fun categoryTitleOf(symbol: String): String? = symbolToCategory[symbol]

    private val symbolToCategory: Map<String, String> by lazy {
        val m = LinkedHashMap<String, String>()
        for (c in categories) for (s in c.symbols) m.putIfAbsent(s, c.title)
        m
    }

    private fun tokens(s: String): List<String> = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
}
