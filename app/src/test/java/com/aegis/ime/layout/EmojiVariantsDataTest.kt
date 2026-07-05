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

class EmojiVariantsDataTest {

    private data class Rec(val cps: List<Int>, val str: String, val group: String, val sub: String)

    private val TONES = listOf(0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF)
    private val toneSet = TONES.toSet()
    private val personOrHand = setOf(0x1F9D1, 0x1F468, 0x1F469, 0x1FAF1, 0x1FAF2)

    private val recs: List<Rec> by lazy { parse() }
    private val fqset: Set<String> by lazy { recs.map { it.str }.toSet() }
    private val base: List<Rec> by lazy { recs.filter { r -> r.cps.none { it in toneSet } } }

    private fun parse(): List<Rec> {
        val text = javaClass.getResourceAsStream("/emoji-test-16.0.txt")
            ?: error("bundled emoji-test-16.0.txt missing from test resources")
        val out = ArrayList<Rec>()
        var group = ""; var sub = ""
        for (raw in text.bufferedReader().readLines()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("# group:") -> group = line.substringAfter(":").trim()
                line.startsWith("# subgroup:") -> sub = line.substringAfter(":").trim()
                line.isEmpty() || line.startsWith("#") -> {}
                else -> {
                    val m = Regex("^([0-9A-Fa-f ]+);\\s*([a-z-]+)").find(line) ?: continue
                    if (m.groupValues[2] != "fully-qualified") continue
                    val cps = m.groupValues[1].trim().split(" ").map { it.toInt(16) }
                    val s = cps.joinToString("") { String(Character.toChars(it)) }
                    out.add(Rec(cps, s, group, sub))
                }
            }
        }
        return out
    }

    private fun cells(): List<String> = EmojiCatalog.categories.flatMap { it.emoji }

    private fun reachable(): Set<String> {
        val r = HashSet<String>()
        for (c in cells()) for (g in EmojiVariants.genderForms(c)) {
            r.add(g); r.addAll(EmojiVariants.skinForms(g))
        }
        return r
    }


    @Test fun every_catalog_cell_is_a_real_fully_qualified_rgi_emoji() {
        val bad = cells().filter { it !in fqset }
        assertTrue("catalog cells that are not fully-qualified RGI emoji (typo / wrong form): $bad", bad.isEmpty())
    }


    @Test fun skin_capable_bases_yield_five_rgi_tones_each() {
        assertEquals("skin-capable base count", 316, EmojiVariants.skinCapable.size)
        for (b in EmojiVariants.skinCapable) {
            assertTrue("skin base '$b' is not RGI", b in fqset)
            for (t in EmojiVariants.SKIN_TONES) {
                val toned = EmojiVariants.applyTone(b, t)
                assertTrue("applyTone('$b') → '$toned' is not RGI", toned in fqset)
            }
        }
    }


    @Test fun gender_swap_families_resolve_to_rgi_man_and_woman() {
        assertEquals("gender-swap family count", 28, EmojiVariants.genderSwap.size)
        for (b in EmojiVariants.genderSwap) {
            val forms = EmojiVariants.genderForms(b)
            assertEquals("$b: [neutral, man, woman]", 3, forms.size)
            assertEquals("$b: first form is the neutral base", b, forms[0])
            for (f in forms) assertTrue("$b → '$f' not RGI", f in fqset)
        }
    }

    @Test fun gender_sign_families_resolve_to_rgi_man_and_woman() {
        assertEquals("gender-sign family count", 51, EmojiVariants.genderSign.size)
        for (b in EmojiVariants.genderSign) {
            val forms = EmojiVariants.genderForms(b)
            assertEquals("$b: [neutral, man, woman]", 3, forms.size)
            for (f in forms) assertTrue("$b → '$f' not RGI", f in fqset)
        }
    }

    @Test fun standalone_person_singles_resolve_to_rgi() {
        for (b in listOf("🧒", "🧓")) {
            val forms = EmojiVariants.genderForms(b)
            assertEquals("$b: [neutral, boy/man, girl/woman]", 3, forms.size)
            for (f in forms) assertTrue("$b → '$f' not RGI", f in fqset)
        }
    }


    @Test fun every_base_emoji_is_reachable_and_coverage_matches_the_stated口径() {
        val reach = reachable()
        val baseStrs = base.map { it.str }
        val unreachableBase = baseStrs.filter { it !in reach }
        assertTrue("base (non-skin-tone) emoji not reachable: $unreachableBase", unreachableBase.isEmpty())
        assertEquals("base emoji count (Unicode v16 fully-qualified, no skin tone)", 1906, baseStrs.size)

        val covered = reach.intersect(fqset)
        assertEquals("fully-qualified RGI total", 3781, fqset.size)
        assertEquals("reachable RGI emoji", 3486, covered.size)

        val excluded = fqset - reach
        assertEquals("excluded RGI emoji", 295, excluded.size)
        val excludedNoTone = excluded.filter { s -> s.codePoints().noneMatch { it in toneSet } }
        assertTrue("a non-skin-tone emoji is unreachable (would be a real coverage hole): $excludedNoTone", excludedNoTone.isEmpty())
    }

    @Test fun excluded_forms_are_exactly_per_person_multitone_couples() {
        val excluded = fqset - reachable()
        fun strip(e: String): String =
            e.codePoints().filter { it !in toneSet }.toArray().joinToString("") { String(Character.toChars(it)) }
        val bases = excluded.map { strip(it) }.toSet()
        assertEquals("excluded reduces to the 13 two-person couple/handshake bases", 13, bases.size)
        for (b in bases) {
            assertTrue("excluded base '$b' must NOT be a uniform-tone base", b !in EmojiVariants.skinCapable)
            val humans = b.codePoints().filter { it in personOrHand }.count()
            assertTrue("excluded base '$b' must be a two-person emoji (>=2 person/hand scalars)", humans >= 2)
            assertTrue("excluded base '$b' must be a ZWJ sequence", b.contains('‍'))
        }
    }


    @Test fun every_unicode_flag_is_present_in_the_flag_category() {
        val flagCells = EmojiCatalog.categories.first { it.id == "flag" }.emoji.toSet()
        val unicodeFlags = base.filter { it.group == "Flags" }.map { it.str }
        val missing = unicodeFlags.filter { it !in flagCells }
        assertTrue("Unicode flags missing from the flag category: $missing", missing.isEmpty())
        assertTrue("expected the full country/subdivision flag set", unicodeFlags.size >= 260)
    }
}
