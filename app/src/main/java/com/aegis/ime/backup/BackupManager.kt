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

import android.content.SharedPreferences
import com.aegis.ime.user.ClipEntry
import com.aegis.ime.user.ClipboardImages
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.SymbolUsageStore
import com.aegis.ime.user.UserDictEdit
import com.aegis.ime.user.UserDictExport
import com.aegis.ime.user.UserLexicon
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

object BackupManager {


    private val USERDB = BackupItem.DICTIONARY.relativePath
    private val USERLEARN = BackupItem.LEARNING.relativePath
    private val CLIPBOARD = BackupItem.CLIPBOARD.relativePath
    private const val CLIPS_DIR = "clips"
    private const val BIG_CLIP_LINE = "B\t"

    private val DOWNLOAD_STATE_KEYS = setOf(
        "engine_pack_touch",
        "gram_validator",
        "gram_sha256",
        "gram_size_bytes",
        "dict_validator",
        "dict_sha256",
        "dict_asset_name",
        "dict_asset_url",
        "dict_release_tag",
        "dict_release_published_at",
    )

    internal class ExportReport(val omitted: Set<BackupItem>)


    internal fun export(
        filesDir: File,
        prefs: SharedPreferences,
        password: CharArray,
        rawOut: OutputStream,
    ): ExportReport {
        if (!UserDictEdit.flushBeforeExport()) throw BackupException(BackupError.IO_ERROR)
        LiveUserData.flushBeforeExport()
        SymbolUsageStore.flushPendingWrites()
        val omitted = StoreHealth.unreadableIn(filesDir, liveStores = true)
        val exportedPrefs = prefs.all.filterKeys { it !in DOWNLOAD_STATE_KEYS }.toMutableMap()
        for (key in listOf(UserLexicon.PREF_ENGLISH_WORDS, UserLexicon.PREF_EMAIL_DOMAINS,
            UserLexicon.PREF_DISABLED_EMAIL_DOMAINS)) {
            if (!exportedPrefs.containsKey(key)) exportedPrefs[key] = emptySet<String>()
        }
        val prefsBlob = PrefsCodec.encode(exportedPrefs)
        val legacyPrefs = BackupArchive.fitsLegacyPrefsEntry(prefsBlob)
        val version =
            if (legacyPrefs) BackupFormat.HEADER_VERSION else BackupFormat.HEADER_VERSION_CHUNKED_PREFS
        BackupCrypto.writeEncrypted(rawOut, password, version) { cipherOut ->
            val gzip = GZIPOutputStream(cipherOut)
            val out = DataOutputStream(gzip)
            if (legacyPrefs) BackupArchive.writePrefs(out, prefsBlob) else BackupArchive.writePrefsChunked(out, prefsBlob)
            for (rel in backupRelPaths(filesDir, omitted)) {
                val file = File(filesDir, rel)
                if ((rel == USERDB || rel == USERLEARN) && (!file.exists() || file.length() == 0L)) {
                    val empty = if (rel == USERDB) "aegis-userdb 3\n" else "aegis-userlearn 1\n"
                    BackupArchive.writeBytes(out, rel, empty.toByteArray())
                } else if (!file.isFile) {
                    continue
                } else if (rel == USERDB) {
                    val shared = ByteArrayOutputStream()
                    file.inputStream().use { UserDictExport.copyWithoutTombstones(it, shared) }
                    BackupArchive.writeBytes(out, rel, shared.toByteArray())
                } else {
                    BackupArchive.writeFile(out, rel, file)
                }
            }
            BackupArchive.writeEnd(out)
            out.flush()
            gzip.finish()
        }
        return ExportReport(omitted)
    }

    private fun backupRelPaths(filesDir: File, omitted: Set<BackupItem>): List<String> {
        val paths = ArrayList<String>()
        for (item in BackupItem.entries) {
            if (item in omitted) continue
            if (item == BackupItem.DICTIONARY || item == BackupItem.LEARNING ||
                File(filesDir, item.relativePath).isFile) paths.add(item.relativePath)
        }
        if (BackupItem.CLIPBOARD in omitted) return paths
        val referencedClips = referencedClipSidecarNames(File(filesDir, CLIPBOARD))
        for (name in referencedClips.sorted()) {
            val rel = "$CLIPS_DIR/$name"
            if (File(filesDir, rel).isFile && BackupArchive.sanitizedRelativePath(rel) != null) paths.add(rel)
        }
        return paths
    }

    private fun referencedClipSidecarNames(index: File): Set<String> {
        if (!index.isFile) return emptySet()
        val names = LinkedHashSet<String>()
        runCatching {
            index.forEachLine { line ->
                val image = ClipEntry.imageReference(line)
                if (image != null) {
                    names.add("images/${image.first}.${ClipboardImages.extension(image.second)}")
                } else if (line.startsWith(BIG_CLIP_LINE)) {
                    val name = line.substring(BIG_CLIP_LINE.length) + ".txt"
                    val rel = "$CLIPS_DIR/$name"
                    if (BackupArchive.sanitizedRelativePath(rel) != null) names.add(name)
                }
            }
        }
        return names
    }
}
