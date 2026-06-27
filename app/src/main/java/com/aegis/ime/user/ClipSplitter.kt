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

package com.aegis.ime.user

/**
 * C4 拆词: split a long clip into tappable blocks so the user can copy one piece at a time.
 *
 * Two-pass-per-position scan: at each index we first try to match an ATOMIC entity that must stay whole
 * — a URL (http(s):// , www. , bare domain) or an email — and emit it as one [Block]; otherwise we
 * consume a maximal run of one character class (中文 / 英文字母 / 数字 / 符号), emitting that run. Pure
 * whitespace runs are dropped (a blank block is useless to copy). Examples:
 *   "你好hello,world!"      → 你好 | hello | , | world | !
 *   "看这个https://x.com很好" → 看这个 | https://x.com | 很好
 *   "联系bob@x.com谢谢"     → 联系 | bob@x.com | 谢谢
 * Pure data — no Android deps — so the segmentation is unit-testable.
 */
object ClipSplitter {

    enum class Kind { LINK, EMAIL, HAN, LATIN, DIGIT, SYMBOL }

    data class Block(val text: String, val kind: Kind)

    // Stop a URL/domain at whitespace, CJK, or CJK punctuation so a link embedded in Chinese text ends cleanly.
    private const val STOP = "\\s一-鿿，。！？；：、（）【】《》“”‘’"
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val URL = Regex("https?://[^$STOP]+", RegexOption.IGNORE_CASE)
    private val WWW = Regex("www\\.[^$STOP]+", RegexOption.IGNORE_CASE)
    // A curated TLD whitelist (common gTLDs + ccTLDs) so a bare domain like x.ai / x.uk / x.tv is one block,
    // while an ordinary "字.字" / "file.txt" is NOT mistaken for a domain.
    private const val TLDS =
        "com|cn|net|org|io|gov|edu|co|me|app|dev|xyz|top|info|biz|ai|ru|uk|tv|de|fr|jp|kr|us|ca|au|in|it|es|" +
            "nl|se|no|ch|eu|hk|tw|sg|cc|to|pro|club|live|vip|ltd|art|online|site|store|tech|news|blog|wiki"
    private val DOMAIN = Regex(
        "[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.($TLDS)(/[^$STOP]*)?",
        RegexOption.IGNORE_CASE,
    )

    private enum class Cls { HAN, LATIN, DIGIT, SYMBOL, SPACE }

    fun split(s: String): List<Block> {
        val out = ArrayList<Block>()
        var i = 0
        while (i < s.length) {
            val entity = entityAt(s, i)
            if (entity != null) { out.add(entity); i += entity.text.length; continue }
            val cls = classOf(s[i])
            var j = i + 1
            while (j < s.length && classOf(s[j]) == cls && entityAt(s, j) == null) j++
            if (cls != Cls.SPACE) out.add(Block(s.substring(i, j), kindOf(cls))) // drop blank runs
            i = j
        }
        return out
    }

    /** Just the block strings (for the UI chip list). */
    fun blocks(s: String): List<String> = split(s).map { it.text }

    private fun entityAt(s: String, i: Int): Block? {
        EMAIL.matchAt(s, i)?.let { return Block(it.value, Kind.EMAIL) }
        URL.matchAt(s, i)?.let { return Block(trimTrailer(it.value), Kind.LINK) }
        WWW.matchAt(s, i)?.let { return Block(trimTrailer(it.value), Kind.LINK) }
        DOMAIN.matchAt(s, i)?.let { return Block(trimTrailer(it.value), Kind.LINK) }
        return null
    }

    /**
     * Strip trailing sentence punctuation a greedy URL/domain match over-grabbed — but keep a closing
     * bracket that has a matching opener inside the match, so `…/Foo_(bar)` is NOT cut to `…/Foo_(bar`.
     */
    private fun trimTrailer(v: String): String {
        var s = v
        while (s.isNotEmpty()) {
            val c = s.last()
            val strip = when (c) {
                '.', ',', '!', '?', ';', ':', '"', '\'', '>' -> true
                ')' -> s.count { it == ')' } > s.count { it == '(' }
                ']' -> s.count { it == ']' } > s.count { it == '[' }
                '}' -> s.count { it == '}' } > s.count { it == '{' }
                else -> false
            }
            if (!strip) break
            s = s.dropLast(1)
        }
        return if (s.isEmpty()) v else s
    }

    private fun classOf(c: Char): Cls = when {
        c.isWhitespace() -> Cls.SPACE
        c in '一'..'鿿' -> Cls.HAN
        c in 'a'..'z' || c in 'A'..'Z' -> Cls.LATIN
        c in '0'..'9' -> Cls.DIGIT
        else -> Cls.SYMBOL
    }

    private fun kindOf(c: Cls): Kind = when (c) {
        Cls.HAN -> Kind.HAN
        Cls.LATIN -> Kind.LATIN
        Cls.DIGIT -> Kind.DIGIT
        else -> Kind.SYMBOL
    }
}
