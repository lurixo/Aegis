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
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.UserDataDatabase
import com.aegis.ime.user.UserDataMigration
import com.aegis.ime.user.UserDictEdit
import com.aegis.ime.user.UserDictHot
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object BackupManager {

    private const val STAGING_DIR = "backup_staging"
    private const val EXPORT_DIR = "backup_export"
    private const val VERIFY_DIR = "backup_verify"
    private const val PREFERENCE_DIR = "preferences"

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
    private val DATABASE_BACKED_PREFERENCE_KEYS = setOf("custom_symbols", "custom_operators")
    private val EXCLUDED_PREFERENCE_KEYS = DOWNLOAD_STATE_KEYS + DATABASE_BACKED_PREFERENCE_KEYS

    enum class Mode { OVERWRITE, MERGE }

    fun export(filesDir: File, prefs: SharedPreferences, password: CharArray, rawOut: OutputStream) {
        UserDictEdit.flushBeforeExport()
        LiveUserData.flushBeforeExport()
        val exportDir = prepareDirectory(File(filesDir, EXPORT_DIR))
        try {
            val snapshot = File(exportDir, UserDataDatabase.DATABASE_NAME)
            UserDataMigration.open(filesDir, prefs).use { database -> database.exportSnapshot(snapshot) }
            BackupCrypto.writeEncrypted(rawOut, password) { cipherOut ->
                GZIPOutputStream(cipherOut).use { gzip ->
                    val writer = BackupArchive.Writer(DataOutputStream(gzip))
                    writer.writeRecord("database", BackupArchive.KIND_DATABASE) { record ->
                        snapshot.inputStream().use { input -> input.copyTo(record, 64 * 1024) }
                    }
                    val preferences = prefs.all.entries
                        .filter { (key, value) -> key !in EXCLUDED_PREFERENCE_KEYS && PrefsCodec.supported(value) }
                        .sortedBy { it.key }
                    for ((index, entry) in preferences.withIndex()) {
                        val name = "preference/${index.toString().padStart(8, '0')}"
                        writer.writeRecord(name, BackupArchive.KIND_PREFERENCE) { record ->
                            val output = DataOutputStream(record)
                            PrefsCodec.writeEntry(output, entry.key, requireNotNull(entry.value))
                            output.flush()
                        }
                    }
                    writer.finish()
                    gzip.finish()
                }
            }
        } finally {
            exportDir.deleteRecursively()
        }
    }

    fun verify(filesDir: File, password: CharArray, rawIn: InputStream) {
        val verifyDir = File(filesDir, VERIFY_DIR)
        try {
            readToStaging(rawIn, password, verifyDir)
        } finally {
            verifyDir.deleteRecursively()
        }
    }

    fun restore(
        filesDir: File,
        prefs: SharedPreferences,
        password: CharArray,
        rawIn: InputStream,
        mode: Mode,
    ): Mode {
        val staging = File(filesDir, STAGING_DIR)
        LiveUserData.restoreInProgress = true
        var handedOff = false
        try {
            try {
                UserDictHot.host?.flush()
                LiveUserData.flushBeforeRestore()
            } catch (failure: Exception) {
                throw BackupException(BackupError.IO_ERROR, failure)
            }
            val staged = readToStaging(rawIn, password, staging)
            try {
                commit(filesDir, prefs, staged, mode)
            } catch (failure: Exception) {
                throw BackupException(BackupError.IO_ERROR, failure)
            }
            val reload = LiveUserData.onRestored
            if (reload != null) {
                handedOff = true
                reload()
            }
            return mode
        } finally {
            staging.deleteRecursively()
            if (!handedOff) LiveUserData.restoreInProgress = false
        }
    }

    private data class StagedBackup(
        val database: File,
        val preferences: List<File>,
    )

    private fun readToStaging(rawIn: InputStream, password: CharArray, staging: File): StagedBackup {
        val directory = try {
            prepareDirectory(staging)
        } catch (failure: Exception) {
            throw BackupException(BackupError.IO_ERROR, failure)
        }
        val databaseFile = File(directory, UserDataDatabase.DATABASE_NAME)
        val preferenceFiles = ArrayList<File>()
        try {
            BackupCrypto.readDecrypted(sourceInput(rawIn), password) { plainIn ->
                GZIPInputStream(plainIn).use { gzip ->
                    BackupArchive.read(
                        DataInputStream(gzip),
                        object : BackupArchive.Visitor {
                            override fun openRecord(name: String, kind: Int): OutputStream {
                                val destination = when (kind) {
                                    BackupArchive.KIND_DATABASE -> databaseFile
                                    BackupArchive.KIND_PREFERENCE -> File(
                                        File(directory, PREFERENCE_DIR),
                                        name.substringAfter('/'),
                                    ).also(preferenceFiles::add)
                                    else -> throw BackupCorruptException("unknown record kind")
                                }
                                return stagingOutput(destination)
                            }
                        },
                    )
                }
            }
        } catch (failure: BackupException) {
            throw failure
        } catch (failure: StagingFailure) {
            throw BackupException(BackupError.IO_ERROR, failure.cause ?: failure)
        } catch (failure: SourceFailure) {
            throw BackupException(BackupError.IO_ERROR, failure.cause ?: failure)
        } catch (failure: Exception) {
            throw BackupException(BackupError.WRONG_PASSWORD_OR_CORRUPT, failure)
        }
        if (!UserDataDatabase.validateRestoreSource(databaseFile)) {
            throw BackupException(BackupError.WRONG_PASSWORD_OR_CORRUPT)
        }
        val keys = HashSet<String>()
        try {
            for (file in preferenceFiles.sortedBy { it.name }) {
                val key = file.inputStream().use { PrefsCodec.readEntry(DataInputStream(it)).first }
                if (!keys.add(key)) throw BackupCorruptException("duplicate preference key")
            }
        } catch (failure: BackupCorruptException) {
            throw BackupException(BackupError.WRONG_PASSWORD_OR_CORRUPT, failure)
        } catch (failure: EOFException) {
            throw BackupException(BackupError.WRONG_PASSWORD_OR_CORRUPT, failure)
        } catch (failure: IOException) {
            throw BackupException(BackupError.IO_ERROR, failure)
        }
        return StagedBackup(databaseFile, preferenceFiles.sortedBy { it.name })
    }

    private fun commit(filesDir: File, prefs: SharedPreferences, staged: StagedBackup, mode: Mode) {
        val merge = mode == Mode.MERGE
        val rollbackPreferences = applyPreferences(prefs, staged.preferences, merge)
        try {
            UserDataMigration.open(filesDir, prefs).use { database ->
                database.restoreFrom(staged.database, merge)
            }
        } catch (failure: Exception) {
            try {
                rollbackPreferences()
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    private fun applyPreferences(
        prefs: SharedPreferences,
        files: List<File>,
        merge: Boolean,
    ): () -> Unit {
        val changes = LinkedHashMap<String, PrefsCodec.Value>()
        for (file in files) {
            val (key, value) = file.inputStream().use { PrefsCodec.readEntry(DataInputStream(it)) }
            if (key in EXCLUDED_PREFERENCE_KEYS || merge && prefs.contains(key)) continue
            changes[key] = value
        }
        val original = LinkedHashMap<String, Any?>()
        val absent = HashSet<String>()
        val current = prefs.all
        for (key in changes.keys) {
            if (prefs.contains(key)) original[key] = copyPreferenceValue(current[key]) else absent.add(key)
        }
        val editor = prefs.edit()
        for ((key, value) in changes) putPreference(editor, key, value)
        val rollback = {
            val rollbackEditor = prefs.edit()
            for (key in absent) rollbackEditor.remove(key)
            for ((key, value) in original) putRawPreference(rollbackEditor, key, value)
            if (!rollbackEditor.commit()) throw IOException("preferences rollback failed")
        }
        if (!editor.commit()) {
            val failure = IOException("preferences restore failed")
            runCatching(rollback).onFailure(failure::addSuppressed)
            throw failure
        }
        return rollback
    }

    private fun putPreference(editor: SharedPreferences.Editor, key: String, value: PrefsCodec.Value) {
        when (value) {
            is PrefsCodec.Value.Bool -> editor.putBoolean(key, value.v)
            is PrefsCodec.Value.Integer -> editor.putInt(key, value.v)
            is PrefsCodec.Value.LongVal -> editor.putLong(key, value.v)
            is PrefsCodec.Value.FloatVal -> editor.putFloat(key, value.v)
            is PrefsCodec.Value.Str -> editor.putString(key, value.v)
            is PrefsCodec.Value.StrSet -> editor.putStringSet(key, value.v)
        }
    }

    private fun putRawPreference(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            null -> editor.remove(key)
        }
    }

    private fun copyPreferenceValue(value: Any?): Any? =
        if (value is Set<*>) value.filterIsInstance<String>().toSet() else value

    private fun prepareDirectory(directory: File): File {
        if (directory.exists() && !directory.deleteRecursively()) throw IOException("temporary directory cleanup failed")
        if (!directory.mkdirs()) throw IOException("temporary directory creation failed")
        return directory
    }

    private fun stagingOutput(destination: File): OutputStream {
        try {
            destination.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) throw IOException("staging directory creation failed")
            }
            return object : FilterOutputStream(destination.outputStream()) {
                override fun write(value: Int) {
                    try {
                        out.write(value)
                    } catch (failure: IOException) {
                        throw StagingFailure(failure)
                    }
                }

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    try {
                        out.write(bytes, offset, length)
                    } catch (failure: IOException) {
                        throw StagingFailure(failure)
                    }
                }

                override fun close() {
                    try {
                        super.close()
                    } catch (failure: IOException) {
                        throw StagingFailure(failure)
                    }
                }
            }
        } catch (failure: IOException) {
            throw StagingFailure(failure)
        }
    }

    private fun sourceInput(source: InputStream): InputStream = object : FilterInputStream(source) {
        override fun read(): Int = try {
            super.read()
        } catch (failure: IOException) {
            throw SourceFailure(failure)
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = try {
            super.read(bytes, offset, length)
        } catch (failure: IOException) {
            throw SourceFailure(failure)
        }
    }

    private class StagingFailure(cause: IOException) : IOException(cause)
    private class SourceFailure(cause: IOException) : IOException(cause)
}
