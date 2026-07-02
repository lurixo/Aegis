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

package com.aegis.ime.decoder

import com.aegis.ime.dict.BinaryDict
import com.aegis.tools.T2SMerge
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExhaustiveDecodeAuditTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")

    private val dict: BinaryDict by lazy { BinaryDict.fromFile(dictFile) }

    private fun sample(s: Collection<String>, n: Int = 8): String =
        s.take(n).joinToString(" ") + if (s.size > n) " …(${s.size})" else ""

    private val mappedForms: Set<String> by lazy {
        javaClass.classLoader!!.getResourceAsStream("tc1_mapped_forms.txt")!!
            .bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
    }

    @Test fun mappedFormList_coversEveryFormThisRepoOwnConversionDataMapsAway() {
        val derived = T2SMerge.load(File("../tools/t2s-data")).mappedSourceForms()
            .filter { it.codePointCount(0, it.length) == 1 }
        val missing = derived.filterNot { it in mappedForms }

        assertTrue("tools/t2s-data maps away ~4300 single characters (drift guard): ${derived.size}",
            derived.size > 4000)
        assertTrue(
            "tc1_mapped_forms.txt omits ${missing.size} of ${derived.size} forms tools/t2s-data maps away: ${sample(missing)}",
            missing.isEmpty(),
        )
    }
}
