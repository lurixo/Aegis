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

import java.text.Normalizer

object EnglishKey {

    fun normalize(text: String): String {
        if (text.isEmpty()) return ""
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val out = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            when (ch) {
                in 'a'..'z', in '0'..'9' -> out.append(ch)
                in 'A'..'Z' -> out.append(ch.lowercaseChar())
                else -> Unit
            }
        }
        return out.toString()
    }
}
