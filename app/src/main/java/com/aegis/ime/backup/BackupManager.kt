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
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.StoredSettingValue
import com.aegis.ime.user.UserDataDatabase
import com.aegis.ime.user.UserDataMigration
import com.aegis.ime.user.UserDataRestoreStage
import com.aegis.ime.user.UserDictEdit
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.UserSettingsPreferences
import com.aegis.ime.user.UserSettingsSchema
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
    ): Mode = restoreInternal(filesDir, prefs, password, rawIn, mode, null)

    internal fun restoreForTest(
        filesDir: File,
        prefs: SharedPreferences,
        password: CharArray,
        rawIn: InputStream,
        mode: Mode,
        stage: (UserDataRestoreStage) -> Unit,
    ): Mode = restoreInternal(filesDir, prefs, password, rawIn, mode, stage)

    private fun restoreInternal(
        filesDir: File,
        prefs: SharedPreferences,
        password: CharArray,
        rawIn: InputStream,
        mode: Mode,
        stage: ((UserDataRestoreStage) -> Unit)?,
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
                commit(filesDir, prefs, staged, mode, stage)
                UserSettingsPreferences.invalidateCache(filesDir)
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

    private data class StagedBackup(val database: File)

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
        if (!UserDataDatabase.validateRestoreSourceForUpgrade(databaseFile)) {
            throw BackupException(BackupError.WRONG_PASSWORD_OR_CORRUPT)
        }
        val keys = HashSet<String>()
        val legacySettings = LinkedHashMap<String, StoredSettingValue>()
        try {
            for (file in preferenceFiles.sortedBy { it.name }) {
                val (key, value) = file.inputStream().use { PrefsCodec.readEntry(DataInputStream(it)) }
                if (!keys.add(key)) throw BackupCorruptException("duplicate preference key")
                if (key !in UserSettingsSchema.specialStorageKeys) legacySettings[key] = storedSetting(value)
            }
            UserDataDatabase.open(directory).use { database ->
                if (database.metadata(UserDataDatabase.SETTINGS_MIGRATION_KEY) == null && database.settingCount() != 0L) {
                    throw BackupCorruptException("unmarked settings table is not empty")
                }
                val migratedSettings = LinkedHashMap(UserSettingsSchema.defaults).apply { putAll(legacySettings) }
                database.migrateLegacySettings(
                    migratedSettings,
                    legacySettings.size,
                    UserSettingsSchema.digest(legacySettings),
                )
            }
        } catch (failure: BackupCorruptException) {
            throw BackupException(BackupError.WRONG_PASSWORD_OR_CORRUPT, failure)
        } catch (failure: EOFException) {
            throw BackupException(BackupError.WRONG_PASSWORD_OR_CORRUPT, failure)
        } catch (failure: IOException) {
            throw BackupException(BackupError.IO_ERROR, failure)
        } catch (failure: SQLiteFullException) {
            throw BackupException(BackupError.IO_ERROR, failure)
        } catch (failure: SQLiteDiskIOException) {
            throw BackupException(BackupError.IO_ERROR, failure)
        } catch (failure: SQLiteCantOpenDatabaseException) {
            throw BackupException(BackupError.IO_ERROR, failure)
        } catch (failure: Exception) {
            throw BackupException(BackupError.WRONG_PASSWORD_OR_CORRUPT, failure)
        }
        if (!UserDataDatabase.validateRestoreSource(databaseFile)) {
            throw BackupException(BackupError.WRONG_PASSWORD_OR_CORRUPT)
        }
        return StagedBackup(databaseFile)
    }

    private fun commit(
        filesDir: File,
        prefs: SharedPreferences,
        staged: StagedBackup,
        mode: Mode,
        stage: ((UserDataRestoreStage) -> Unit)?,
    ) {
        val merge = mode == Mode.MERGE
        UserDataMigration.open(filesDir, prefs).use { database ->
            database.restoreFrom(staged.database, merge, stage)
        }
    }

    private fun storedSetting(value: PrefsCodec.Value): StoredSettingValue = when (value) {
        is PrefsCodec.Value.Bool -> StoredSettingValue.Bool(value.v)
        is PrefsCodec.Value.Integer -> StoredSettingValue.Integer(value.v)
        is PrefsCodec.Value.LongVal -> StoredSettingValue.LongValue(value.v)
        is PrefsCodec.Value.FloatVal -> StoredSettingValue.FloatValue(value.v)
        is PrefsCodec.Value.Str -> StoredSettingValue.StringValue(value.v)
        is PrefsCodec.Value.StrSet -> StoredSettingValue.StringSetValue(LinkedHashSet(value.v))
    }

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
