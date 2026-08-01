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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class StoredWord(val count: Int, val lastUsed: Long)

internal data class UserDataSnapshot(
    val words: Map<String, StoredWord>,
    val bigrams: Map<String, Map<String, Int>>,
    val readings: Map<String, Set<String>>,
)

internal data class StoredUsage(val count: Double, val lastSeen: Long)

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

internal enum class UserDataRecoveryKind {
    EXISTING,
    LAST_GOOD,
    EMPTY,
}

internal data class UserDataRecoveryReport(
    val kind: UserDataRecoveryKind,
    val detail: String,
)

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
        }
        checkpointLastGood()
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
            val validationFailure = restoreSourceValidationFailure(temporary)
            if (validationFailure != null) {
                throw IOException("exported database validation failed: $validationFailure")
            }
            atomicReplace(temporary, destination)
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun restoreFrom(source: File, merge: Boolean) = synchronized(checkpointLock) {
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
                    }
                }
            } catch (restoreFailure: Exception) {
                runCatching { restoreRollbackSnapshot(rollback) }.onFailure(restoreFailure::addSuppressed)
                throw restoreFailure
            }
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
        val nextPhrase = HashMap<String, Long>()
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
                val position = nextPhrase.getOrPut(category) {
                    database.rawQuery(
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
                    put("position", position)
                }
                if (database.insertWithOnConflict("phrases", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    nextPhrase[category] = position + 1
                }
            }
        }
    }

    private fun mergeCustomItems() {
        val nextPosition = HashMap<String, Long>()
        database.rawQuery("SELECT kind,value FROM restore_source.custom_items ORDER BY kind,position,value", null).use { cursor ->
            while (cursor.moveToNext()) {
                val kind = cursor.getString(0)
                val position = nextPosition.getOrPut(kind) {
                    database.rawQuery(
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
                    put("position", position)
                }
                if (database.insertWithOnConflict("custom_items", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    nextPosition[kind] = position + 1
                }
            }
        }
    }

    private fun mergeRecentItems() {
        val nextRecency = HashMap<String, Long>()
        database.rawQuery(
            "SELECT kind,identity,value,origin FROM restore_source.recent_items ORDER BY kind,recency DESC,identity",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val kind = cursor.getString(0)
                val recency = nextRecency.getOrPut(kind) {
                    database.rawQuery(
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
                    put("recency", recency)
                }
                if (database.insertWithOnConflict("recent_items", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    nextRecency[kind] = recency - 1
                }
            }
        }
    }

    private fun transaction(block: () -> Unit) = synchronized(checkpointLock) {
        database.beginTransaction()
        try {
            block()
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

    private fun scalarLong(sql: String): Long = database.rawQuery(sql, null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else 0L
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
        private const val SCHEMA_VERSION = 3
        private const val MAX_COUNT = 1_000_000_000
        private val checkpointLock = Any()
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
        )
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
            return UserDataDatabase(root, db, report)
        }

        fun fileIdentity(file: File): String = if (!file.isFile) {
            "absent"
        } else {
            "${file.length()}:${sha256(file)}"
        }

        fun validateRestoreSource(file: File): Boolean = restoreSourceValidationFailure(file) == null

        private fun restoreSourceValidationFailure(file: File): String? {
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
                        version != SCHEMA_VERSION -> "schema version $version is unsupported"
                        tables != EXPECTED_TABLES -> "table set $tables does not match $EXPECTED_TABLES"
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
