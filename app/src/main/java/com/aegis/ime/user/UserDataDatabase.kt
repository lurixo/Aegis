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

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class StoredWord(val count: Int, val lastUsed: Long)

internal data class StoredUserWordEntry(
    val reading: String,
    val word: String,
    val count: Int,
    val lastUsed: Long,
)

internal data class UserDataSnapshot(
    val words: Map<String, StoredWord>,
    val bigrams: Map<String, Map<String, Int>>,
    val readings: Map<String, Set<String>>,
)

internal data class StoredUsage(val count: Double, val lastSeen: Long)

internal data class StoredLearningEntry(
    val reading: String,
    val word: String,
    val usage: StoredUsage,
)

internal data class UserLearningSnapshot(
    val formed: Map<String, Map<String, StoredUsage>>,
    val pending: Map<Pair<String, String>, StoredUsage>,
    val follows: Map<String, Map<String, StoredUsage>>,
)

internal data class StoredPhrase(val text: String, val note: String)

internal data class StoredPhraseCategory(
    val name: String,
    val phrases: List<StoredPhrase>,
)

internal data class ClipboardDataSnapshot(
    val history: List<String>,
    val categories: List<StoredPhraseCategory>,
)

internal data class StoredRecentItem(val value: String, val origin: String?)

internal sealed class StoredSettingValue {
    data class Bool(val value: Boolean) : StoredSettingValue()
    data class Integer(val value: Int) : StoredSettingValue()
    data class LongValue(val value: Long) : StoredSettingValue()
    data class FloatValue(val value: Float) : StoredSettingValue()
    data class StringValue(val value: String) : StoredSettingValue()
    data class StringSetValue(val value: Set<String>) : StoredSettingValue()
}

internal enum class UserDataRecoveryKind {
    EXISTING,
    LAST_GOOD,
    EMPTY,
}

internal data class UserDataRecoveryReport(
    val kind: UserDataRecoveryKind,
    val detail: String,
)

internal enum class UserDataTransferStage {
    AFTER_VALIDATION,
    BEFORE_DATABASE_COMMIT,
    AFTER_DATABASE_COMMIT,
}

internal enum class UserDataRestoreStage {
    BEFORE_DATABASE_COMMIT,
    AFTER_DATABASE_COMMIT,
    AFTER_CHECKPOINT,
}

internal class UserDataDatabase private constructor(
    private val root: File,
    private val database: SQLiteDatabase,
    val recoveryReport: UserDataRecoveryReport,
) : Closeable {

    private val databaseFile = File(root, DATABASE_NAME)
    private val lastGoodFile = File(root, LAST_GOOD_NAME)
    private val statusFile = File(root, STATUS_NAME)

    @Volatile
    var lastFailure: String? = null
        private set

    init {
        database.execSQL("PRAGMA foreign_keys=ON")
        database.enableWriteAheadLogging()
        database.execSQL("PRAGMA synchronous=FULL")
        createSchema(database)
        writeRecoveryStatus(recoveryReport)
    }

    @Synchronized
    fun isEmpty(): Boolean = scalarLong("SELECT COUNT(*) FROM user_words") == 0L &&
        scalarLong("SELECT COUNT(*) FROM learned_formed") == 0L &&
        scalarLong("SELECT COUNT(*) FROM learned_pending") == 0L &&
        scalarLong("SELECT COUNT(*) FROM learned_follows") == 0L &&
        scalarLong("SELECT COUNT(*) FROM clipboard_history") == 0L &&
        scalarLong("SELECT COUNT(*) FROM phrase_categories") == 0L &&
        scalarLong("SELECT COUNT(*) FROM custom_items") == 0L &&
        scalarLong("SELECT COUNT(*) FROM recent_items") == 0L

    @Synchronized
    fun metadata(key: String): String? = database.rawQuery(
        "SELECT value FROM metadata WHERE key=?",
        arrayOf(key),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    @Synchronized
    fun dataVersion(): Long = metadataInTransaction(DATA_VERSION_KEY)?.toLongOrNull() ?: 0L

    @Synchronized
    fun putMetadata(key: String, value: String) {
        transaction {
            val values = ContentValues().apply {
                put("key", key)
                put("value", value)
            }
            database.insertWithOnConflict("metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    @Synchronized
    fun settingCount(): Long = scalarLong("SELECT COUNT(*) FROM user_settings")

    @Synchronized
    fun readSetting(key: String): StoredSettingValue? = readSettingInTransaction(key)

    @Synchronized
    fun readSettings(): Map<String, StoredSettingValue> {
        val out = LinkedHashMap<String, StoredSettingValue>()
        database.rawQuery("SELECT key FROM user_settings ORDER BY key", null).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0)
                out[key] = checkNotNull(readSettingInTransaction(key))
            }
        }
        return out
    }

    @Synchronized
    fun updateSettings(
        values: Map<String, StoredSettingValue>,
        removals: Set<String> = emptySet(),
        clear: Boolean = false,
    ) {
        transaction {
            if (clear) database.delete("user_settings", null, null)
            for (key in removals) database.delete("user_settings", "key=?", arrayOf(key))
            for ((key, value) in values) writeSettingInTransaction(key, value)
            for ((key, value) in values) {
                if (readSettingInTransaction(key) != value) throw IOException("setting verification failed: $key")
            }
            putMetadataInTransaction(SETTINGS_CHECKPOINT_PENDING_KEY, "1")
        }
    }

    @Synchronized
    fun markSettingsCheckpointed() {
        putMetadata(SETTINGS_CHECKPOINT_PENDING_KEY, "0")
    }

    @Synchronized
    fun replaceSettings(values: Map<String, StoredSettingValue>) {
        updateSettings(values, clear = true)
    }

    @Synchronized
    fun migrateLegacySettings(
        values: Map<String, StoredSettingValue>,
        sourceCount: Int,
        sourceDigest: String,
        stage: ((SettingsMigrationStage) -> Unit)? = null,
    ): Boolean {
        if (metadata(SETTINGS_MIGRATION_KEY) != null) {
            val missingDefaults = UserSettingsSchema.defaults.filterKeys { readSetting(it) == null }
            if (missingDefaults.isNotEmpty()) {
                updateSettings(missingDefaults)
                checkpointLastGood()
                markSettingsCheckpointed()
            } else if (metadata(SETTINGS_CHECKPOINT_PENDING_KEY) == "1") {
                checkpointLastGood()
                markSettingsCheckpointed()
            }
            stage?.invoke(SettingsMigrationStage.AFTER_DATABASE_COMMIT)
            return false
        }
        var migrated = false
        transaction {
            if (metadataInTransaction(SETTINGS_MIGRATION_KEY) != null) return@transaction
            if (scalarLong("SELECT COUNT(*) FROM user_settings") != 0L) {
                throw IOException("unmarked settings table is not empty")
            }
            for ((key, value) in values) writeSettingInTransaction(key, value)
            if (scalarLong("SELECT COUNT(*) FROM user_settings") != values.size.toLong()) {
                throw IOException("settings migration record count mismatch")
            }
            for ((key, value) in values) {
                if (readSettingInTransaction(key) != value) {
                    throw IOException("settings migration readback failed: $key")
                }
            }
            putMetadataInTransaction(SETTINGS_MIGRATION_SOURCE_COUNT_KEY, sourceCount.toString())
            putMetadataInTransaction(SETTINGS_MIGRATION_SOURCE_DIGEST_KEY, sourceDigest)
            putMetadataInTransaction(SETTINGS_MIGRATION_RECORD_COUNT_KEY, values.size.toString())
            putMetadataInTransaction(SETTINGS_CHECKPOINT_PENDING_KEY, "1")
            putMetadataInTransaction(SETTINGS_MIGRATION_KEY, "complete")
            stage?.invoke(SettingsMigrationStage.BEFORE_DATABASE_COMMIT)
            migrated = true
        }
        if (migrated) {
            checkpointLastGood()
            markSettingsCheckpointed()
        }
        stage?.invoke(SettingsMigrationStage.AFTER_DATABASE_COMMIT)
        return migrated
    }

    @Synchronized
    fun readUserData(): UserDataSnapshot {
        val words = LinkedHashMap<String, StoredWord>()
        database.rawQuery("SELECT word, count, last_used FROM user_words", null).use { cursor ->
            while (cursor.moveToNext()) words[cursor.getString(0)] = StoredWord(cursor.getInt(1), cursor.getLong(2))
        }
        val bigrams = LinkedHashMap<String, MutableMap<String, Int>>()
        database.rawQuery("SELECT prev_word, word, count FROM user_bigrams", null).use { cursor ->
            while (cursor.moveToNext()) {
                bigrams.getOrPut(cursor.getString(0)) { LinkedHashMap() }[cursor.getString(1)] = cursor.getInt(2)
            }
        }
        val readings = LinkedHashMap<String, MutableSet<String>>()
        database.rawQuery("SELECT reading, word FROM user_readings", null).use { cursor ->
            while (cursor.moveToNext()) readings.getOrPut(cursor.getString(0)) { LinkedHashSet() }.add(cursor.getString(1))
        }
        return UserDataSnapshot(words, bigrams, readings)
    }

    @Synchronized
    fun writeUserDictionary(output: OutputStream) {
        val writer = UserDataTransfer.writer(output)
        writer.write(UserDataTransfer.USER_DICTIONARY_HEADER)
        writer.newLine()
        database.rawQuery("SELECT word,count,last_used FROM user_words ORDER BY word", null).use { cursor ->
            while (cursor.moveToNext()) {
                writer.write("W\t")
                writer.write(cursor.getString(0))
                writer.write('\t'.code)
                writer.write(cursor.getInt(1).toString())
                writer.write('\t'.code)
                writer.write(cursor.getLong(2).toString())
                writer.newLine()
            }
        }
        database.rawQuery("SELECT prev_word,word,count FROM user_bigrams ORDER BY prev_word,word", null).use { cursor ->
            while (cursor.moveToNext()) {
                writer.write("B\t")
                writer.write(cursor.getString(0))
                writer.write('\t'.code)
                writer.write(cursor.getString(1))
                writer.write('\t'.code)
                writer.write(cursor.getInt(2).toString())
                writer.newLine()
            }
        }
        database.rawQuery("SELECT reading,word FROM user_readings ORDER BY reading,word", null).use { cursor ->
            while (cursor.moveToNext()) {
                writer.write("R\t")
                writer.write(cursor.getString(0))
                writer.write('\t'.code)
                writer.write(cursor.getString(1))
                writer.newLine()
            }
        }
        writer.flush()
    }

    @Synchronized
    fun importUserDictionary(
        input: InputStream,
        merge: Boolean,
        stage: ((UserDataTransferStage) -> Unit)? = null,
    ): Boolean = synchronized(checkpointLock) {
        val rollback = File(root, "$DATABASE_NAME.before-user-dictionary-import")
        try {
            exportSnapshot(rollback)
            var imported = false
            transaction {
                database.execSQL("DROP TABLE IF EXISTS temp.stage_user_words")
                database.execSQL("DROP TABLE IF EXISTS temp.stage_user_readings")
                database.execSQL("DROP TABLE IF EXISTS temp.stage_user_bigrams")
                database.execSQL(
                    "CREATE TEMP TABLE stage_user_words (word TEXT PRIMARY KEY, count INTEGER NOT NULL " +
                        "CHECK(count > 0), last_used INTEGER NOT NULL CHECK(last_used >= 0))",
                )
                database.execSQL(
                    "CREATE TEMP TABLE stage_user_readings (reading TEXT NOT NULL, word TEXT NOT NULL, " +
                        "PRIMARY KEY(reading,word))",
                )
                database.execSQL(
                    "CREATE TEMP TABLE stage_user_bigrams (prev_word TEXT NOT NULL, word TEXT NOT NULL, " +
                        "count INTEGER NOT NULL CHECK(count > 0), PRIMARY KEY(prev_word,word))",
                )
                UserDataTransfer.readUserDictionary(input) { row ->
                    when (row) {
                        is UserDataTransfer.UserDictionaryRow.Word -> {
                            val values = ContentValues().apply {
                                put("word", row.word)
                                put("count", row.count)
                                put("last_used", row.lastUsed)
                            }
                            database.insertOrThrow("stage_user_words", null, values)
                        }
                        is UserDataTransfer.UserDictionaryRow.Bigram -> {
                            val values = ContentValues().apply {
                                put("prev_word", row.previous)
                                put("word", row.word)
                                put("count", row.count)
                            }
                            database.insertOrThrow("stage_user_bigrams", null, values)
                        }
                        is UserDataTransfer.UserDictionaryRow.Reading -> {
                            val values = ContentValues().apply {
                                put("reading", row.reading)
                                put("word", row.word)
                            }
                            database.insertOrThrow("stage_user_readings", null, values)
                        }
                    }
                }
                if (scalarLong("SELECT COUNT(*) FROM stage_user_words") == 0L) {
                    throw IOException("user dictionary contains no words")
                }
                val missingReadingWord = database.rawQuery(
                    "SELECT 1 FROM stage_user_readings reading LEFT JOIN stage_user_words word " +
                        "ON word.word=reading.word WHERE word.word IS NULL LIMIT 1",
                    null,
                ).use { it.moveToFirst() }
                val missingBigramWord = database.rawQuery(
                    "SELECT 1 FROM stage_user_bigrams relation LEFT JOIN stage_user_words word " +
                        "ON word.word=relation.word WHERE word.word IS NULL LIMIT 1",
                    null,
                ).use { it.moveToFirst() }
                if (missingReadingWord || missingBigramWord) {
                    throw IOException("user dictionary relation target is missing")
                }
                stage?.invoke(UserDataTransferStage.AFTER_VALIDATION)
                if (merge) mergeStagedUserDictionary() else replaceFromStagedUserDictionary()
                database.execSQL("DROP TABLE stage_user_readings")
                database.execSQL("DROP TABLE stage_user_bigrams")
                database.execSQL("DROP TABLE stage_user_words")
                stage?.invoke(UserDataTransferStage.BEFORE_DATABASE_COMMIT)
                imported = true
            }
            try {
                stage?.invoke(UserDataTransferStage.AFTER_DATABASE_COMMIT)
                checkpointLastGood()
            } catch (failure: Exception) {
                rollbackCommittedTransfer(rollback, failure)
                throw failure
            }
            imported
        } finally {
            rollback.delete()
        }
    }

    @Synchronized
    fun userWordEntryCount(query: String = ""): Long {
        val filter = userWordFilter(query)
        return database.rawQuery(
            "SELECT COUNT(*) FROM user_readings r JOIN user_words w ON w.word=r.word${filter.sql}",
            filter.args,
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
    }

    @Synchronized
    fun readUserWordEntries(
        query: String = "",
        offset: Int,
        limit: Int,
    ): List<StoredUserWordEntry> {
        require(offset >= 0)
        require(limit in 0..MAX_RUNTIME_PAGE_SIZE)
        if (limit == 0) return emptyList()
        val filter = userWordFilter(query)
        val args = ArrayList<String>((filter.args?.size ?: 0) + 2).apply {
            filter.args?.let(::addAll)
            add(limit.toString())
            add(offset.toString())
        }
        val out = ArrayList<StoredUserWordEntry>(limit)
        database.rawQuery(
            "SELECT r.reading,r.word,w.count,w.last_used FROM user_readings r " +
                "JOIN user_words w ON w.word=r.word${filter.sql} " +
                "ORDER BY w.count DESC,r.reading,r.word LIMIT ? OFFSET ?",
            args.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(
                    StoredUserWordEntry(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getInt(2),
                        cursor.getLong(3),
                    ),
                )
            }
        }
        return out
    }

    @Synchronized
    fun readUserWordsForKey(key: String, t9: Boolean, offset: Int, limit: Int): List<StoredUserWordEntry> {
        require(offset >= 0)
        require(limit in 0..MAX_RUNTIME_PAGE_SIZE)
        if (limit == 0 || key.isEmpty()) return emptyList()
        val reading = if (t9) t9Glob(key) ?: return emptyList() else key
        val operator = if (t9) "GLOB" else "="
        val out = ArrayList<StoredUserWordEntry>(limit)
        database.rawQuery(
            "SELECT r.reading,r.word,w.count,w.last_used FROM user_readings r " +
                "JOIN user_words w ON w.word=r.word WHERE r.reading $operator ? " +
                "GROUP BY r.word ORDER BY w.count DESC,w.last_used DESC,r.word LIMIT ? OFFSET ?",
            arrayOf(reading, limit.toString(), offset.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(
                    StoredUserWordEntry(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getInt(2),
                        cursor.getLong(3),
                    ),
                )
            }
        }
        return out
    }

    @Synchronized
    fun hasUserWordForKey(key: String, t9: Boolean, word: String): Boolean {
        if (key.isEmpty() || word.isEmpty()) return false
        val reading = if (t9) t9Glob(key) ?: return false else key
        val operator = if (t9) "GLOB" else "="
        return database.rawQuery(
            "SELECT 1 FROM user_readings WHERE reading $operator ? AND word=? LIMIT 1",
            arrayOf(reading, word),
        ).use { it.moveToFirst() }
    }

    @Synchronized
    fun readStoredWord(word: String): StoredWord? = database.rawQuery(
        "SELECT count,last_used FROM user_words WHERE word=?",
        arrayOf(word),
    ).use { cursor -> if (cursor.moveToFirst()) StoredWord(cursor.getInt(0), cursor.getLong(1)) else null }

    @Synchronized
    fun hasUserReading(reading: String, word: String): Boolean = database.rawQuery(
        "SELECT 1 FROM user_readings WHERE reading=? AND word=? LIMIT 1",
        arrayOf(reading, word),
    ).use { it.moveToFirst() }

    @Synchronized
    fun maximumUserWordCount(): Int = scalarLong("SELECT COALESCE(MAX(count),0) FROM user_words").toInt()

    @Synchronized
    fun userDataIsEmpty(): Boolean = scalarLong("SELECT COUNT(*) FROM user_words") == 0L &&
        scalarLong("SELECT COUNT(*) FROM user_readings") == 0L

    @Synchronized
    fun readUserSuccessors(previousWord: String, offset: Int, limit: Int): List<StoredUserWordEntry> {
        require(offset >= 0)
        require(limit in 0..MAX_RUNTIME_PAGE_SIZE)
        if (limit == 0) return emptyList()
        val out = ArrayList<StoredUserWordEntry>(limit)
        database.rawQuery(
            "SELECT '',b.word,b.count,w.last_used FROM user_bigrams b " +
                "JOIN user_words w ON w.word=b.word WHERE b.prev_word=? " +
                "ORDER BY b.count DESC,w.last_used DESC,b.word LIMIT ? OFFSET ?",
            arrayOf(previousWord, limit.toString(), offset.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(StoredUserWordEntry("", cursor.getString(1), cursor.getInt(2), cursor.getLong(3)))
            }
        }
        return out
    }

    @Synchronized
    fun replaceUserData(snapshot: UserDataSnapshot) {
        transaction {
            database.delete("user_bigrams", null, null)
            database.delete("user_readings", null, null)
            database.delete("user_words", null, null)
            insertUserData(snapshot)
        }
    }

    @Synchronized
    fun recordWord(
        word: String,
        reading: String?,
        previousWord: String?,
        now: Long,
        incrementCount: Boolean,
    ) {
        transaction {
            val current = database.rawQuery("SELECT count FROM user_words WHERE word=?", arrayOf(word)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            val next = if (incrementCount || current == 0) saturatingAdd(current, 1) else current
            val values = ContentValues().apply {
                put("word", word)
                put("count", next)
                put("last_used", now.coerceAtLeast(0L))
            }
            database.insertWithOnConflict("user_words", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            if (!reading.isNullOrEmpty()) {
                val relation = ContentValues().apply {
                    put("reading", reading)
                    put("word", word)
                }
                database.insertWithOnConflict("user_readings", null, relation, SQLiteDatabase.CONFLICT_IGNORE)
            }
            if (!previousWord.isNullOrEmpty()) {
                val previousCount = database.rawQuery(
                    "SELECT count FROM user_bigrams WHERE prev_word=? AND word=?",
                    arrayOf(previousWord, word),
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
                val relation = ContentValues().apply {
                    put("prev_word", previousWord)
                    put("word", word)
                    put("count", saturatingAdd(previousCount, 1))
                }
                database.insertWithOnConflict("user_bigrams", null, relation, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    @Synchronized
    fun removeReading(reading: String, word: String) {
        transaction {
            database.delete("user_readings", "reading=? AND word=?", arrayOf(reading, word))
            val remaining = database.rawQuery("SELECT 1 FROM user_readings WHERE word=? LIMIT 1", arrayOf(word)).use {
                it.moveToFirst()
            }
            if (!remaining) removeWordRows(word)
        }
    }

    @Synchronized
    fun removeWord(word: String) {
        transaction { removeWordRows(word) }
    }

    @Synchronized
    fun readLearning(): UserLearningSnapshot {
        val formed = LinkedHashMap<String, MutableMap<String, StoredUsage>>()
        database.rawQuery("SELECT word, reading, count, last_seen FROM learned_formed", null).use { cursor ->
            while (cursor.moveToNext()) {
                formed.getOrPut(cursor.getString(0)) { LinkedHashMap() }[cursor.getString(1)] =
                    StoredUsage(cursor.getDouble(2), cursor.getLong(3))
            }
        }
        val pending = LinkedHashMap<Pair<String, String>, StoredUsage>()
        database.rawQuery("SELECT reading, word, count, last_seen FROM learned_pending", null).use { cursor ->
            while (cursor.moveToNext()) {
                pending[cursor.getString(0) to cursor.getString(1)] = StoredUsage(cursor.getDouble(2), cursor.getLong(3))
            }
        }
        val follows = LinkedHashMap<String, MutableMap<String, StoredUsage>>()
        database.rawQuery("SELECT prev_word, word, count, last_seen FROM learned_follows", null).use { cursor ->
            while (cursor.moveToNext()) {
                follows.getOrPut(cursor.getString(0)) { LinkedHashMap() }[cursor.getString(1)] =
                    StoredUsage(cursor.getDouble(2), cursor.getLong(3))
            }
        }
        return UserLearningSnapshot(formed, pending, follows)
    }

    @Synchronized
    fun readFormedWordsForKey(key: String, t9: Boolean, offset: Int, limit: Int): List<StoredLearningEntry> {
        require(offset >= 0)
        require(limit in 0..MAX_RUNTIME_PAGE_SIZE)
        if (limit == 0 || key.isEmpty()) return emptyList()
        val reading = if (t9) t9Glob(key) ?: return emptyList() else key
        val operator = if (t9) "GLOB" else "="
        val out = ArrayList<StoredLearningEntry>(limit)
        database.rawQuery(
            "SELECT MIN(reading),word,MAX(count),MAX(last_seen) FROM learned_formed WHERE reading $operator ? " +
                "GROUP BY word ORDER BY MAX(count) DESC,MAX(last_seen) DESC,word LIMIT ? OFFSET ?",
            arrayOf(reading, limit.toString(), offset.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(
                    StoredLearningEntry(
                        cursor.getString(0),
                        cursor.getString(1),
                        StoredUsage(cursor.getDouble(2), cursor.getLong(3)),
                    ),
                )
            }
        }
        return out
    }

    @Synchronized
    fun readFormedEntries(offset: Int, limit: Int): List<StoredLearningEntry> {
        require(offset >= 0)
        require(limit in 0..MAX_RUNTIME_PAGE_SIZE)
        if (limit == 0) return emptyList()
        val out = ArrayList<StoredLearningEntry>(limit)
        database.rawQuery(
            "SELECT reading,word,count,last_seen FROM learned_formed " +
                "ORDER BY count DESC,last_seen DESC,reading,word LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(
                    StoredLearningEntry(
                        cursor.getString(0),
                        cursor.getString(1),
                        StoredUsage(cursor.getDouble(2), cursor.getLong(3)),
                    ),
                )
            }
        }
        return out
    }

    @Synchronized
    fun readFormedUsage(word: String, reading: String): StoredUsage? = readUsage(
        "SELECT count,last_seen FROM learned_formed WHERE word=? AND reading=?",
        arrayOf(word, reading),
    )

    @Synchronized
    fun readBestFormedUsage(word: String): StoredUsage? = readUsage(
        "SELECT count,last_seen FROM learned_formed WHERE word=? ORDER BY count DESC,last_seen DESC LIMIT 1",
        arrayOf(word),
    )

    @Synchronized
    fun readPendingUsage(reading: String, word: String): StoredUsage? = readUsage(
        "SELECT count,last_seen FROM learned_pending WHERE reading=? AND word=?",
        arrayOf(reading, word),
    )

    @Synchronized
    fun readFollowUsage(previousWord: String, word: String): StoredUsage? = readUsage(
        "SELECT count,last_seen FROM learned_follows WHERE prev_word=? AND word=?",
        arrayOf(previousWord, word),
    )

    @Synchronized
    fun readFollows(previousWord: String, offset: Int, limit: Int): List<Pair<String, StoredUsage>> {
        require(offset >= 0)
        require(limit in 0..MAX_RUNTIME_PAGE_SIZE)
        if (limit == 0) return emptyList()
        val out = ArrayList<Pair<String, StoredUsage>>(limit)
        database.rawQuery(
            "SELECT word,count,last_seen FROM learned_follows WHERE prev_word=? " +
                "ORDER BY count DESC,last_seen DESC,word LIMIT ? OFFSET ?",
            arrayOf(previousWord, limit.toString(), offset.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(cursor.getString(0) to StoredUsage(cursor.getDouble(1), cursor.getLong(2)))
            }
        }
        return out
    }

    @Synchronized
    fun maximumFormedCount(): Double = scalarDouble("SELECT COALESCE(MAX(count),0) FROM learned_formed")

    @Synchronized
    fun maximumFollowCount(): Double = scalarDouble("SELECT COALESCE(MAX(count),0) FROM learned_follows")

    @Synchronized
    fun maximumFollowContextCodePoints(): Int = scalarLong(
        "SELECT COALESCE(MAX(length(prev_word)),0) FROM learned_follows",
    ).toInt()

    @Synchronized
    fun learningIsEmpty(): Boolean = scalarLong("SELECT COUNT(*) FROM learned_formed") == 0L &&
        scalarLong("SELECT COUNT(*) FROM learned_pending") == 0L &&
        scalarLong("SELECT COUNT(*) FROM learned_follows") == 0L

    @Synchronized
    fun upsertFormedUsage(word: String, reading: String, usage: StoredUsage) {
        transaction { putUsage("learned_formed", "word", word, "reading", reading, usage) }
    }

    @Synchronized
    fun upsertPendingUsage(reading: String, word: String, usage: StoredUsage) {
        transaction { putUsage("learned_pending", "reading", reading, "word", word, usage) }
    }

    @Synchronized
    fun upsertFollowUsage(previousWord: String, word: String, usage: StoredUsage) {
        transaction { putUsage("learned_follows", "prev_word", previousWord, "word", word, usage) }
    }

    @Synchronized
    fun promoteLearning(
        word: String,
        reading: String,
        usage: StoredUsage,
        pendingToDelete: Collection<Pair<String, String>>,
    ) {
        transaction {
            for ((pendingReading, pendingWord) in pendingToDelete) {
                database.delete(
                    "learned_pending",
                    "reading=? AND word=?",
                    arrayOf(pendingReading, pendingWord),
                )
            }
            putUsage("learned_formed", "word", word, "reading", reading, usage)
        }
    }

    @Synchronized
    fun deletePendingLearning(keys: Collection<Pair<String, String>>) {
        if (keys.isEmpty()) return
        transaction {
            for ((reading, word) in keys) {
                database.delete("learned_pending", "reading=? AND word=?", arrayOf(reading, word))
            }
        }
    }

    @Synchronized
    fun removeLearningWord(word: String): Boolean {
        var changed = false
        transaction {
            changed = database.delete("learned_formed", "word=?", arrayOf(word)) > 0 || changed
            changed = database.delete("learned_pending", "word=?", arrayOf(word)) > 0 || changed
            changed = database.delete("learned_follows", "prev_word=? OR word=?", arrayOf(word, word)) > 0 || changed
        }
        return changed
    }

    @Synchronized
    fun replaceLearning(snapshot: UserLearningSnapshot) {
        transaction {
            database.delete("learned_formed", null, null)
            database.delete("learned_pending", null, null)
            database.delete("learned_follows", null, null)
            insertLearning(snapshot)
        }
    }

    @Synchronized
    fun clipboardHistoryCount(): Long = scalarLong("SELECT COUNT(*) FROM clipboard_history")

    @Synchronized
    fun containsClipboard(text: String): Boolean = database.rawQuery(
        "SELECT 1 FROM clipboard_history WHERE text=? LIMIT 1",
        arrayOf(text),
    ).use { it.moveToFirst() }

    @Synchronized
    fun readClipboardHistory(offset: Int = 0, limit: Int = Int.MAX_VALUE): List<String> {
        require(offset >= 0)
        require(limit >= 0)
        if (limit == 0) return emptyList()
        val out = ArrayList<String>()
        database.rawQuery(
            "SELECT text FROM clipboard_history ORDER BY recency DESC, text LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString()),
        ).use { cursor -> while (cursor.moveToNext()) out.add(cursor.getString(0)) }
        return out
    }

    @Synchronized
    fun recordClipboard(text: String) {
        transaction {
            database.delete("clipboard_history", "text=?", arrayOf(text))
            val values = ContentValues().apply {
                put("text", text)
                put("recency", nextRecency("clipboard_history", null))
            }
            database.insertOrThrow("clipboard_history", null, values)
        }
    }

    @Synchronized
    fun replaceClipboardHistory(entries: List<String>, merge: Boolean) {
        transaction { replaceClipboardHistoryInTransaction(entries, merge) }
    }

    @Synchronized
    fun deleteClipboardHistory(entries: Collection<String>) {
        if (entries.isEmpty()) return
        transaction {
            for (entry in entries) database.delete("clipboard_history", "text=?", arrayOf(entry))
        }
    }

    @Synchronized
    fun clearClipboardHistory() {
        transaction { database.delete("clipboard_history", null, null) }
    }

    @Synchronized
    fun readPhraseCategories(): List<StoredPhraseCategory> {
        val categories = ArrayList<StoredPhraseCategory>()
        database.rawQuery("SELECT name FROM phrase_categories ORDER BY position, name", null).use { categoryCursor ->
            while (categoryCursor.moveToNext()) {
                val name = categoryCursor.getString(0)
                val phrases = ArrayList<StoredPhrase>()
                database.rawQuery(
                    "SELECT text, note FROM phrases WHERE category=? ORDER BY position, text",
                    arrayOf(name),
                ).use { phraseCursor ->
                    while (phraseCursor.moveToNext()) {
                        phrases.add(StoredPhrase(phraseCursor.getString(0), phraseCursor.getString(1)))
                    }
                }
                categories.add(StoredPhraseCategory(name, phrases))
            }
        }
        return categories
    }

    @Synchronized
    fun writePhrases(output: OutputStream) {
        val writer = UserDataTransfer.writer(output)
        database.rawQuery("SELECT name FROM phrase_categories ORDER BY position,name", null).use { categoryCursor ->
            while (categoryCursor.moveToNext()) {
                val category = categoryCursor.getString(0)
                UserDataTransfer.writeEscaped(writer, 'C', category)
                database.rawQuery(
                    "SELECT text,note FROM phrases WHERE category=? ORDER BY position,text",
                    arrayOf(category),
                ).use { phraseCursor ->
                    while (phraseCursor.moveToNext()) {
                        UserDataTransfer.writeEscaped(writer, 'P', phraseCursor.getString(0))
                        val note = phraseCursor.getString(1)
                        if (note.isNotEmpty()) UserDataTransfer.writeEscaped(writer, 'N', note)
                    }
                }
            }
        }
        writer.flush()
    }

    @Synchronized
    fun importPhrases(
        input: InputStream,
        merge: Boolean,
        stage: ((UserDataTransferStage) -> Unit)? = null,
    ): Boolean = synchronized(checkpointLock) {
        val rollback = File(root, "$DATABASE_NAME.before-phrase-import")
        try {
            exportSnapshot(rollback)
            var imported = false
            transaction {
                database.execSQL("DROP TABLE IF EXISTS temp.stage_phrase_categories")
                database.execSQL("DROP TABLE IF EXISTS temp.stage_phrases")
                database.execSQL(
                    "CREATE TEMP TABLE stage_phrase_categories (name TEXT PRIMARY KEY, " +
                        "position INTEGER NOT NULL UNIQUE CHECK(position >= 0))",
                )
                database.execSQL(
                    "CREATE TEMP TABLE stage_phrases (category TEXT NOT NULL, text TEXT NOT NULL, " +
                        "note TEXT NOT NULL, position INTEGER NOT NULL CHECK(position >= 0), " +
                        "PRIMARY KEY(category,text), UNIQUE(category,position))",
                )
                var category: String? = null
                var phrase: String? = null
                var categoryPosition = 0L
                var phrasePosition = 0L
                UserDataTransfer.readPhrases(input) { row ->
                    when (row) {
                        is UserDataTransfer.PhraseRow.Category -> {
                            category = row.name
                            phrase = null
                            val values = ContentValues().apply {
                                put("name", row.name)
                                put("position", categoryPosition)
                            }
                            if (database.insertWithOnConflict(
                                    "stage_phrase_categories",
                                    null,
                                    values,
                                    SQLiteDatabase.CONFLICT_IGNORE,
                                ) != -1L
                            ) {
                                categoryPosition++
                            }
                            phrasePosition = database.rawQuery(
                                "SELECT COALESCE(MAX(position),-1)+1 FROM stage_phrases WHERE category=?",
                                arrayOf(row.name),
                            ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
                        }
                        is UserDataTransfer.PhraseRow.Phrase -> {
                            val currentCategory = category ?: throw IOException("phrase has no category")
                            phrase = row.text
                            val values = ContentValues().apply {
                                put("category", currentCategory)
                                put("text", row.text)
                                put("note", "")
                                put("position", phrasePosition)
                            }
                            if (database.insertWithOnConflict(
                                    "stage_phrases",
                                    null,
                                    values,
                                    SQLiteDatabase.CONFLICT_IGNORE,
                                ) != -1L
                            ) {
                                phrasePosition++
                            }
                        }
                        is UserDataTransfer.PhraseRow.Note -> {
                            val currentCategory = category ?: throw IOException("note has no category")
                            val currentPhrase = phrase ?: throw IOException("note has no phrase")
                            val values = ContentValues().apply { put("note", row.note) }
                            database.update(
                                "stage_phrases",
                                values,
                                "category=? AND text=? AND note=''",
                                arrayOf(currentCategory, currentPhrase),
                            )
                        }
                    }
                }
                if (scalarLong("SELECT COUNT(*) FROM stage_phrase_categories") == 0L) {
                    throw IOException("phrase transfer contains no categories")
                }
                canonicalizeStagedPhraseCategories()
                stage?.invoke(UserDataTransferStage.AFTER_VALIDATION)
                if (merge) mergeStagedPhrases() else replaceFromStagedPhrases()
                database.execSQL("DROP TABLE stage_phrases")
                database.execSQL("DROP TABLE stage_phrase_categories")
                stage?.invoke(UserDataTransferStage.BEFORE_DATABASE_COMMIT)
                imported = true
            }
            try {
                stage?.invoke(UserDataTransferStage.AFTER_DATABASE_COMMIT)
                checkpointLastGood()
            } catch (failure: Exception) {
                rollbackCommittedTransfer(rollback, failure)
                throw failure
            }
            imported
        } finally {
            rollback.delete()
        }
    }

    @Synchronized
    fun phraseCategoryCount(): Long = scalarLong("SELECT COUNT(*) FROM phrase_categories")

    @Synchronized
    fun readPhraseCategoryNames(offset: Int, limit: Int): List<String> {
        require(offset >= 0)
        require(limit in 0..MAX_RUNTIME_PAGE_SIZE)
        if (limit == 0) return emptyList()
        val out = ArrayList<String>(limit)
        database.rawQuery(
            "SELECT name FROM phrase_categories ORDER BY position,name LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString()),
        ).use { cursor -> while (cursor.moveToNext()) out.add(cursor.getString(0)) }
        return out
    }

    @Synchronized
    fun phraseCategoryExists(name: String): Boolean = database.rawQuery(
        "SELECT 1 FROM phrase_categories WHERE name=? LIMIT 1",
        arrayOf(name),
    ).use { it.moveToFirst() }

    @Synchronized
    fun phraseCount(category: String): Long = database.rawQuery(
        "SELECT COUNT(*) FROM phrases WHERE category=?",
        arrayOf(category),
    ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }

    @Synchronized
    fun phraseNote(category: String, text: String): String? = database.rawQuery(
        "SELECT note FROM phrases WHERE category=? AND text=?",
        arrayOf(category, text),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    @Synchronized
    fun ensurePhraseCategory(name: String): Boolean {
        if (phraseCategoryExists(name)) return false
        transaction { insertPhraseCategoryAtEnd(name) }
        return true
    }

    @Synchronized
    fun addPhraseCategory(name: String): Boolean {
        if (name.isEmpty() || phraseCategoryExists(name)) return false
        transaction { insertPhraseCategoryAtEnd(name) }
        return true
    }

    @Synchronized
    fun deletePhraseCategory(name: String): Boolean {
        val position = phraseCategoryPosition(name) ?: return false
        transaction {
            database.delete("phrase_categories", "name=?", arrayOf(name))
            closePositionGap("phrase_categories", null, null, position)
        }
        return true
    }

    @Synchronized
    fun renamePhraseCategory(old: String, new: String): Boolean {
        if (new.isEmpty() || old == new) return old == new && phraseCategoryExists(old)
        val position = phraseCategoryPosition(old) ?: return false
        if (phraseCategoryExists(new)) return false
        transaction {
            val temporaryPosition = scalarLong("SELECT COALESCE(MAX(position),-1)+1 FROM phrase_categories")
            val values = ContentValues().apply { put("name", new); put("position", temporaryPosition) }
            database.insertOrThrow("phrase_categories", null, values)
            database.execSQL("UPDATE phrases SET category=? WHERE category=?", arrayOf(new, old))
            database.delete("phrase_categories", "name=?", arrayOf(old))
            database.execSQL("UPDATE phrase_categories SET position=? WHERE name=?", arrayOf<Any>(position, new))
        }
        return true
    }

    @Synchronized
    fun setPhraseNote(category: String, text: String, note: String): Boolean {
        val values = ContentValues().apply { put("note", note) }
        var changed = false
        transaction { changed = database.update("phrases", values, "category=? AND text=?", arrayOf(category, text)) > 0 }
        return changed
    }

    @Synchronized
    fun addPhrases(category: String, texts: Collection<String>): Int {
        val incoming = LinkedHashSet(texts.filter { it.isNotEmpty() })
        if (incoming.isEmpty()) return 0
        var added = 0
        transaction {
            if (!phraseCategoryExists(category)) insertPhraseCategoryAtEnd(category)
            val accepted = incoming.filterNot { phraseExists(category, it) }
            if (accepted.isEmpty()) return@transaction
            shiftPhrasePositions(category, accepted.size)
            for ((position, text) in accepted.withIndex()) {
                val values = ContentValues().apply {
                    put("category", category)
                    put("text", text)
                    put("note", "")
                    put("position", position)
                }
                database.insertOrThrow("phrases", null, values)
                added++
            }
        }
        return added
    }

    @Synchronized
    fun deletePhrase(category: String, text: String): Boolean {
        val position = phrasePosition(category, text) ?: return false
        transaction {
            database.delete("phrases", "category=? AND text=?", arrayOf(category, text))
            closePositionGap("phrases", "category", category, position)
        }
        return true
    }

    @Synchronized
    fun deletePhraseEverywhere(text: String): Boolean {
        val categories = ArrayList<String>()
        database.rawQuery("SELECT category FROM phrases WHERE text=?", arrayOf(text)).use { cursor ->
            while (cursor.moveToNext()) categories.add(cursor.getString(0))
        }
        if (categories.isEmpty()) return false
        transaction {
            for (category in categories) {
                val position = phrasePosition(category, text) ?: continue
                database.delete("phrases", "category=? AND text=?", arrayOf(category, text))
                closePositionGap("phrases", "category", category, position)
            }
        }
        return true
    }

    @Synchronized
    fun clearPhrases(category: String): Int {
        var removed = 0
        transaction { removed = database.delete("phrases", "category=?", arrayOf(category)) }
        return removed
    }

    @Synchronized
    fun editPhrase(category: String, oldText: String, newText: String): Boolean {
        if (newText.isEmpty() || phraseExists(category, newText)) return false
        val values = ContentValues().apply { put("text", newText) }
        var changed = false
        transaction {
            changed = database.update("phrases", values, "category=? AND text=?", arrayOf(category, oldText)) > 0
        }
        return changed
    }

    @Synchronized
    fun movePhrases(fromCategory: String, texts: Collection<String>, toCategory: String): Int {
        if (fromCategory == toCategory || !phraseCategoryExists(toCategory)) return 0
        var moved = 0
        transaction {
            for (text in LinkedHashSet(texts)) {
                val sourcePosition = phrasePosition(fromCategory, text) ?: continue
                val sourceNote = phraseNote(fromCategory, text).orEmpty()
                val targetPosition = phrasePosition(toCategory, text)
                if (targetPosition == null) {
                    val values = ContentValues().apply {
                        put("category", toCategory)
                        put("text", text)
                        put("note", sourceNote)
                        put("position", phraseCount(toCategory))
                    }
                    database.insertOrThrow("phrases", null, values)
                } else if (sourceNote.isNotEmpty() && phraseNote(toCategory, text).isNullOrEmpty()) {
                    val values = ContentValues().apply { put("note", sourceNote) }
                    database.update("phrases", values, "category=? AND text=?", arrayOf(toCategory, text))
                }
                database.delete("phrases", "category=? AND text=?", arrayOf(fromCategory, text))
                closePositionGap("phrases", "category", fromCategory, sourcePosition)
                moved++
            }
        }
        return moved
    }

    @Synchronized
    fun reorderPhrase(category: String, fromIndex: Int, toIndex: Int): Boolean =
        reorderPosition("phrases", "category", category, fromIndex, toIndex)

    @Synchronized
    fun reorderPhraseCategory(fromIndex: Int, toIndex: Int): Boolean =
        reorderPosition("phrase_categories", null, null, fromIndex, toIndex)

    @Synchronized
    fun readPhrases(category: String, offset: Int = 0, limit: Int = Int.MAX_VALUE): List<StoredPhrase> {
        require(offset >= 0)
        require(limit >= 0)
        if (limit == 0) return emptyList()
        val out = ArrayList<StoredPhrase>()
        database.rawQuery(
            "SELECT text, note FROM phrases WHERE category=? ORDER BY position, text LIMIT ? OFFSET ?",
            arrayOf(category, limit.toString(), offset.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) out.add(StoredPhrase(cursor.getString(0), cursor.getString(1)))
        }
        return out
    }

    @Synchronized
    fun replacePhraseCategories(categories: List<StoredPhraseCategory>) {
        transaction { replacePhraseCategoriesInTransaction(categories) }
    }

    @Synchronized
    fun readCustomItems(kind: String, offset: Int = 0, limit: Int = Int.MAX_VALUE): List<String> {
        require(offset >= 0)
        require(limit >= 0)
        if (limit == 0) return emptyList()
        val out = ArrayList<String>()
        database.rawQuery(
            "SELECT value FROM custom_items WHERE kind=? ORDER BY position, value LIMIT ? OFFSET ?",
            arrayOf(kind, limit.toString(), offset.toString()),
        ).use { cursor -> while (cursor.moveToNext()) out.add(cursor.getString(0)) }
        return out
    }

    @Synchronized
    fun customItemCount(kind: String): Long = database.rawQuery(
        "SELECT COUNT(*) FROM custom_items WHERE kind=?",
        arrayOf(kind),
    ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }

    @Synchronized
    fun containsCustomItem(kind: String, value: String): Boolean = database.rawQuery(
        "SELECT 1 FROM custom_items WHERE kind=? AND value=? LIMIT 1",
        arrayOf(kind, value),
    ).use { it.moveToFirst() }

    @Synchronized
    fun addCustomItem(kind: String, value: String): Boolean {
        if (value.isEmpty() || containsCustomItem(kind, value)) return false
        transaction {
            val position = database.rawQuery(
                "SELECT COALESCE(MAX(position),-1)+1 FROM custom_items WHERE kind=?",
                arrayOf(kind),
            ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
            val values = ContentValues().apply { put("kind", kind); put("value", value); put("position", position) }
            database.insertOrThrow("custom_items", null, values)
        }
        return true
    }

    @Synchronized
    fun removeCustomItem(kind: String, value: String): Boolean {
        val position = database.rawQuery(
            "SELECT position FROM custom_items WHERE kind=? AND value=?",
            arrayOf(kind, value),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else null } ?: return false
        transaction {
            database.delete("custom_items", "kind=? AND value=?", arrayOf(kind, value))
            val count = customItemCount(kind).toInt()
            if (count > 0) {
                val highOffset = count + position + 2
                database.execSQL(
                    "UPDATE custom_items SET position=position+? WHERE kind=? AND position>?",
                    arrayOf<Any>(highOffset, kind, position),
                )
                database.execSQL(
                    "UPDATE custom_items SET position=position-? WHERE kind=? AND position>?",
                    arrayOf<Any>(highOffset + 1, kind, highOffset),
                )
            }
        }
        return true
    }

    @Synchronized
    fun replaceCustomItems(kind: String, items: List<String>) {
        transaction { replaceCustomItemsInTransaction(kind, items) }
    }

    @Synchronized
    fun readRecentItems(kind: String, offset: Int = 0, limit: Int = Int.MAX_VALUE): List<StoredRecentItem> {
        require(offset >= 0)
        require(limit >= 0)
        if (limit == 0) return emptyList()
        val out = ArrayList<StoredRecentItem>()
        database.rawQuery(
            "SELECT value, origin FROM recent_items WHERE kind=? ORDER BY recency DESC, identity LIMIT ? OFFSET ?",
            arrayOf(kind, limit.toString(), offset.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(StoredRecentItem(cursor.getString(0), if (cursor.isNull(1)) null else cursor.getString(1)))
            }
        }
        return out
    }

    @Synchronized
    fun recentItemCount(kind: String): Long = database.rawQuery(
        "SELECT COUNT(*) FROM recent_items WHERE kind=?",
        arrayOf(kind),
    ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }

    @Synchronized
    fun recentItemOrigin(kind: String, identity: String): String? = database.rawQuery(
        "SELECT origin FROM recent_items WHERE kind=? AND identity=?",
        arrayOf(kind, identity),
    ).use { cursor ->
        if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getString(0)
    }

    @Synchronized
    fun recordRecentItem(kind: String, identity: String, item: StoredRecentItem) {
        transaction {
            database.delete("recent_items", "kind=? AND identity=?", arrayOf(kind, identity))
            val values = ContentValues().apply {
                put("kind", kind)
                put("identity", identity)
                put("value", item.value)
                if (item.origin == null) putNull("origin") else put("origin", item.origin)
                put("recency", nextRecency("recent_items", kind))
            }
            database.insertOrThrow("recent_items", null, values)
        }
    }

    @Synchronized
    fun replaceRecentItems(kind: String, identities: List<Pair<String, StoredRecentItem>>, merge: Boolean) {
        transaction { replaceRecentItemsInTransaction(kind, identities, merge) }
    }

    @Synchronized
    fun clearRecentItems(kind: String) {
        transaction { database.delete("recent_items", "kind=?", arrayOf(kind)) }
    }

    @Synchronized
    fun migrateLegacyCollections(
        clipboard: ClipboardDataSnapshot?,
        customItems: Map<String, List<String>>,
        recentItems: Map<String, List<Pair<String, StoredRecentItem>>>,
        identities: Map<String, String>,
    ) {
        if (clipboard == null && customItems.isEmpty() && recentItems.isEmpty() && identities.isEmpty()) return
        var changed = false
        transaction {
            if (clipboard != null && metadataInTransaction(CLIPBOARD_MIGRATION_KEY) == null) {
                if (scalarLong("SELECT COUNT(*) FROM clipboard_history") == 0L) {
                    replaceClipboardHistoryInTransaction(clipboard.history, false)
                }
                if (scalarLong("SELECT COUNT(*) FROM phrase_categories") == 0L) {
                    replacePhraseCategoriesInTransaction(clipboard.categories)
                }
                if (readClipboardHistory() != clipboard.history || readPhraseCategories() != clipboard.categories) {
                    throw IOException("legacy clipboard verification failed")
                }
                putMetadataInTransaction(CLIPBOARD_MIGRATION_KEY, "complete")
                changed = true
            }
            for ((kind, items) in customItems) {
                val marker = "$CUSTOM_MIGRATION_PREFIX$kind"
                if (metadataInTransaction(marker) != null) continue
                if (readCustomItems(kind).isEmpty()) replaceCustomItemsInTransaction(kind, items)
                if (readCustomItems(kind) != items) throw IOException("legacy custom item verification failed: $kind")
                putMetadataInTransaction(marker, "complete")
                changed = true
            }
            for ((kind, items) in recentItems) {
                val marker = "$RECENT_MIGRATION_PREFIX$kind"
                if (metadataInTransaction(marker) != null) continue
                if (readRecentItems(kind).isEmpty()) replaceRecentItemsInTransaction(kind, items, false)
                if (readRecentItems(kind) != items.map { it.second }) {
                    throw IOException("legacy recent item verification failed: $kind")
                }
                putMetadataInTransaction(marker, "complete")
                changed = true
            }
            for ((key, value) in identities) putMetadataInTransaction("legacy_$key", value)
        }
        if (changed) checkpointLastGood()
    }

    @Synchronized
    fun migrateLegacy(userData: UserDataSnapshot?, learning: UserLearningSnapshot?, identities: Map<String, String>) {
        if (metadata(MIGRATION_KEY) != null) return
        var changed = false
        transaction {
            if (metadataInTransaction(MIGRATION_KEY) != null) return@transaction
            if (userData != null && scalarLong("SELECT COUNT(*) FROM user_words") == 0L) insertUserData(userData)
            if (learning != null && scalarLong("SELECT COUNT(*) FROM learned_formed") == 0L &&
                scalarLong("SELECT COUNT(*) FROM learned_pending") == 0L &&
                scalarLong("SELECT COUNT(*) FROM learned_follows") == 0L
            ) {
                insertLearning(learning)
            }
            for ((key, value) in identities) putMetadataInTransaction("legacy_$key", value)
            if (userData != null && readUserData() != userData) throw IOException("legacy user data verification failed")
            if (learning != null && readLearning() != learning) throw IOException("legacy learning verification failed")
            putMetadataInTransaction(MIGRATION_KEY, "complete")
            changed = true
        }
        if (changed) checkpointLastGood()
    }

    @Synchronized
    fun checkpointLastGood() = synchronized(checkpointLock) {
        try {
            val busy = database.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                if (!cursor.moveToFirst()) throw IOException("checkpoint returned no status")
                cursor.getInt(0)
            }
            if (busy != 0) throw IOException("checkpoint remained busy")
            val tmp = File(root, "$LAST_GOOD_NAME.tmp")
            copyFile(databaseFile, tmp)
            if (!isValidDatabase(tmp)) throw IOException("last-good validation failed")
            if (verifiedSnapshot(lastGoodFile, File(root, LAST_GOOD_DIGEST_NAME))) {
                val previous = File(root, LAST_GOOD_PREVIOUS_NAME)
                val previousTmp = File(root, "$LAST_GOOD_PREVIOUS_NAME.tmp")
                copyFile(lastGoodFile, previousTmp)
                atomicReplace(previousTmp, previous)
                writeTextAtomically(File(root, LAST_GOOD_PREVIOUS_DIGEST_NAME), sha256(previous) + "\n")
            }
            atomicReplace(tmp, lastGoodFile)
            writeTextAtomically(File(root, LAST_GOOD_DIGEST_NAME), sha256(lastGoodFile) + "\n")
            lastFailure = null
        } catch (e: Exception) {
            lastFailure = e.javaClass.simpleName + ": " + e.message.orEmpty()
            throw e
        }
    }

    @Synchronized
    fun integrityOk(): Boolean = database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
        cursor.moveToFirst() && cursor.getString(0) == "ok"
    }

    @Synchronized
    fun foreignKeysOk(): Boolean = database.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
        !cursor.moveToFirst()
    }

    @Synchronized
    fun exportSnapshot(destination: File) = synchronized(checkpointLock) {
        val busy = database.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
            if (!cursor.moveToFirst()) throw IOException("snapshot checkpoint returned no status")
            cursor.getInt(0)
        }
        if (busy != 0) throw IOException("snapshot checkpoint remained busy")
        destination.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) throw IOException("snapshot directory creation failed")
        }
        val temporary = File(destination.parentFile, destination.name + ".tmp")
        try {
            copyFile(databaseFile, temporary)
            val validationFailure = restoreSourceValidationFailure(temporary, false)
            if (validationFailure != null) {
                throw IOException("exported database validation failed: $validationFailure")
            }
            atomicReplace(temporary, destination)
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun restoreFrom(
        source: File,
        merge: Boolean,
        stage: ((UserDataRestoreStage) -> Unit)? = null,
    ) = synchronized(checkpointLock) {
        if (!validateRestoreSource(source)) throw IOException("restore database validation failed")
        val rollback = File(source.parentFile, "$DATABASE_NAME.before-restore")
        try {
            exportSnapshot(rollback)
            try {
                withRestoreSource(source) {
                    transaction {
                        if (merge) mergeRestoreSource() else overwriteFromRestoreSource()
                        if (!integrityOk() || !foreignKeysOk()) {
                            throw IOException("restored database verification failed")
                        }
                        stage?.invoke(UserDataRestoreStage.BEFORE_DATABASE_COMMIT)
                    }
                }
            } catch (restoreFailure: Exception) {
                runCatching { restoreRollbackSnapshot(rollback) }.onFailure(restoreFailure::addSuppressed)
                throw restoreFailure
            }
            stage?.invoke(UserDataRestoreStage.AFTER_DATABASE_COMMIT)
            try {
                checkpointLastGood()
            } catch (checkpointFailure: Exception) {
                val rolledBack = runCatching { restoreRollbackSnapshot(rollback) }
                    .onFailure(checkpointFailure::addSuppressed)
                    .isSuccess
                if (rolledBack) {
                    runCatching { checkpointLastGood() }.onFailure(checkpointFailure::addSuppressed)
                    throw checkpointFailure
                }
                if (!integrityOk() || !foreignKeysOk()) throw checkpointFailure
                lastFailure = "restore committed without a new last-good snapshot: " +
                    checkpointFailure.javaClass.simpleName + ": " + checkpointFailure.message.orEmpty()
            }
            stage?.invoke(UserDataRestoreStage.AFTER_CHECKPOINT)
        } finally {
            rollback.delete()
        }
    }

    override fun close() {
        database.close()
    }

    private fun overwriteFromRestoreSource() {
        for (table in DELETE_ORDER) database.delete(table, null, null)
        for ((table, columns) in RESTORE_TABLES) {
            database.execSQL("INSERT INTO $table ($columns) SELECT $columns FROM restore_source.$table")
        }
    }

    private fun withRestoreSource(source: File, block: () -> Unit) {
        database.execSQL("ATTACH DATABASE ? AS restore_source", arrayOf(source.absolutePath))
        var blockFailure: Exception? = null
        try {
            block()
        } catch (failure: Exception) {
            blockFailure = failure
            throw failure
        } finally {
            try {
                database.execSQL("DETACH DATABASE restore_source")
            } catch (detachFailure: Exception) {
                if (blockFailure != null) blockFailure.addSuppressed(detachFailure)
                else lastFailure = detachFailure.javaClass.simpleName + ": " + detachFailure.message.orEmpty()
            }
        }
    }

    private fun restoreRollbackSnapshot(rollback: File) {
        withRestoreSource(rollback) {
            transaction {
                overwriteFromRestoreSource()
                if (!integrityOk() || !foreignKeysOk()) throw IOException("restore rollback verification failed")
            }
        }
    }

    private fun rollbackCommittedTransfer(rollback: File, failure: Exception) {
        val rolledBack = runCatching { restoreRollbackSnapshot(rollback) }
            .onFailure(failure::addSuppressed)
            .isSuccess
        if (rolledBack) runCatching { checkpointLastGood() }.onFailure(failure::addSuppressed)
    }

    private fun mergeRestoreSource() {
        database.execSQL(
            "UPDATE user_words SET count=MIN($MAX_COUNT, count+(SELECT count FROM restore_source.user_words source WHERE source.word=user_words.word)), " +
                "last_used=MAX(last_used,(SELECT last_used FROM restore_source.user_words source WHERE source.word=user_words.word)) " +
                "WHERE EXISTS(SELECT 1 FROM restore_source.user_words source WHERE source.word=user_words.word)",
        )
        database.execSQL("INSERT OR IGNORE INTO user_words (word,count,last_used) SELECT word,count,last_used FROM restore_source.user_words")
        database.execSQL("INSERT OR IGNORE INTO user_readings (reading,word) SELECT reading,word FROM restore_source.user_readings")
        database.execSQL(
            "UPDATE user_bigrams SET count=MIN($MAX_COUNT, count+(SELECT count FROM restore_source.user_bigrams source " +
                "WHERE source.prev_word=user_bigrams.prev_word AND source.word=user_bigrams.word)) " +
                "WHERE EXISTS(SELECT 1 FROM restore_source.user_bigrams source " +
                "WHERE source.prev_word=user_bigrams.prev_word AND source.word=user_bigrams.word)",
        )
        database.execSQL("INSERT OR IGNORE INTO user_bigrams (prev_word,word,count) SELECT prev_word,word,count FROM restore_source.user_bigrams")
        mergeUsageTable("learned_formed", "word", "reading")
        mergeUsageTable("learned_pending", "reading", "word")
        mergeUsageTable("learned_follows", "prev_word", "word")
        mergeClipboardHistory()
        mergePhraseCategories()
        mergeCustomItems()
        mergeRecentItems()
        mergeSettings()
    }

    private fun mergeUsageTable(table: String, firstKey: String, secondKey: String) {
        database.execSQL(
            "UPDATE $table SET count=MIN(1.0e12, count+(SELECT count FROM restore_source.$table source " +
                "WHERE source.$firstKey=$table.$firstKey AND source.$secondKey=$table.$secondKey)), " +
                "last_seen=MAX(last_seen,(SELECT last_seen FROM restore_source.$table source " +
                "WHERE source.$firstKey=$table.$firstKey AND source.$secondKey=$table.$secondKey)) " +
                "WHERE EXISTS(SELECT 1 FROM restore_source.$table source " +
                "WHERE source.$firstKey=$table.$firstKey AND source.$secondKey=$table.$secondKey)",
        )
        database.execSQL(
            "INSERT OR IGNORE INTO $table ($firstKey,$secondKey,count,last_seen) " +
                "SELECT $firstKey,$secondKey,count,last_seen FROM restore_source.$table",
        )
    }

    private fun mergeClipboardHistory() {
        var next = database.rawQuery("SELECT COALESCE(MIN(recency), 0) - 1 FROM clipboard_history", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
        database.rawQuery("SELECT text FROM restore_source.clipboard_history ORDER BY recency DESC, text", null).use { cursor ->
            while (cursor.moveToNext()) {
                val values = ContentValues().apply {
                    put("text", cursor.getString(0))
                    put("recency", next--)
                }
                database.insertWithOnConflict("clipboard_history", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }

    private fun mergePhraseCategories() {
        var nextCategory = scalarLong("SELECT COALESCE(MAX(position), -1) + 1 FROM phrase_categories")
        database.rawQuery("SELECT name FROM restore_source.phrase_categories ORDER BY position, name", null).use { cursor ->
            while (cursor.moveToNext()) {
                val values = ContentValues().apply {
                    put("name", cursor.getString(0))
                    put("position", nextCategory)
                }
                if (database.insertWithOnConflict(
                        "phrase_categories",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_IGNORE,
                    ) != -1L
                ) {
                    nextCategory++
                }
            }
        }
        var currentCategory: String? = null
        var nextPhrasePosition = 0L
        database.rawQuery(
            "SELECT category,text,note FROM restore_source.phrases ORDER BY category,position,text",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val category = cursor.getString(0)
                val text = cursor.getString(1)
                val note = cursor.getString(2)
                database.execSQL(
                    "UPDATE phrases SET note=? WHERE category=? AND text=? AND note='' AND ?!=''",
                    arrayOf(note, category, text, note),
                )
                if (category != currentCategory) {
                    currentCategory = category
                    nextPhrasePosition = database.rawQuery(
                        "SELECT COALESCE(MAX(position), -1) + 1 FROM phrases WHERE category=?",
                        arrayOf(category),
                    ).use { positionCursor ->
                        positionCursor.moveToFirst()
                        positionCursor.getLong(0)
                    }
                }
                val values = ContentValues().apply {
                    put("category", category)
                    put("text", text)
                    put("note", note)
                    put("position", nextPhrasePosition)
                }
                if (database.insertWithOnConflict("phrases", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    nextPhrasePosition++
                }
            }
        }
    }

    private fun canonicalizeStagedPhraseCategories() {
        val hasDefault = database.rawQuery(
            "SELECT 1 FROM stage_phrase_categories WHERE name=? LIMIT 1",
            arrayOf(ClipboardStore.DEFAULT_CATEGORY_ID),
        ).use { it.moveToFirst() }
        if (hasDefault) return
        val hasLegacyDefault = database.rawQuery(
            "SELECT 1 FROM stage_phrase_categories WHERE name=? LIMIT 1",
            arrayOf(LEGACY_DEFAULT_PHRASE_CATEGORY),
        ).use { it.moveToFirst() }
        if (!hasLegacyDefault) return
        database.execSQL(
            "UPDATE stage_phrases SET category=? WHERE category=?",
            arrayOf(ClipboardStore.DEFAULT_CATEGORY_ID, LEGACY_DEFAULT_PHRASE_CATEGORY),
        )
        database.execSQL(
            "UPDATE stage_phrase_categories SET name=? WHERE name=?",
            arrayOf(ClipboardStore.DEFAULT_CATEGORY_ID, LEGACY_DEFAULT_PHRASE_CATEGORY),
        )
    }

    private fun replaceFromStagedPhrases() {
        val hasDefault = database.rawQuery(
            "SELECT 1 FROM stage_phrase_categories WHERE name=? LIMIT 1",
            arrayOf(ClipboardStore.DEFAULT_CATEGORY_ID),
        ).use { it.moveToFirst() }
        if (!hasDefault) {
            database.execSQL(
                "CREATE TEMP TABLE stage_phrase_categories_next (name TEXT PRIMARY KEY, " +
                    "position INTEGER NOT NULL UNIQUE CHECK(position >= 0))",
            )
            database.execSQL(
                "INSERT INTO stage_phrase_categories_next (name,position) VALUES (?,0)",
                arrayOf(ClipboardStore.DEFAULT_CATEGORY_ID),
            )
            database.execSQL(
                "INSERT INTO stage_phrase_categories_next (name,position) " +
                    "SELECT name,position+1 FROM stage_phrase_categories",
            )
            database.execSQL("DROP TABLE stage_phrase_categories")
            database.execSQL("ALTER TABLE stage_phrase_categories_next RENAME TO stage_phrase_categories")
        }
        database.delete("phrases", null, null)
        database.delete("phrase_categories", null, null)
        database.execSQL(
            "INSERT INTO phrase_categories (name,position) " +
                "SELECT name,position FROM stage_phrase_categories",
        )
        database.execSQL(
            "INSERT INTO phrases (category,text,note,position) " +
                "SELECT category,text,note,position FROM stage_phrases",
        )
    }

    private fun mergeStagedPhrases() {
        var nextCategory = scalarLong("SELECT COALESCE(MAX(position),-1)+1 FROM phrase_categories")
        database.rawQuery("SELECT name FROM stage_phrase_categories ORDER BY position,name", null).use { cursor ->
            while (cursor.moveToNext()) {
                val values = ContentValues().apply {
                    put("name", cursor.getString(0))
                    put("position", nextCategory)
                }
                if (database.insertWithOnConflict(
                        "phrase_categories",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_IGNORE,
                    ) != -1L
                ) {
                    nextCategory++
                }
            }
        }
        var currentCategory: String? = null
        var nextPhrasePosition = 0L
        database.rawQuery(
            "SELECT category,text,note FROM stage_phrases ORDER BY category,position,text",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val category = cursor.getString(0)
                val text = cursor.getString(1)
                val note = cursor.getString(2)
                database.execSQL(
                    "UPDATE phrases SET note=? WHERE category=? AND text=? AND note='' AND ?!=''",
                    arrayOf(note, category, text, note),
                )
                if (category != currentCategory) {
                    currentCategory = category
                    nextPhrasePosition = database.rawQuery(
                        "SELECT COALESCE(MAX(position),-1)+1 FROM phrases WHERE category=?",
                        arrayOf(category),
                    ).use { positionCursor -> positionCursor.moveToFirst(); positionCursor.getLong(0) }
                }
                val values = ContentValues().apply {
                    put("category", category)
                    put("text", text)
                    put("note", note)
                    put("position", nextPhrasePosition)
                }
                if (database.insertWithOnConflict("phrases", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    nextPhrasePosition++
                }
            }
        }
    }

    private fun mergeCustomItems() {
        var currentKind: String? = null
        var nextPosition = 0L
        database.rawQuery("SELECT kind,value FROM restore_source.custom_items ORDER BY kind,position,value", null).use { cursor ->
            while (cursor.moveToNext()) {
                val kind = cursor.getString(0)
                if (kind != currentKind) {
                    currentKind = kind
                    nextPosition = database.rawQuery(
                        "SELECT COALESCE(MAX(position), -1) + 1 FROM custom_items WHERE kind=?",
                        arrayOf(kind),
                    ).use { positionCursor ->
                        positionCursor.moveToFirst()
                        positionCursor.getLong(0)
                    }
                }
                val values = ContentValues().apply {
                    put("kind", kind)
                    put("value", cursor.getString(1))
                    put("position", nextPosition)
                }
                if (database.insertWithOnConflict("custom_items", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    nextPosition++
                }
            }
        }
    }

    private fun mergeRecentItems() {
        var currentKind: String? = null
        var nextRecency = 0L
        database.rawQuery(
            "SELECT kind,identity,value,origin FROM restore_source.recent_items ORDER BY kind,recency DESC,identity",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val kind = cursor.getString(0)
                if (kind != currentKind) {
                    currentKind = kind
                    nextRecency = database.rawQuery(
                        "SELECT COALESCE(MIN(recency), 0) - 1 FROM recent_items WHERE kind=?",
                        arrayOf(kind),
                    ).use { recencyCursor ->
                        recencyCursor.moveToFirst()
                        recencyCursor.getLong(0)
                    }
                }
                val values = ContentValues().apply {
                    put("kind", kind)
                    put("identity", cursor.getString(1))
                    put("value", cursor.getString(2))
                    if (cursor.isNull(3)) putNull("origin") else put("origin", cursor.getString(3))
                    put("recency", nextRecency)
                }
                if (database.insertWithOnConflict("recent_items", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    nextRecency--
                }
            }
        }
    }

    private fun mergeSettings() {
        database.execSQL("DROP TABLE IF EXISTS temp.restore_missing_settings")
        database.execSQL(
            "CREATE TEMP TABLE restore_missing_settings AS " +
                "SELECT source.key FROM restore_source.user_settings source " +
                "WHERE NOT EXISTS(SELECT 1 FROM user_settings local WHERE local.key=source.key)",
        )
        database.execSQL(
            "INSERT INTO user_settings (key,type,integer_value,text_value) " +
                "SELECT source.key,source.type,source.integer_value,source.text_value " +
                "FROM restore_source.user_settings source JOIN restore_missing_settings missing " +
                "ON missing.key=source.key",
        )
        database.execSQL(
            "INSERT INTO user_setting_set_values (setting_key,value) " +
                "SELECT source.setting_key,source.value FROM restore_source.user_setting_set_values source " +
                "JOIN restore_missing_settings missing ON missing.key=source.setting_key",
        )
        database.execSQL("DROP TABLE restore_missing_settings")
    }

    private fun readSettingInTransaction(key: String): StoredSettingValue? = database.rawQuery(
        "SELECT type,integer_value,text_value FROM user_settings WHERE key=?",
        arrayOf(key),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        when (cursor.getInt(0)) {
            SETTING_BOOLEAN -> StoredSettingValue.Bool(cursor.getLong(1) != 0L)
            SETTING_INT -> StoredSettingValue.Integer(cursor.getInt(1))
            SETTING_LONG -> StoredSettingValue.LongValue(cursor.getLong(1))
            SETTING_FLOAT -> StoredSettingValue.FloatValue(Float.fromBits(cursor.getInt(1)))
            SETTING_STRING -> StoredSettingValue.StringValue(cursor.getString(2))
            SETTING_STRING_SET -> {
                val values = LinkedHashSet<String>()
                database.rawQuery(
                    "SELECT value FROM user_setting_set_values WHERE setting_key=? ORDER BY value",
                    arrayOf(key),
                ).use { valueCursor ->
                    while (valueCursor.moveToNext()) values.add(valueCursor.getString(0))
                }
                StoredSettingValue.StringSetValue(values)
            }
            else -> throw IOException("unsupported setting type for $key")
        }
    }

    private fun writeSettingInTransaction(key: String, value: StoredSettingValue) {
        require(key.isNotEmpty())
        val values = ContentValues().apply {
            put("key", key)
            when (value) {
                is StoredSettingValue.Bool -> {
                    put("type", SETTING_BOOLEAN)
                    put("integer_value", if (value.value) 1L else 0L)
                    putNull("text_value")
                }
                is StoredSettingValue.Integer -> {
                    put("type", SETTING_INT)
                    put("integer_value", value.value.toLong())
                    putNull("text_value")
                }
                is StoredSettingValue.LongValue -> {
                    put("type", SETTING_LONG)
                    put("integer_value", value.value)
                    putNull("text_value")
                }
                is StoredSettingValue.FloatValue -> {
                    put("type", SETTING_FLOAT)
                    put("integer_value", value.value.toRawBits().toLong())
                    putNull("text_value")
                }
                is StoredSettingValue.StringValue -> {
                    put("type", SETTING_STRING)
                    putNull("integer_value")
                    put("text_value", value.value)
                }
                is StoredSettingValue.StringSetValue -> {
                    put("type", SETTING_STRING_SET)
                    putNull("integer_value")
                    putNull("text_value")
                }
            }
        }
        database.insertWithOnConflict("user_settings", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        database.delete("user_setting_set_values", "setting_key=?", arrayOf(key))
        if (value is StoredSettingValue.StringSetValue) {
            for (item in value.value) {
                val member = ContentValues().apply {
                    put("setting_key", key)
                    put("value", item)
                }
                database.insertOrThrow("user_setting_set_values", null, member)
            }
        }
    }

    private fun transaction(block: () -> Unit) = synchronized(checkpointLock) {
        database.beginTransaction()
        try {
            block()
            val dataVersion = (metadataInTransaction(DATA_VERSION_KEY)?.toLongOrNull() ?: 0L) + 1L
            putMetadataInTransaction(DATA_VERSION_KEY, dataVersion.toString())
            putMetadataInTransaction("last_commit_at", System.currentTimeMillis().toString())
            database.setTransactionSuccessful()
            lastFailure = null
        } catch (e: Exception) {
            lastFailure = e.javaClass.simpleName + ": " + e.message.orEmpty()
            throw e
        } finally {
            database.endTransaction()
        }
    }

    private fun insertUserData(snapshot: UserDataSnapshot) {
        for ((word, state) in snapshot.words) {
            val values = ContentValues().apply {
                put("word", word)
                put("count", state.count)
                put("last_used", state.lastUsed)
            }
            database.insertOrThrow("user_words", null, values)
        }
        for ((reading, words) in snapshot.readings) for (word in words) {
            val values = ContentValues().apply {
                put("reading", reading)
                put("word", word)
            }
            database.insertOrThrow("user_readings", null, values)
        }
        for ((previous, words) in snapshot.bigrams) for ((word, count) in words) {
            val values = ContentValues().apply {
                put("prev_word", previous)
                put("word", word)
                put("count", count)
            }
            database.insertOrThrow("user_bigrams", null, values)
        }
    }

    private fun replaceFromStagedUserDictionary() {
        database.delete("user_bigrams", null, null)
        database.delete("user_readings", null, null)
        database.delete("user_words", null, null)
        database.execSQL(
            "INSERT INTO user_words (word,count,last_used) " +
                "SELECT word,count,last_used FROM stage_user_words",
        )
        database.execSQL(
            "INSERT INTO user_readings (reading,word) SELECT reading,word FROM stage_user_readings",
        )
        database.execSQL(
            "INSERT INTO user_bigrams (prev_word,word,count) " +
                "SELECT prev_word,word,count FROM stage_user_bigrams",
        )
    }

    private fun mergeStagedUserDictionary() {
        database.execSQL(
            "UPDATE user_words SET count=MIN($MAX_COUNT, count+(SELECT count FROM stage_user_words source " +
                "WHERE source.word=user_words.word)), last_used=MAX(last_used,(SELECT last_used FROM " +
                "stage_user_words source WHERE source.word=user_words.word)) WHERE EXISTS(" +
                "SELECT 1 FROM stage_user_words source WHERE source.word=user_words.word)",
        )
        database.execSQL(
            "INSERT OR IGNORE INTO user_words (word,count,last_used) " +
                "SELECT word,count,last_used FROM stage_user_words",
        )
        database.execSQL(
            "INSERT OR IGNORE INTO user_readings (reading,word) SELECT reading,word FROM stage_user_readings",
        )
        database.execSQL(
            "UPDATE user_bigrams SET count=MIN($MAX_COUNT, count+(SELECT count FROM stage_user_bigrams source " +
                "WHERE source.prev_word=user_bigrams.prev_word AND source.word=user_bigrams.word)) " +
                "WHERE EXISTS(SELECT 1 FROM stage_user_bigrams source WHERE " +
                "source.prev_word=user_bigrams.prev_word AND source.word=user_bigrams.word)",
        )
        database.execSQL(
            "INSERT OR IGNORE INTO user_bigrams (prev_word,word,count) " +
                "SELECT prev_word,word,count FROM stage_user_bigrams",
        )
    }

    private fun insertLearning(snapshot: UserLearningSnapshot) {
        for ((word, readings) in snapshot.formed) for ((reading, state) in readings) {
            val values = ContentValues().apply {
                put("word", word)
                put("reading", reading)
                put("count", state.count)
                put("last_seen", state.lastSeen)
            }
            database.insertOrThrow("learned_formed", null, values)
        }
        for ((key, state) in snapshot.pending) {
            val values = ContentValues().apply {
                put("reading", key.first)
                put("word", key.second)
                put("count", state.count)
                put("last_seen", state.lastSeen)
            }
            database.insertOrThrow("learned_pending", null, values)
        }
        for ((previous, words) in snapshot.follows) for ((word, state) in words) {
            val values = ContentValues().apply {
                put("prev_word", previous)
                put("word", word)
                put("count", state.count)
                put("last_seen", state.lastSeen)
            }
            database.insertOrThrow("learned_follows", null, values)
        }
    }

    private fun readUsage(sql: String, args: Array<String>): StoredUsage? = database.rawQuery(sql, args).use { cursor ->
        if (cursor.moveToFirst()) StoredUsage(cursor.getDouble(0), cursor.getLong(1)) else null
    }

    private fun putUsage(
        table: String,
        firstColumn: String,
        firstValue: String,
        secondColumn: String,
        secondValue: String,
        usage: StoredUsage,
    ) {
        require(table in LEARNING_TABLES)
        val values = ContentValues().apply {
            put(firstColumn, firstValue)
            put(secondColumn, secondValue)
            put("count", usage.count)
            put("last_seen", usage.lastSeen.coerceAtLeast(0L))
        }
        database.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun replaceClipboardHistoryInTransaction(entries: List<String>, merge: Boolean) {
        if (!merge) database.delete("clipboard_history", null, null)
        val present = LinkedHashSet<String>()
        database.rawQuery("SELECT text FROM clipboard_history", null).use { cursor ->
            while (cursor.moveToNext()) present.add(cursor.getString(0))
        }
        val incoming = entries.filter { it.isNotEmpty() && present.add(it) }
        var recency = if (present.size == incoming.size) {
            incoming.size.toLong()
        } else {
            database.rawQuery("SELECT COALESCE(MIN(recency), 0) - 1 FROM clipboard_history", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
        }
        for (entry in incoming) {
            val values = ContentValues().apply {
                put("text", entry)
                put("recency", recency--)
            }
            database.insertOrThrow("clipboard_history", null, values)
        }
    }

    private fun replacePhraseCategoriesInTransaction(categories: List<StoredPhraseCategory>) {
        database.delete("phrases", null, null)
        database.delete("phrase_categories", null, null)
        for ((categoryPosition, category) in categories.withIndex()) {
            val categoryValues = ContentValues().apply {
                put("name", category.name)
                put("position", categoryPosition)
            }
            database.insertOrThrow("phrase_categories", null, categoryValues)
            for ((phrasePosition, phrase) in category.phrases.withIndex()) {
                val phraseValues = ContentValues().apply {
                    put("category", category.name)
                    put("text", phrase.text)
                    put("note", phrase.note)
                    put("position", phrasePosition)
                }
                database.insertOrThrow("phrases", null, phraseValues)
            }
        }
    }

    private fun insertPhraseCategoryAtEnd(name: String) {
        val values = ContentValues().apply {
            put("name", name)
            put("position", scalarLong("SELECT COALESCE(MAX(position),-1)+1 FROM phrase_categories"))
        }
        database.insertOrThrow("phrase_categories", null, values)
    }

    private fun phraseCategoryPosition(name: String): Int? = database.rawQuery(
        "SELECT position FROM phrase_categories WHERE name=?",
        arrayOf(name),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else null }

    private fun phrasePosition(category: String, text: String): Int? = database.rawQuery(
        "SELECT position FROM phrases WHERE category=? AND text=?",
        arrayOf(category, text),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else null }

    private fun phraseExists(category: String, text: String): Boolean = database.rawQuery(
        "SELECT 1 FROM phrases WHERE category=? AND text=? LIMIT 1",
        arrayOf(category, text),
    ).use { it.moveToFirst() }

    private fun shiftPhrasePositions(category: String, amount: Int) {
        if (amount <= 0) return
        val count = phraseCount(category).toInt()
        if (count == 0) return
        val highOffset = count + amount + 1
        database.execSQL(
            "UPDATE phrases SET position=position+? WHERE category=?",
            arrayOf<Any>(highOffset, category),
        )
        database.execSQL(
            "UPDATE phrases SET position=position-? WHERE category=?",
            arrayOf<Any>(highOffset - amount, category),
        )
    }

    private fun closePositionGap(table: String, scopeColumn: String?, scopeValue: String?, removedPosition: Int) {
        require(table == "phrases" || table == "phrase_categories")
        val countSql = if (scopeColumn == null) "SELECT COUNT(*) FROM $table" else
            "SELECT COUNT(*) FROM $table WHERE $scopeColumn=?"
        val count = database.rawQuery(countSql, scopeValue?.let { arrayOf(it) }).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        if (count == 0) return
        val highOffset = count + removedPosition + 2
        val scopeSql = if (scopeColumn == null) "" else "$scopeColumn=? AND "
        val firstArgs: Array<Any> = if (scopeValue == null) arrayOf(highOffset, removedPosition) else
            arrayOf(highOffset, scopeValue, removedPosition)
        database.execSQL(
            "UPDATE $table SET position=position+? WHERE ${scopeSql}position>?",
            firstArgs,
        )
        val secondArgs: Array<Any> = if (scopeValue == null) arrayOf(highOffset + 1, highOffset) else
            arrayOf(highOffset + 1, scopeValue, highOffset)
        database.execSQL(
            "UPDATE $table SET position=position-? WHERE ${scopeSql}position>?",
            secondArgs,
        )
    }

    private fun reorderPosition(
        table: String,
        scopeColumn: String?,
        scopeValue: String?,
        fromIndex: Int,
        toIndex: Int,
    ): Boolean {
        require(table == "phrases" || table == "phrase_categories")
        val countSql = if (scopeColumn == null) "SELECT COUNT(*) FROM $table" else
            "SELECT COUNT(*) FROM $table WHERE $scopeColumn=?"
        val count = database.rawQuery(countSql, scopeValue?.let { arrayOf(it) }).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        if (fromIndex !in 0 until count || toIndex !in 0 until count || fromIndex == toIndex) return false
        transaction {
            val scopeSql = if (scopeColumn == null) "" else "$scopeColumn=? AND "
            val temporary = count * 3 + 7
            val movedArgs: Array<Any> = if (scopeValue == null) arrayOf(temporary, fromIndex) else
                arrayOf(temporary, scopeValue, fromIndex)
            database.execSQL(
                "UPDATE $table SET position=? WHERE ${scopeSql}position=?",
                movedArgs,
            )
            val lower = minOf(fromIndex, toIndex)
            val upper = maxOf(fromIndex, toIndex)
            val highOffset = count + 2
            val rangeArgs: Array<Any> = if (scopeValue == null) arrayOf(highOffset, lower, upper) else
                arrayOf(highOffset, scopeValue, lower, upper)
            database.execSQL(
                "UPDATE $table SET position=position+? WHERE ${scopeSql}position BETWEEN ? AND ?",
                rangeArgs,
            )
            val adjustment = if (fromIndex < toIndex) highOffset + 1 else highOffset - 1
            val shiftedArgs: Array<Any> = if (scopeValue == null) arrayOf(adjustment, lower + highOffset, upper + highOffset) else
                arrayOf(adjustment, scopeValue, lower + highOffset, upper + highOffset)
            database.execSQL(
                "UPDATE $table SET position=position-? WHERE ${scopeSql}position BETWEEN ? AND ?",
                shiftedArgs,
            )
            val finalArgs: Array<Any> = if (scopeValue == null) arrayOf(toIndex, temporary) else
                arrayOf(toIndex, scopeValue, temporary)
            database.execSQL(
                "UPDATE $table SET position=? WHERE ${scopeSql}position=?",
                finalArgs,
            )
        }
        return true
    }

    private fun replaceCustomItemsInTransaction(kind: String, items: List<String>) {
        database.delete("custom_items", "kind=?", arrayOf(kind))
        val seen = HashSet<String>()
        for ((position, item) in items.filter { it.isNotEmpty() && seen.add(it) }.withIndex()) {
            val values = ContentValues().apply {
                put("kind", kind)
                put("value", item)
                put("position", position)
            }
            database.insertOrThrow("custom_items", null, values)
        }
    }

    private fun replaceRecentItemsInTransaction(
        kind: String,
        identities: List<Pair<String, StoredRecentItem>>,
        merge: Boolean,
    ) {
        if (!merge) database.delete("recent_items", "kind=?", arrayOf(kind))
        val present = LinkedHashSet<String>()
        database.rawQuery("SELECT identity FROM recent_items WHERE kind=?", arrayOf(kind)).use { cursor ->
            while (cursor.moveToNext()) present.add(cursor.getString(0))
        }
        val incoming = identities.filter { it.first.isNotEmpty() && it.second.value.isNotEmpty() && present.add(it.first) }
        var recency = if (present.size == incoming.size) {
            incoming.size.toLong()
        } else {
            database.rawQuery(
                "SELECT COALESCE(MIN(recency), 0) - 1 FROM recent_items WHERE kind=?",
                arrayOf(kind),
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
        }
        for ((identity, item) in incoming) {
            val values = ContentValues().apply {
                put("kind", kind)
                put("identity", identity)
                put("value", item.value)
                if (item.origin == null) putNull("origin") else put("origin", item.origin)
                put("recency", recency--)
            }
            database.insertOrThrow("recent_items", null, values)
        }
    }

    private fun nextRecency(table: String, kind: String?): Long {
        val (sql, args) = if (kind == null) {
            "SELECT COALESCE(MAX(recency), 0) + 1 FROM $table" to null
        } else {
            "SELECT COALESCE(MAX(recency), 0) + 1 FROM $table WHERE kind=?" to arrayOf(kind)
        }
        return database.rawQuery(sql, args).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
    }

    private fun removeWordRows(word: String) {
        database.delete("user_readings", "word=?", arrayOf(word))
        database.delete("user_bigrams", "prev_word=? OR word=?", arrayOf(word, word))
        database.delete("user_words", "word=?", arrayOf(word))
    }

    private data class UserWordFilter(val sql: String, val args: Array<String>?)

    private fun userWordFilter(query: String): UserWordFilter {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return UserWordFilter("", null)
        val lower = trimmed.lowercase()
        val reading = buildString(lower.length) {
            for (character in lower) if (character in 'a'..'z') append(character)
        }
        val pinyinOnly = reading.isNotEmpty() && lower.all {
            it.isWhitespace() || it == '\'' || it in 'a'..'z'
        }
        return if (pinyinOnly) {
            UserWordFilter(
                " WHERE instr(lower(r.word),lower(?))>0 OR instr(r.reading,?)>0",
                arrayOf(trimmed, reading),
            )
        } else {
            UserWordFilter(" WHERE instr(lower(r.word),lower(?))>0", arrayOf(trimmed))
        }
    }

    private fun t9Glob(key: String): String? = buildString(key.length * 5) {
        for (digit in key) append(
            when (digit) {
                '2' -> "[abc]"
                '3' -> "[def]"
                '4' -> "[ghi]"
                '5' -> "[jkl]"
                '6' -> "[mno]"
                '7' -> "[pqrs]"
                '8' -> "[tuv]"
                '9' -> "[wxyz]"
                else -> return null
            },
        )
    }

    private fun scalarLong(sql: String): Long = database.rawQuery(sql, null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else 0L
    }

    private fun scalarDouble(sql: String): Double = database.rawQuery(sql, null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0
    }

    private fun metadataInTransaction(key: String): String? = database.rawQuery(
        "SELECT value FROM metadata WHERE key=?",
        arrayOf(key),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun putMetadataInTransaction(key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        database.insertWithOnConflict("metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun writeRecoveryStatus(report: UserDataRecoveryReport) {
        val text = "kind=${report.kind.name.lowercase()}\ndetail=${report.detail}\n"
        writeTextAtomically(statusFile, text)
    }

    companion object {
        const val DATABASE_NAME = "user-data-v2.db"
        const val LAST_GOOD_NAME = "user-data-v2.last-good.db"
        const val STATUS_NAME = "user-data-recovery.txt"
        private const val LAST_GOOD_DIGEST_NAME = "user-data-v2.last-good.sha256"
        private const val LAST_GOOD_PREVIOUS_NAME = "user-data-v2.last-good.previous.db"
        private const val LAST_GOOD_PREVIOUS_DIGEST_NAME = "user-data-v2.last-good.previous.sha256"
        private const val MIGRATION_KEY = "beta29_migration"
        private const val CLIPBOARD_MIGRATION_KEY = "beta29_clipboard_migration"
        private const val CUSTOM_MIGRATION_PREFIX = "beta29_custom_migration_"
        private const val RECENT_MIGRATION_PREFIX = "beta29_recent_migration_"
        private const val DATA_VERSION_KEY = "data_version"
        private const val LEGACY_DEFAULT_PHRASE_CATEGORY = "默认"
        internal const val SETTINGS_MIGRATION_KEY = "beta29_settings_migration"
        internal const val SETTINGS_MIGRATION_SOURCE_COUNT_KEY = "beta29_settings_source_count"
        internal const val SETTINGS_MIGRATION_SOURCE_DIGEST_KEY = "beta29_settings_source_digest"
        internal const val SETTINGS_MIGRATION_RECORD_COUNT_KEY = "beta29_settings_record_count"
        internal const val SETTINGS_CHECKPOINT_PENDING_KEY = "settings_checkpoint_pending"
        private const val LEGACY_SCHEMA_VERSION = 3
        private const val SCHEMA_VERSION = 4
        private const val MAX_COUNT = 1_000_000_000
        internal const val MAX_RUNTIME_PAGE_SIZE = 256
        private const val SETTING_BOOLEAN = 1
        private const val SETTING_INT = 2
        private const val SETTING_LONG = 3
        private const val SETTING_FLOAT = 4
        private const val SETTING_STRING = 5
        private const val SETTING_STRING_SET = 6
        private val checkpointLock = Any()
        private val LEARNING_TABLES = setOf("learned_formed", "learned_pending", "learned_follows")
        private val DELETE_ORDER = listOf(
            "user_readings",
            "user_bigrams",
            "user_words",
            "learned_formed",
            "learned_pending",
            "learned_follows",
            "clipboard_history",
            "phrases",
            "phrase_categories",
            "custom_items",
            "recent_items",
            "user_setting_set_values",
            "user_settings",
        )
        private val RESTORE_TABLES = linkedMapOf(
            "user_words" to "word,count,last_used",
            "user_readings" to "reading,word",
            "user_bigrams" to "prev_word,word,count",
            "learned_formed" to "word,reading,count,last_seen",
            "learned_pending" to "reading,word,count,last_seen",
            "learned_follows" to "prev_word,word,count,last_seen",
            "clipboard_history" to "text,recency",
            "phrase_categories" to "name,position",
            "phrases" to "category,text,note,position",
            "custom_items" to "kind,value,position",
            "recent_items" to "kind,identity,value,origin,recency",
            "user_settings" to "key,type,integer_value,text_value",
            "user_setting_set_values" to "setting_key,value",
        )
        private val LEGACY_EXPECTED_TABLES = RESTORE_TABLES.keys
            .filterNotTo(LinkedHashSet()) { it == "user_settings" || it == "user_setting_set_values" }
            .plus(setOf("metadata", "android_metadata"))
        private val EXPECTED_TABLES = RESTORE_TABLES.keys + setOf("metadata", "android_metadata")

        fun open(root: File): UserDataDatabase {
            if (!root.exists() && !root.mkdirs()) throw IOException("user data directory creation failed")
            val main = File(root, DATABASE_NAME)
            val lastGood = File(root, LAST_GOOD_NAME)
            var report = UserDataRecoveryReport(UserDataRecoveryKind.EXISTING, "database integrity verified")
            if (main.isFile && !isValidDatabase(main)) {
                val previous = File(root, LAST_GOOD_PREVIOUS_NAME)
                val restored = when {
                    verifiedSnapshot(lastGood, File(root, LAST_GOOD_DIGEST_NAME)) -> lastGood
                    verifiedSnapshot(previous, File(root, LAST_GOOD_PREVIOUS_DIGEST_NAME)) -> previous
                    else -> null
                }
                if (restored != null) {
                    val tmp = File(root, "$DATABASE_NAME.restore")
                    copyFile(restored, tmp)
                    atomicReplace(tmp, main)
                    deleteCompanions(main)
                    val source = if (restored == lastGood) "last-good" else "previous last-good"
                    report = UserDataRecoveryReport(UserDataRecoveryKind.LAST_GOOD, "restored verified $source snapshot")
                } else {
                    main.delete()
                    deleteCompanions(main)
                    report = UserDataRecoveryReport(UserDataRecoveryKind.EMPTY, "created verified empty database after corruption")
                }
            }
            val db = SQLiteDatabase.openOrCreateDatabase(main, null)
            createSchema(db)
            return UserDataDatabase(root, db, report).also { opened ->
                if (report.kind != UserDataRecoveryKind.EXISTING &&
                    opened.metadata(SETTINGS_MIGRATION_KEY) != null
                ) {
                    opened.updateSettings(
                        mapOf(UserSettingsSchema.CLIPBOARD_HISTORY to StoredSettingValue.Bool(false)),
                    )
                    runCatching {
                        opened.checkpointLastGood()
                        opened.markSettingsCheckpointed()
                    }
                }
            }
        }

        fun fileIdentity(file: File): String = if (!file.isFile) {
            "absent"
        } else {
            "${file.length()}:${sha256(file)}"
        }

        fun validateRestoreSource(file: File): Boolean = restoreSourceValidationFailure(file, false) == null

        internal fun validateRestoreSourceForUpgrade(file: File): Boolean =
            restoreSourceValidationFailure(file, true) == null

        private fun restoreSourceValidationFailure(file: File, allowLegacy: Boolean): String? {
            if (!file.isFile || file.length() == 0L) return "database file is absent or empty"
            return try {
                SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    val version = db.rawQuery("PRAGMA user_version", null).use { cursor ->
                        cursor.moveToFirst()
                        cursor.getInt(0)
                    }
                    val tables = LinkedHashSet<String>()
                    db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
                        null,
                    ).use { cursor -> while (cursor.moveToNext()) tables.add(cursor.getString(0)) }
                    val integrity = db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                        cursor.moveToFirst() && cursor.getString(0) == "ok"
                    }
                    val foreignKeyViolation = db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                        cursor.moveToFirst()
                    }
                    when {
                        version == SCHEMA_VERSION && tables != EXPECTED_TABLES ->
                            "table set $tables does not match $EXPECTED_TABLES"
                        version == LEGACY_SCHEMA_VERSION && allowLegacy && tables != LEGACY_EXPECTED_TABLES ->
                            "legacy table set $tables does not match $LEGACY_EXPECTED_TABLES"
                        version != SCHEMA_VERSION && !(allowLegacy && version == LEGACY_SCHEMA_VERSION) ->
                            "schema version $version is unsupported"
                        !integrity -> "integrity check failed"
                        foreignKeyViolation -> "foreign key check failed"
                        else -> null
                    }
                }
            } catch (failure: Exception) {
                failure.javaClass.simpleName + ": " + failure.message.orEmpty()
            }
        }

        private fun createSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS user_words (word TEXT PRIMARY KEY, count INTEGER NOT NULL CHECK(count > 0), last_used INTEGER NOT NULL CHECK(last_used >= 0))")
            db.execSQL("CREATE TABLE IF NOT EXISTS user_readings (reading TEXT NOT NULL, word TEXT NOT NULL, PRIMARY KEY(reading, word), FOREIGN KEY(word) REFERENCES user_words(word) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS user_readings_word ON user_readings(word)")
            db.execSQL("CREATE TABLE IF NOT EXISTS user_bigrams (prev_word TEXT NOT NULL, word TEXT NOT NULL, count INTEGER NOT NULL CHECK(count > 0), PRIMARY KEY(prev_word, word), FOREIGN KEY(word) REFERENCES user_words(word) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS user_bigrams_prev ON user_bigrams(prev_word, count DESC, word)")
            db.execSQL("CREATE TABLE IF NOT EXISTS learned_formed (word TEXT NOT NULL, reading TEXT NOT NULL, count REAL NOT NULL CHECK(count > 0), last_seen INTEGER NOT NULL CHECK(last_seen >= 0), PRIMARY KEY(word, reading))")
            db.execSQL("CREATE INDEX IF NOT EXISTS learned_formed_reading ON learned_formed(reading, count DESC, word)")
            db.execSQL("CREATE TABLE IF NOT EXISTS learned_pending (reading TEXT NOT NULL, word TEXT NOT NULL, count REAL NOT NULL CHECK(count > 0), last_seen INTEGER NOT NULL CHECK(last_seen >= 0), PRIMARY KEY(reading, word))")
            db.execSQL("CREATE TABLE IF NOT EXISTS learned_follows (prev_word TEXT NOT NULL, word TEXT NOT NULL, count REAL NOT NULL CHECK(count > 0), last_seen INTEGER NOT NULL CHECK(last_seen >= 0), PRIMARY KEY(prev_word, word))")
            db.execSQL("CREATE INDEX IF NOT EXISTS learned_follows_prev ON learned_follows(prev_word, count DESC, word)")
            db.execSQL("CREATE TABLE IF NOT EXISTS clipboard_history (text TEXT PRIMARY KEY, recency INTEGER NOT NULL UNIQUE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS clipboard_history_recency ON clipboard_history(recency DESC)")
            db.execSQL("CREATE TABLE IF NOT EXISTS phrase_categories (name TEXT PRIMARY KEY, position INTEGER NOT NULL UNIQUE CHECK(position >= 0))")
            db.execSQL("CREATE TABLE IF NOT EXISTS phrases (category TEXT NOT NULL, text TEXT NOT NULL, note TEXT NOT NULL, position INTEGER NOT NULL CHECK(position >= 0), PRIMARY KEY(category, text), UNIQUE(category, position), FOREIGN KEY(category) REFERENCES phrase_categories(name) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS phrases_position ON phrases(category, position)")
            db.execSQL("CREATE TABLE IF NOT EXISTS custom_items (kind TEXT NOT NULL, value TEXT NOT NULL, position INTEGER NOT NULL CHECK(position >= 0), PRIMARY KEY(kind, value), UNIQUE(kind, position))")
            db.execSQL("CREATE INDEX IF NOT EXISTS custom_items_position ON custom_items(kind, position)")
            db.execSQL("CREATE TABLE IF NOT EXISTS recent_items (kind TEXT NOT NULL, identity TEXT NOT NULL, value TEXT NOT NULL, origin TEXT, recency INTEGER NOT NULL, PRIMARY KEY(kind, identity), UNIQUE(kind, recency))")
            db.execSQL("CREATE INDEX IF NOT EXISTS recent_items_recency ON recent_items(kind, recency DESC)")
            db.execSQL("CREATE TABLE IF NOT EXISTS user_settings (key TEXT PRIMARY KEY, type INTEGER NOT NULL CHECK(type BETWEEN 1 AND 6), integer_value INTEGER, text_value TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS user_setting_set_values (setting_key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(setting_key, value), FOREIGN KEY(setting_key) REFERENCES user_settings(key) ON DELETE CASCADE)")
            db.execSQL("PRAGMA user_version=$SCHEMA_VERSION")
        }

        private fun isValidDatabase(file: File): Boolean {
            if (!file.isFile || file.length() == 0L) return false
            return try {
                SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                        cursor.moveToFirst() && cursor.getString(0) == "ok"
                    }
                }
            } catch (_: SQLiteException) {
                false
            }
        }

        private fun copyFile(source: File, destination: File) {
            FileInputStream(source).use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output, 64 * 1024)
                    output.fd.sync()
                }
            }
        }

        private fun writeTextAtomically(destination: File, value: String) {
            val temporary = File(destination.parentFile, destination.name + ".tmp")
            FileOutputStream(temporary).use { output ->
                output.write(value.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            atomicReplace(temporary, destination)
        }

        private fun verifiedSnapshot(lastGood: File, digestFile: File): Boolean {
            if (!lastGood.isFile || !isValidDatabase(lastGood)) return false
            val expected = runCatching { digestFile.readText().trim() }.getOrNull() ?: return false
            return expected.matches(Regex("[0-9a-f]{64}")) && expected == sha256(lastGood)
        }

        private fun atomicReplace(source: File, destination: File) {
            try {
                Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        private fun deleteCompanions(main: File) {
            File(main.parentFile, main.name + "-wal").delete()
            File(main.parentFile, main.name + "-shm").delete()
            File(main.parentFile, main.name + "-journal").delete()
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        private fun saturatingAdd(left: Int, right: Int): Int =
            minOf(MAX_COUNT.toLong(), left.toLong() + right.toLong()).toInt()
    }
}
