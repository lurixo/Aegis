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

import android.icu.text.BreakIterator
import java.util.Locale

object ClipSplitter {

    enum class Kind { LINK, EMAIL, HAN, KANA, LATIN, DIGIT, SYMBOL }

    data class Block(val text: String, val kind: Kind)

    private enum class Cat { CJK, LATIN, DIGIT, SYMBOL, SPACE }

    private const val KEYCAP = 0x20E3
    private const val ZWJ = 0x200D
    private const val VS15 = 0xFE0E
    private const val VS16 = 0xFE0F
    private const val PROLONGED = 0x30FC
    private const val IDEO_ITER = 0x3005

    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val LINK = Regex("(?:https?://|ftp://|www\\.)[A-Za-z0-9._~:/?#\\[\\]@!\$&'()*+,;=%\\-]+")
    private const val URL_TRAIL = ".,;:!?'\""

    fun split(s: String): List<Block> {
        if (s.isEmpty()) return emptyList()
        val bounds = clusterBounds(s)
        val nb = bounds.size - 1
        val japanese = hasKana(s)
        val out = ArrayList<Block>()
        var bi = 0
        while (bi < nb) {
            val start = bounds[bi]
            val entity = entityAt(s, start)
            if (entity != null) {
                val endBi = boundaryIndex(bounds, start + entity.text.length)
                if (endBi > bi) { out.add(entity); bi = endBi; continue }
            }
            when (categoryOf(s, bounds[bi], bounds[bi + 1])) {
                Cat.SPACE -> bi++
                Cat.CJK -> {
                    var m = bi + 1
                    while (m < nb && entityAt(s, bounds[m]) == null &&
                        categoryOf(s, bounds[m], bounds[m + 1]) == Cat.CJK) m++
                    val run = s.substring(bounds[bi], bounds[m])
                    if (japanese) segmentJapanese(run, out) else segmentChinese(run, out)
                    bi = m
                }
                Cat.LATIN, Cat.DIGIT -> {
                    val endBi = tokenEnd(s, bounds, bi, nb)
                    val text = s.substring(bounds[bi], bounds[endBi])
                    out.add(Block(text, tokenKind(text)))
                    bi = endBi
                }
                Cat.SYMBOL -> {
                    var m = bi + 1
                    while (m < nb && entityAt(s, bounds[m]) == null &&
                        categoryOf(s, bounds[m], bounds[m + 1]) == Cat.SYMBOL) m++
                    out.add(Block(s.substring(bounds[bi], bounds[m]), Kind.SYMBOL))
                    bi = m
                }
            }
        }
        return out
    }

    fun blocks(s: String): List<String> = split(s).map { it.text }

    private fun clusterBounds(s: String): IntArray {
        val it = BreakIterator.getCharacterInstance()
        it.setText(s)
        val list = ArrayList<Int>()
        list.add(0)
        var e = it.next()
        while (e != BreakIterator.DONE) { list.add(e); e = it.next() }
        return list.toIntArray()
    }

    private fun boundaryIndex(bounds: IntArray, charPos: Int): Int {
        val idx = java.util.Arrays.binarySearch(bounds, charPos)
        return if (idx >= 0) idx else -1
    }

    private fun tokenEnd(s: String, bounds: IntArray, bi: Int, nb: Int): Int {
        var m = bi + 1
        while (m < nb) {
            if (entityAt(s, bounds[m]) != null) break
            val cat = categoryOf(s, bounds[m], bounds[m + 1])
            if (cat == Cat.LATIN || cat == Cat.DIGIT) { m++; continue }
            if (cat == Cat.SYMBOL && m + 1 < nb && bounds[m + 1] - bounds[m] == 1 &&
                entityAt(s, bounds[m + 1]) == null) {
                val c = s[bounds[m]]
                val left = categoryOf(s, bounds[m - 1], bounds[m])
                val right = categoryOf(s, bounds[m + 1], bounds[m + 2])
                if (isInfix(c, left, right)) { m += 2; continue }
            }
            break
        }
        return m
    }

    private fun isInfix(c: Char, left: Cat, right: Cat): Boolean = when (c) {
        '-', '_' -> left.alnum() && right.alnum()
        '.', ',' -> left == Cat.DIGIT && right == Cat.DIGIT
        '\'', '’' -> left == Cat.LATIN && right == Cat.LATIN
        else -> false
    }

    private fun Cat.alnum(): Boolean = this == Cat.LATIN || this == Cat.DIGIT

    private fun tokenKind(t: String): Kind {
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            if (Character.isLetter(cp)) return Kind.LATIN
            i += Character.charCount(cp)
        }
        return Kind.DIGIT
    }

    private fun categoryOf(s: String, start: Int, end: Int): Cat {
        val cp = s.codePointAt(start)
        val base = baseCat(cp)
        if (end - start == Character.charCount(cp)) return base
        var i = start + Character.charCount(cp)
        while (i < end) {
            val c = s.codePointAt(i)
            if (c == ZWJ || c == KEYCAP || c == VS15 || c == VS16 ||
                c in 0x1F1E6..0x1F1FF || c in 0x1F3FB..0x1F3FF) return Cat.SYMBOL
            i += Character.charCount(c)
        }
        return base
    }

    private fun baseCat(cp: Int): Cat = when {
        Character.isWhitespace(cp) -> Cat.SPACE
        isCjk(cp) -> Cat.CJK
        Character.isLetter(cp) -> Cat.LATIN
        cp in '0'.code..'9'.code -> Cat.DIGIT
        else -> Cat.SYMBOL
    }

    private fun isCjk(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA -> true
        else -> cp == PROLONGED || cp == IDEO_ITER
    }

    private fun hasKana(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> return true
                else -> {}
            }
            i += Character.charCount(cp)
        }
        return false
    }

    private fun segmentChinese(run: String, out: ArrayList<Block>) {
        val it = BreakIterator.getWordInstance(Locale.SIMPLIFIED_CHINESE)
        it.setText(run)
        var a = it.first()
        var b = it.next()
        while (b != BreakIterator.DONE) {
            out.add(Block(run.substring(a, b), Kind.HAN))
            a = b
            b = it.next()
        }
    }

    private enum class JScript { KANJI, HIRA, KATA }

    private fun jScript(cp: Int): JScript = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HIRAGANA -> JScript.HIRA
        Character.UnicodeScript.KATAKANA -> JScript.KATA
        else -> if (cp == PROLONGED) JScript.KATA else JScript.KANJI
    }

    private fun segmentJapanese(run: String, out: ArrayList<Block>) {
        val runs = ArrayList<IntArray>()
        var i = 0
        while (i < run.length) {
            val js = jScript(run.codePointAt(i))
            var j = i + Character.charCount(run.codePointAt(i))
            while (j < run.length && jScript(run.codePointAt(j)) == js) j += Character.charCount(run.codePointAt(j))
            runs.add(intArrayOf(i, j, js.ordinal))
            i = j
        }
        var k = 0
        while (k < runs.size) {
            val a = runs[k][0]
            if (runs[k][2] == JScript.KANJI.ordinal) {
                var end = runs[k][1]
                if (k + 1 < runs.size && runs[k + 1][2] == JScript.HIRA.ordinal) { end = runs[k + 1][1]; k++ }
                out.add(Block(run.substring(a, end), Kind.HAN))
            } else {
                out.add(Block(run.substring(a, runs[k][1]), Kind.KANA))
            }
            k++
        }
    }

    private fun entityAt(s: String, i: Int): Block? {
        val email = EMAIL.matchAt(s, i)?.value
        val link = LINK.matchAt(s, i)?.value?.let { trimUrl(it) }
        return when {
            email != null && (link == null || email.length >= link.length) -> Block(email, Kind.EMAIL)
            link != null -> Block(link, Kind.LINK)
            else -> null
        }
    }

    private fun trimUrl(u: String): String? {
        var end = u.length
        while (end > 0) {
            val c = u[end - 1]
            val drop = c in URL_TRAIL ||
                (c == ')' && count(u, end, ')') > count(u, end, '(')) ||
                (c == ']' && count(u, end, ']') > count(u, end, '[')) ||
                (c == '}' && count(u, end, '}') > count(u, end, '{'))
            if (drop) end-- else break
        }
        val v = u.substring(0, end)
        val hasBody = (v.startsWith("www.") && v.length > 4) ||
            (v.contains("://") && v.substringAfter("://").isNotEmpty())
        return if (hasBody) v else null
    }

    private fun count(s: String, end: Int, ch: Char): Int {
        var n = 0
        for (k in 0 until end) if (s[k] == ch) n++
        return n
    }
}
