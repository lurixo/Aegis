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

package com.aegis.ime.backup

import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.SymbolUsageStore
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import java.io.File

internal enum class BackupItem(val relativePath: String) {
    DICTIONARY("userdb.txt"),
    LEARNING("userlearn.txt"),
    PHRASES("phrases.txt"),
    CLIPBOARD("clipboard.txt"),
    SYMBOL_USAGE("symbol_usage.txt"),
    EMOJI_USAGE("emoji/symbol_usage.txt"),
}

internal object StoreHealth {

    fun unreadableIn(root: File, liveStores: Boolean): Set<BackupItem> {
        val out = LinkedHashSet<BackupItem>()
        for (item in BackupItem.entries) if (!readable(root, item, liveStores)) out.add(item)
        return out
    }

    fun readable(root: File, item: BackupItem, liveStores: Boolean): Boolean {
        val file = File(root, item.relativePath)
        if (!file.exists()) return true
        return when (item) {
            BackupItem.DICTIONARY ->
                liveDictionary(liveStores)?.dictionaryReadable() ?: probeDictionary(file)
            BackupItem.LEARNING ->
                liveDictionary(liveStores)?.learnedReadable() ?: probeLearning(file)
            BackupItem.PHRASES ->
                liveClipboard(liveStores)?.phrasesReadable ?: probeClipboard(root) { it.phrasesReadable }
            BackupItem.CLIPBOARD ->
                liveClipboard(liveStores)?.historyReadable ?: probeClipboard(root) { it.historyReadable }
            BackupItem.SYMBOL_USAGE, BackupItem.EMOJI_USAGE ->
                SymbolUsageStore(file.parentFile ?: root).also { it.load() }.readable
        }
    }

    private fun liveDictionary(liveStores: Boolean): UserDictHot.Host? =
        if (liveStores) UserDictHot.host else null

    private fun liveClipboard(liveStores: Boolean): ClipboardStore? =
        if (liveStores) LiveUserData.clipboardHost else null

    private fun probeDictionary(file: File): Boolean =
        runCatching { UserModel().load(file, sweepStale = false) }.isSuccess

    private fun probeLearning(file: File): Boolean =
        UserLearning().apply { load(file) }.readable

    private fun probeClipboard(root: File, of: (ClipboardStore) -> Boolean): Boolean {
        val probe = ClipboardStore(root).also { it.load() }
        return try {
            of(probe)
        } finally {
            probe.stopSaving()
        }
    }
}
