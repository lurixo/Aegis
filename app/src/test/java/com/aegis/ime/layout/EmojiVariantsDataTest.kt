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

    private val recs: List<Rec> by lazy { parse() }
    private val fqset: Set<String> by lazy { recs.map { it.str }.toSet() }

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
}
