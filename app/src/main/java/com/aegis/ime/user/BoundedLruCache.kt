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

internal class BoundedLruCache<K, V>(private val maximumSize: Int) {
    init {
        require(maximumSize > 0)
    }

    private val values = object : LinkedHashMap<K, V>(maximumSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maximumSize
    }

    @Synchronized
    operator fun get(key: K): V? = values[key]

    @Synchronized
    fun put(key: K, value: V) {
        values[key] = value
    }

    @Synchronized
    fun remove(key: K) {
        values.remove(key)
    }

    @Synchronized
    fun clear() {
        values.clear()
    }

    @Synchronized
    fun snapshot(): Map<K, V> = LinkedHashMap(values)

    @Synchronized
    internal fun sizeForTest(): Int = values.size
}
