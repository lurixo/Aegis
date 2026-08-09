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
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.SymbolUsageStore
import com.aegis.ime.user.UserDictEdit
import com.aegis.ime.user.UserDictExport
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.UserDictImport
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object BackupManager {


    private const val USERDB = "userdb.txt"
    private const val USERLEARN = "userlearn.txt"
    private const val PHRASES = "phrases.txt"
    private const val CLIPBOARD = "clipboard.txt"
    private const val CLIPS_DIR = "clips"
    private const val BIG_CLIP_LINE = "B\t"
    private const val SYMBOL_USAGE = "symbol_usage.txt"
    private const val EMOJI_DIR = "emoji"
    private const val EMOJI_USAGE = "emoji/symbol_usage.txt"
    private const val STAGING_DIR = "backup_staging"

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

    enum class Mode { OVERWRITE, MERGE }


    fun export(filesDir: File, prefs: SharedPreferences, password: CharArray, rawOut: OutputStream) {
        if (!UserDictEdit.flushBeforeExport()) throw BackupException(BackupError.IO_ERROR)
        LiveUserData.flushBeforeExport()
        val prefsBlob = PrefsCodec.encode(prefs.all.filterKeys { it !in DOWNLOAD_STATE_KEYS })
        val legacyPrefs = BackupArchive.fitsLegacyPrefsEntry(prefsBlob)
        val version =
            if (legacyPrefs) BackupFormat.HEADER_VERSION else BackupFormat.HEADER_VERSION_CHUNKED_PREFS
        BackupCrypto.writeEncrypted(rawOut, password, version) { cipherOut ->
            val gzip = GZIPOutputStream(cipherOut)
            val out = DataOutputStream(gzip)
            if (legacyPrefs) BackupArchive.writePrefs(out, prefsBlob) else BackupArchive.writePrefsChunked(out, prefsBlob)
            for (rel in backupRelPaths(filesDir)) {
                val file = File(filesDir, rel)
                if (!file.isFile) continue
                if (rel == USERDB) {
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
    }

    private fun backupRelPaths(filesDir: File): List<String> {
        val paths = ArrayList<String>()
        for (name in listOf(USERDB, USERLEARN, PHRASES, CLIPBOARD, SYMBOL_USAGE)) {
            if (File(filesDir, name).isFile) paths.add(name)
        }
        if (File(filesDir, EMOJI_USAGE).isFile) paths.add(EMOJI_USAGE)
        val referencedClips = referencedClipSidecarNames(File(filesDir, CLIPBOARD))
        File(filesDir, CLIPS_DIR).listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.name !in referencedClips) return@forEach
            val rel = "$CLIPS_DIR/${f.name}"
            if (f.isFile && BackupArchive.sanitizedRelativePath(rel) != null) paths.add(rel)
        }
        return paths
    }

    private fun referencedClipSidecarNames(index: File): Set<String> {
        if (!index.isFile) return emptySet()
        val names = LinkedHashSet<String>()
        runCatching {
            index.forEachLine { line ->
                if (line.startsWith(BIG_CLIP_LINE)) {
                    val name = line.substring(BIG_CLIP_LINE.length) + ".txt"
                    val rel = "$CLIPS_DIR/$name"
                    if (BackupArchive.sanitizedRelativePath(rel) != null) names.add(name)
                }
            }
        }
        return names
    }


    fun restore(
        filesDir: File,
        prefs: SharedPreferences,
        password: CharArray,
        rawIn: InputStream,
        mode: Mode,
    ): Mode {
        val staging = File(filesDir, STAGING_DIR)
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw BackupException(BackupError.IO_ERROR)
        LiveUserData.restoreInProgress = true
        var handedOff = false
        try {
            try {
                val live = UserDictHot.host
                if (live != null && !live.flushDictionary() && live.dictionaryReadable()) {
                    throw IOException("user dictionary flush failed")
                }
                LiveUserData.flushBeforeRestore()
            } catch (e: Exception) {
                throw BackupException(BackupError.IO_ERROR, e)
            }

            val visitor = StagingVisitor(staging)
            try {
                BackupCrypto.readDecrypted(rawIn, password) { plainIn ->
                    GZIPInputStream(plainIn).use { gzip ->
                        BackupArchive.read(DataInputStream(gzip), visitor)
                    }
                }
            } catch (e: BackupException) {
                throw e
            } catch (e: Exception) {
                throw BackupException(BackupError.WRONG_PASSWORD_OR_CORRUPT, e)
            }

            try {
                commit(filesDir, prefs, staging, visitor.prefsBlob, mode)
            } catch (e: Exception) {
                throw BackupException(BackupError.IO_ERROR, e)
            }

            val reload = LiveUserData.onRestored
            if (reload != null) {
                reload()
                handedOff = true
            }
            return mode
        } finally {
            staging.deleteRecursively()
            if (!handedOff) LiveUserData.restoreInProgress = false
        }
    }

    private class StagingVisitor(private val staging: File) : BackupArchive.Visitor {
        var prefsBlob: ByteArray? = null
            private set

        override fun onPrefs(blob: ByteArray) {
            prefsBlob = blob
        }

        override fun openFile(relativePath: String): OutputStream {
            val dest = File(staging, relativePath)
            dest.parentFile?.mkdirs()
            return dest.outputStream()
        }
    }

    private fun commit(
        filesDir: File,
        prefs: SharedPreferences,
        staging: File,
        prefsBlob: ByteArray?,
        mode: Mode,
    ) {
        val merge = mode == Mode.MERGE
        applyPrefs(prefs, prefsBlob, merge)
        applyUserDb(filesDir, staging, merge)
        applyUserLearning(filesDir, staging, merge)
        applyPhrases(filesDir, staging, merge)
        applyClipboard(filesDir, staging, merge)
        applySymbolUsage(filesDir, staging, merge)
        applyEmojiUsage(filesDir, staging, merge)
    }

    private fun applyPrefs(prefs: SharedPreferences, blob: ByteArray?, merge: Boolean) {
        if (blob == null) return
        val decoded = PrefsCodec.decode(blob).filterKeys { it !in DOWNLOAD_STATE_KEYS }
        val editor = prefs.edit()
        for ((key, value) in decoded) {
            if (merge && prefs.contains(key)) continue
            when (value) {
                is PrefsCodec.Value.Bool -> editor.putBoolean(key, value.v)
                is PrefsCodec.Value.Integer -> editor.putInt(key, value.v)
                is PrefsCodec.Value.LongVal -> editor.putLong(key, value.v)
                is PrefsCodec.Value.FloatVal -> editor.putFloat(key, value.v)
                is PrefsCodec.Value.Str -> editor.putString(key, value.v)
                is PrefsCodec.Value.StrSet -> editor.putStringSet(key, value.v)
            }
        }
        if (!editor.commit()) throw IOException("preferences restore failed")
    }

    private fun applyUserDb(filesDir: File, staging: File, merge: Boolean) {
        val staged = File(staging, USERDB)
        if (!staged.isFile) return
        val now = System.currentTimeMillis()
        val host = UserDictHot.host
        val applied = if (host != null) {
            host.importUserDict(staged, merge, now)
        } else {
            UserDictImport.apply(staged, File(filesDir, USERDB), merge, now)
        }
        if (!applied) throw IOException("user dictionary import failed")
    }

    private fun applyUserLearning(filesDir: File, staging: File, merge: Boolean) {
        val staged = File(staging, USERLEARN)
        if (!staged.isFile) return
        val target = File(filesDir, USERLEARN)
        if (merge && target.isFile) return
        staged.copyTo(target, overwrite = true)
    }

    private fun applyPhrases(filesDir: File, staging: File, merge: Boolean) {
        val staged = File(staging, PHRASES)
        if (!staged.isFile) return
        val raw = staged.readText()
        val text = if (raw.lineSequence().any { it.startsWith("C\t") }) {
            raw
        } else {
            val migrated = ClipboardStore(staging).also { it.load() }
            if (migrated.phrases().isEmpty()) return
            migrated.exportPhrasesText()
        }
        val applied = ClipboardStore(filesDir).also { it.load() }.importPhrasesText(text, merge)
        if (!applied) return
    }

    private fun applyClipboard(filesDir: File, staging: File, merge: Boolean) {
        val stagedIndex = File(staging, CLIPBOARD)
        if (!stagedIndex.isFile) return
        val incoming = ClipboardStore(staging).also { it.load() }.history()
        ClipboardStore(filesDir).also { it.load() }.importHistory(incoming, merge)
    }

    private fun applySymbolUsage(filesDir: File, staging: File, merge: Boolean) {
        val staged = File(staging, SYMBOL_USAGE)
        if (!staged.isFile) return
        val incoming = SymbolUsageStore(staging).also { it.load() }.recentEntries()
        val applied = SymbolUsageStore(filesDir).also { it.load() }.importEntries(incoming, merge)
        if (!applied) throw IOException("symbol usage import failed")
    }

    private fun applyEmojiUsage(filesDir: File, staging: File, merge: Boolean) {
        val staged = File(staging, EMOJI_USAGE)
        if (!staged.isFile) return
        val incoming = SymbolUsageStore(File(staging, EMOJI_DIR)).also { it.load() }.recentEntries()
        val applied = SymbolUsageStore(File(filesDir, EMOJI_DIR).apply { mkdirs() }).also { it.load() }.importEntries(incoming, merge)
        if (!applied) throw IOException("emoji usage import failed")
    }
}
