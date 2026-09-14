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
import com.aegis.ime.user.AtomicFileSwap
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.ClipEntry
import com.aegis.ime.user.ClipboardImages
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.SymbolUsageStore
import com.aegis.ime.user.UserDictEdit
import com.aegis.ime.user.UserDictExport
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.UserDictImport
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserLexicon
import com.aegis.ime.user.UserLexiconTransfer
import com.aegis.ime.user.UserModel
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object BackupManager {


    private val USERDB = BackupItem.DICTIONARY.relativePath
    private val USERLEARN = BackupItem.LEARNING.relativePath
    private val PHRASES = BackupItem.PHRASES.relativePath
    private val CLIPBOARD = BackupItem.CLIPBOARD.relativePath
    private const val CLIPS_DIR = "clips"
    private const val BIG_CLIP_LINE = "B\t"
    private val SYMBOL_USAGE = BackupItem.SYMBOL_USAGE.relativePath
    private const val EMOJI_DIR = "emoji"
    private val EMOJI_USAGE = BackupItem.EMOJI_USAGE.relativePath
    private const val STAGING_DIR = "backup_staging"
    private const val TMP_TAG = 0L

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

    private val restoring = AtomicBoolean(false)

    enum class Mode { OVERWRITE, MERGE }

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


    fun restore(
        filesDir: File,
        prefs: SharedPreferences,
        password: CharArray,
        rawIn: InputStream,
        mode: Mode,
    ): Mode {
        return restoring {
            restoreOnce(filesDir, prefs, mode) { visitor ->
                BackupCrypto.readDecrypted(rawIn, password) { plainIn ->
                    GZIPInputStream(plainIn).use { gzip ->
                        BackupArchive.read(DataInputStream(gzip), visitor)
                    }
                }
            }
        }
    }

    internal fun restoreLexicons(
        filesDir: File,
        prefs: SharedPreferences,
        data: UserLexiconTransfer.Data,
        mode: Mode,
    ) {
        restoring {
            restoreOnce(filesDir, prefs, mode, data) { visitor ->
                data.chinese?.let { chinese ->
                    visitor.openFile(USERDB).use { it.write(chinese.userdb.toByteArray()) }
                    chinese.userlearn?.let { text ->
                        visitor.openFile(USERLEARN).use { it.write(text.toByteArray()) }
                    }
                }
                val settings = LinkedHashMap<String, Any>()
                data.english?.let { settings[UserLexicon.PREF_ENGLISH_WORDS] = it }
                data.email?.let { email ->
                    settings[UserLexicon.PREF_EMAIL_DOMAINS] = email.domains
                    settings[UserLexicon.PREF_DISABLED_EMAIL_DOMAINS] = email.disabledDefaults
                    for ((domain, count) in email.counts) settings[UserLexicon.EMAIL_COUNT_PREFIX + domain] = count
                }
                if (settings.isNotEmpty()) visitor.onPrefs(PrefsCodec.encode(settings))
            }
        }
    }

    private fun <T> restoring(work: () -> T): T {
        if (!restoring.compareAndSet(false, true)) throw BackupException(BackupError.ALREADY_RESTORING)
        return try {
            work()
        } finally {
            restoring.set(false)
        }
    }

    private fun restoreOnce(
        filesDir: File,
        prefs: SharedPreferences,
        mode: Mode,
        lexicons: UserLexiconTransfer.Data? = null,
        read: (StagingVisitor) -> Unit,
    ): Mode {
        val staging = File(filesDir, STAGING_DIR)
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw BackupException(BackupError.IO_ERROR)
        try {
            RestoreJournal.finishAnyInterrupted(filesDir, prefs)
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw BackupException(BackupError.IO_ERROR, e)
        }
        LiveUserData.restoreInProgress = true
        var handedOff = false
        try {
            try {
                val live = UserDictHot.host
                if (lexicons == null) {
                    if (live != null && !live.flushDictionary() && live.dictionaryReadable()) {
                        throw IOException("user dictionary flush failed")
                    }
                } else if (lexicons.chinese != null && live != null && !live.flushForRestore() &&
                    (live.dictionaryReadable() || live.learnedReadable())) {
                    throw IOException("Chinese lexicons could not be flushed")
                }
                LiveUserData.flushBeforeRestore()
            } catch (e: Exception) {
                throw BackupException(BackupError.IO_ERROR, e)
            }

            val visitor = StagingVisitor(staging)
            try {
                read(visitor)
            } catch (e: BackupException) {
                throw e
            } catch (e: Exception) {
                throw BackupException(BackupError.WRONG_PASSWORD_OR_CORRUPT, e)
            }

            val damaged = StoreHealth.unreadableIn(staging, liveStores = false)
            if (damaged.isNotEmpty()) throw BackupException(BackupError.DAMAGED_CONTENT, items = damaged)

            val journal = try {
                RestoreJournal.open(filesDir, prefs)
            } catch (e: Exception) {
                throw BackupException(BackupError.IO_ERROR, e)
            }
            try {
                commit(filesDir, prefs, staging, visitor.prefsBlob, mode, lexicons)
                journal.markDone()
            } catch (e: Exception) {
                val takenBack = runCatching { journal.rollBack(prefs) }.isSuccess
                if (takenBack) handedOff = reloadLiveStores(lexicons)
                throw when {
                    !takenBack -> BackupException(BackupError.ROLLBACK_FAILED, e)
                    e is BackupCorruptException -> BackupException(BackupError.WRONG_PASSWORD_OR_CORRUPT, e)
                    else -> BackupException(BackupError.IO_ERROR, e)
                }
            }
            journal.discard()
            LiveUserData.restoreTrouble = null

            val reload = restoredCallback(lexicons)
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

    private fun restoredCallback(lexicons: UserLexiconTransfer.Data?): (() -> Unit)? = when {
        lexicons == null -> LiveUserData.onRestored
        lexicons.chinese != null -> LiveUserData.onLexiconsRestored
        else -> null
    }

    private fun reloadLiveStores(lexicons: UserLexiconTransfer.Data?): Boolean {
        if (lexicons == null || lexicons.chinese != null) {
            UserDictHot.host?.let { host -> runCatching { host.reloadDictionary() } }
        }
        val reload = restoredCallback(lexicons) ?: return false
        return runCatching { reload() }.isSuccess
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
        lexicons: UserLexiconTransfer.Data?,
    ) {
        val merge = mode == Mode.MERGE
        applyUserDb(filesDir, staging, merge, allowEmpty = lexicons?.legacy != true)
        applyUserLearning(filesDir, staging, merge, mergeRecords = lexicons != null && !lexicons.legacy)
        applyPhrases(filesDir, staging, merge)
        applyClipboard(filesDir, staging, merge)
        applySymbolUsage(filesDir, staging, merge)
        applyEmojiUsage(filesDir, staging, merge)
        applyPrefs(prefs, prefsBlob, merge)
    }

    private fun applyPrefs(prefs: SharedPreferences, blob: ByteArray?, merge: Boolean) {
        if (blob == null) return
        val decoded = PrefsCodec.decode(blob).filterKeys { it !in DOWNLOAD_STATE_KEYS }
        val editor = prefs.edit()
        if (!merge && (decoded.containsKey(UserLexicon.PREF_EMAIL_DOMAINS) ||
                decoded.containsKey(UserLexicon.PREF_DISABLED_EMAIL_DOMAINS))) {
            for (key in prefs.all.keys) {
                if (key.startsWith(UserLexicon.EMAIL_COUNT_PREFIX)) editor.remove(key)
            }
        }
        for ((key, value) in decoded) {
            if (merge && value is PrefsCodec.Value.StrSet &&
                (key == UserLexicon.PREF_ENGLISH_WORDS ||
                    key == UserLexicon.PREF_EMAIL_DOMAINS ||
                    key == UserLexicon.PREF_DISABLED_EMAIL_DOMAINS)) {
                val existing = runCatching { prefs.getStringSet(key, emptySet()).orEmpty() }.getOrDefault(emptySet())
                editor.putStringSet(key, existing + value.v)
                continue
            }
            if (merge && prefs.contains(key)) continue
            PrefsCodec.put(editor, key, value)
        }
        val emailTouched = decoded.keys.any {
            it == UserLexicon.PREF_EMAIL_DOMAINS || it == UserLexicon.PREF_DISABLED_EMAIL_DOMAINS ||
                it.startsWith(UserLexicon.EMAIL_COUNT_PREFIX)
        }
        if (emailTouched) {
            val disabledKey = UserLexicon.PREF_DISABLED_EMAIL_DOMAINS
            val existingDisabled = runCatching { prefs.getStringSet(disabledKey, emptySet()).orEmpty() }.getOrDefault(emptySet())
            val importedDisabled = (decoded[disabledKey] as? PrefsCodec.Value.StrSet)?.v.orEmpty()
            val disabled = when {
                merge -> existingDisabled + importedDisabled
                decoded.containsKey(disabledKey) -> importedDisabled
                else -> existingDisabled
            }
            for (raw in disabled) {
                val domain = UserLexicon.normalize(UserLexicon.Kind.EMAIL, raw)
                if (domain != null && domain in UserLexicon.COMMON_EMAIL_DOMAINS) {
                    editor.remove(UserLexicon.EMAIL_COUNT_PREFIX + domain)
                }
            }
        }
        if (!editor.commit()) throw IOException("preferences restore failed")
    }

    private fun applyUserDb(filesDir: File, staging: File, merge: Boolean, allowEmpty: Boolean) {
        val staged = File(staging, USERDB)
        if (!staged.isFile) return
        if (allowEmpty && UserModel().apply { load(staged, sweepStale = false) }.isEmpty()) {
            if (merge) return
            val target = File(filesDir, USERDB)
            val incoming = UserModel().apply { replaceWordsFrom(staged) }
            val owed = runCatching {
                UserModel().apply { load(target, sweepStale = false) }.tombstones()
            }.getOrDefault(emptyList())
            for ((word, reading) in owed) incoming.addTombstone(word, reading)
            incoming.save(target)
            if (UserDictHot.host?.reloadDictionary() == false) throw IOException("user dictionary reload failed")
            return
        }
        val now = System.currentTimeMillis()
        val host = UserDictHot.host
        val applied = if (host != null) {
            host.importUserDict(staged, merge, now)
        } else {
            UserDictImport.apply(staged, File(filesDir, USERDB), merge, now)
        }
        if (!applied) throw IOException("user dictionary import failed")
    }

    private fun applyUserLearning(filesDir: File, staging: File, merge: Boolean, mergeRecords: Boolean) {
        val staged = File(staging, USERLEARN)
        if (!staged.isFile) return
        val target = File(filesDir, USERLEARN)
        if (merge && target.isFile) {
            if (!mergeRecords) return
            val local = target.readText().ifEmpty { "aegis-userlearn 1\n" }
            val incoming = staged.readText()
            UserLearning.validateText(local)
            val rows = LinkedHashMap<List<String>, String>()
            for (text in listOf(local, incoming)) {
                for (row in text.lineSequence().drop(1).filter { it.isNotEmpty() }) {
                    rows.putIfAbsent(row.split('\t').take(3), row)
                }
            }
            val combined = buildString {
                append("aegis-userlearn 1\n")
                for (row in rows.values) append(row).append('\n')
            }
            UserLearning.validateText(combined)
            AtomicFileSwap.write(target, TMP_TAG, combined)
        } else {
            AtomicFileSwap.copy(staged, target, TMP_TAG)
        }
    }

    private fun applyPhrases(filesDir: File, staging: File, merge: Boolean) {
        val staged = File(staging, PHRASES)
        if (!staged.isFile) return
        val raw = staged.readText()
        val text = if (raw.lineSequence().any { it.startsWith("C\t") }) {
            raw
        } else {
            val migrated = ClipboardStore(staging).also { it.load() }
            val carried = try {
                migrated.exportPhrasesText().takeIf { migrated.phrases().isNotEmpty() }
            } finally {
                migrated.stopSaving()
            }
            carried ?: return
        }
        LiveUserData.withClipboardStore(filesDir) { it.importPhrasesText(text, merge) }
    }

    private fun applyClipboard(filesDir: File, staging: File, merge: Boolean) {
        val stagedIndex = File(staging, CLIPBOARD)
        if (!stagedIndex.isFile) return
        val staged = ClipboardStore(staging).also { it.load() }
        val incoming = try {
            staged.history()
        } finally {
            staged.stopSaving()
        }
        LiveUserData.withClipboardStore(filesDir) { it.importHistory(incoming, merge) }
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
