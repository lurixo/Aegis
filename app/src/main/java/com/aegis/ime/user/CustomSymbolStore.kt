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

    fun pageSnapshot(offset: Int, limit: Int, expectedVersion: Long? = null): PersistedPage<String> {
        require(offset >= 0)
        require(limit >= 0)
        return database?.let { backing ->
            runCatching { backing.readCustomItemsPage(key, offset, minOf(limit, RUNTIME_PAGE_SIZE), expectedVersion) }
                .onFailure { lastFailure = it.javaClass.simpleName + ": " + it.message.orEmpty() }
                .getOrElse { PersistedPage(emptyList(), backing.dataVersion(), restartRequired = true) }
        } ?: if (expectedVersion != null && expectedVersion != 0L) {
            PersistedPage(emptyList(), 0L, restartRequired = true)
        } else {
            val items = legacyItems()
            PersistedPage(items.drop(offset).take(limit), 0L, items.size.toLong())
        }
    }

    internal fun pagedList(excluded: Set<String> = emptySet()): List<String> {
        val backing = database ?: return legacyItems().filterNot { it in excluded }
        return DatabasePagedList(backing, excluded.toSet())
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

    fun contains(symbol: String): Boolean = database?.containsCustomItem(key, symbol) ?: (symbol in legacyItems())

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

    private inner class DatabasePagedList(
        private val backing: UserDataDatabase,
        private val excluded: Set<String>,
    ) : AbstractList<String>() {
        private val pages = BoundedLruCache<Int, List<String>>(2)
        private var version: Long? = null
        private var total = -1

        override val size: Int
            @Synchronized get() {
                ensureMetadata()
                return total.coerceAtLeast(0)
            }

        @Synchronized
        override fun get(index: Int): String {
            if (index < 0) throw IndexOutOfBoundsException(index.toString())
            ensureMetadata()
            if (index >= total) throw IndexOutOfBoundsException("$index >= $total")
            val pageOffset = index / RUNTIME_PAGE_SIZE * RUNTIME_PAGE_SIZE
            pages[pageOffset]?.let { page ->
                return page.getOrElse(index - pageOffset) { throw IndexOutOfBoundsException(index.toString()) }
            }
            repeat(2) {
                val page = runCatching {
                    backing.readCustomItemsPageExcluding(
                        key,
                        excluded,
                        pageOffset,
                        RUNTIME_PAGE_SIZE,
                        version,
                    )
                }.onFailure {
                    lastFailure = it.javaClass.simpleName + ": " + it.message.orEmpty()
                }.getOrNull() ?: throw IndexOutOfBoundsException(index.toString())
                if (page.restartRequired) {
                    pages.clear()
                    version = null
                    total = -1
                    ensureMetadata()
                    if (index >= total) throw IndexOutOfBoundsException("$index >= $total")
                } else {
                    version = page.version
                    total = page.totalCount?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: total
                    pages.put(pageOffset, page.items)
                    if (pageOffset == 0) {
                        lastValid.clear()
                        lastValid.addAll(page.items)
                    }
                    lastFailure = null
                    return page.items.getOrElse(index - pageOffset) {
                        throw IndexOutOfBoundsException(index.toString())
                    }
                }
            }
            throw IndexOutOfBoundsException(index.toString())
        }

        private fun ensureMetadata() {
            if (total >= 0) return
            val page = runCatching {
                backing.readCustomItemsPageExcluding(key, excluded, 0, 0, version)
            }.onFailure {
                lastFailure = it.javaClass.simpleName + ": " + it.message.orEmpty()
            }.getOrNull()
            if (page == null || page.restartRequired) {
                version = null
                total = lastValid.count { it !in excluded }
                return
            }
            version = page.version
            total = page.totalCount?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
            lastFailure = null
        }
    }

    private companion object {
        const val RUNTIME_PAGE_SIZE = 128
    }
}
