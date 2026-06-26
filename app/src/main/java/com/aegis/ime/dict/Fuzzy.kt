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

package com.aegis.ime.dict

/**
 * Fuzzy pinyin normalization — MUST stay identical to `tools/Pinyin.fuzzyNormalize` (the fuzzy
 * index is built with that function; queries are normalized with this one). Collapses the most
 * common confusions: 平翘舌 zh/ch/sh -> z/c/s and 前后鼻音 ang/eng/ing -> an/en/in.
 */
object Fuzzy {
    fun normalize(s: String): String {
        var r = s
        r = r.replace("zh", "z").replace("ch", "c").replace("sh", "s")
        r = r.replace("ang", "an").replace("eng", "en").replace("ing", "in")
        return r
    }
}
