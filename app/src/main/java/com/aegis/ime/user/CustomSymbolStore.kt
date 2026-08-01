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

import android.content.SharedPreferences
import java.security.MessageDigest

class CustomSymbolStore private constructor(
    private val prefs: SharedPreferences,
    private val key: String,
    private val database: UserDataDatabase?,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) {

    constructor(prefs: SharedPreferences, key: String = "custom_symbols") : this(prefs, key, null, Unit)

    internal constructor(prefs: SharedPreferences, key: String, database: UserDataDatabase) :
        this(prefs, key, database, Unit)

    @Volatile
    var lastFailure: String? = null
        private set

    private val lastValid = ArrayList<String>()

    fun list(): List<String> = runCatching {
        val current = database?.readCustomItems(key, 0, RUNTIME_PAGE_SIZE) ?: legacyItems()
        lastValid.clear()
        lastValid.addAll(current)
        lastFailure = null
        current
    }.onFailure {
        lastFailure = it.javaClass.simpleName + ": " + it.message.orEmpty()
    }.getOrElse { lastValid.toList() }

    fun page(offset: Int, limit: Int): List<String> {
        require(offset >= 0)
        require(limit >= 0)
        return runCatching {
            database?.readCustomItems(key, offset, minOf(limit, RUNTIME_PAGE_SIZE)) ?:
                legacyItems().drop(offset).take(limit)
        }.onFailure {
            lastFailure = it.javaClass.simpleName + ": " + it.message.orEmpty()
        }.getOrElse { lastValid.drop(offset).take(limit) }
    }

    fun add(symbol: String): Boolean {
        val s = symbol.filterNot { it.isISOControl() }.trim()
        database?.let { backing ->
            if (s.isEmpty()) return false
            return runCatching { backing.addCustomItem(key, s) }
                .onSuccess { added ->
                    if (added && s !in lastValid && lastValid.size < RUNTIME_PAGE_SIZE) lastValid.add(s)
                    lastFailure = null
                }
                .onFailure { lastFailure = it.javaClass.simpleName + ": " + it.message.orEmpty() }
                .getOrDefault(false)
        }
        val cur = list()
        if (s.isEmpty() || s in cur) return false
        return save(cur + s)
    }

    fun remove(symbol: String): Boolean {
        database?.let { backing ->
            return runCatching { backing.removeCustomItem(key, symbol) }
                .onSuccess { removed -> if (removed) lastValid.remove(symbol); lastFailure = null }
                .onFailure { lastFailure = it.javaClass.simpleName + ": " + it.message.orEmpty() }
                .getOrDefault(false)
        }
        val cur = list()
        return symbol in cur && save(cur - symbol)
    }

    fun count(): Long = database?.customItemCount(key) ?: legacyItems().size.toLong()

    private fun save(items: List<String>): Boolean = runCatching {
        val saved = if (database == null) {
            prefs.edit().putString(key, items.joinToString("\n")).commit()
        } else {
            database.replaceCustomItems(key, items)
            true
        }
        if (!saved) throw IllegalStateException("custom item persistence failed")
        lastValid.clear()
        lastValid.addAll(items)
        lastFailure = null
        true
    }.onFailure {
        lastFailure = it.javaClass.simpleName + ": " + it.message.orEmpty()
    }.getOrDefault(false)

    internal fun legacyItems(): List<String> =
        prefs.getString(key, "").orEmpty().split("\n").filter { it.isNotEmpty() }

    internal fun storageKind(): String = key

    internal fun hasLegacyValue(): Boolean = prefs.contains(key)

    internal fun legacyIdentity(): String {
        val value = prefs.getString(key, "").orEmpty()
        val bytes = value.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return bytes.size.toString() + ":" + digest
    }

    private companion object {
        const val RUNTIME_PAGE_SIZE = 128
    }
}
