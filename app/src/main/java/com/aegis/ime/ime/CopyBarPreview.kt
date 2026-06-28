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

package com.aegis.ime.ime

internal class CopyBarPreview(private val source: String, private val window: Int, private val step: Int) {

    init {
        require(window > 0) { "window=$window" }
        require(step in 1..window) { "step=$step window=$window" }
    }

    private val lastStart = maxOf(0, source.length - window)

    var start: Int = 0
        private set

    val slides: Boolean get() = lastStart > 0

    val end: Int get() = minOf(source.length, start + window)

    fun text(): String = source.substring(start, end)

    fun forward(): Boolean {
        if (start >= lastStart) return false
        start = minOf(lastStart, start + step)
        return true
    }

    fun back(): Boolean {
        if (start <= 0) return false
        start = maxOf(0, start - step)
        return true
    }
}
