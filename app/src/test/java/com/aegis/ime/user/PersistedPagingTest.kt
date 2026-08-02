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

import androidx.test.core.app.ApplicationProvider
import com.aegis.ime.decoder.T9Pinyin
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersistedPagingTest {

    private fun root(): File = Files.createTempDirectory("persisted-paging").toFile().also { it.deleteOnExit() }

    @Test
    fun batchedRankingReadsMatchScalarSemanticsAndCacheMisses() {
        val root = root()
        UserDataDatabase.open(root).use { database ->
            database.replaceUserData(
                UserDataSnapshot(
                    words = mapOf("甲" to StoredWord(9, 1_900L), "乙" to StoredWord(3, 1_000L)),
                    bigrams = emptyMap(),
                    readings = mapOf("jia" to setOf("甲"), "yi" to setOf("乙")),
                ),
            )
            database.replaceLearning(
                UserLearningSnapshot(
                    formed = mapOf(
                        "甲" to mapOf(
                            "jia" to StoredUsage(5.0, 1_900L),
                            "other" to StoredUsage(4.0, 2_000L),
                        ),
                    ),
                    pending = emptyMap(),
                    follows = mapOf(
                        "前" to mapOf("甲" to StoredUsage(2.0, 1_900L)),
                        "更前" to mapOf("甲" to StoredUsage(3.0, 1_800L)),
                    ),
                ),
            )
            val words = listOf("甲", "乙", "缺失")
            val scalarModel = UserModel({ 2_000L }, database)
            val scalarLearning = UserLearning({ 2_000L }, database)
            val expectedModel = words.associateWith(scalarModel::wordBoost)
            val expectedLearning = words.associateWith {
                scalarLearning.formedWeight(it) + scalarLearning.followBoost("更前", it)
            }

            val batchedModel = UserModel({ 2_000L }, database)
            val batchedLearning = UserLearning({ 2_000L }, database)
            assertEquals(expectedModel, batchedModel.wordBoosts(words))
            assertEquals(expectedLearning, batchedLearning.rankingBoosts("更前", words))

            database.resetRankingReadCountsForTest()
            words.forEach {
                batchedModel.wordBoost(it)
                batchedLearning.formedWeight(it)
                batchedLearning.followBoost("更前", it)
            }
            assertEquals(0 to 0, database.rankingReadCountsForTest())
        }
    }

    @Test
    fun userDictionarySearchIsStablePagedAndDoesNotPopulateAnUnboundedModel() {
        val root = root()
        UserDataDatabase.open(root).use { database ->
            val words = LinkedHashMap<String, StoredWord>()
            val readings = LinkedHashMap<String, Set<String>>()
            repeat(5_000) { index ->
                val word = "词${index.toString().padStart(5, '0')}"
                val group = index % 50
                val reading = "reading" + ('a'.code + group / 26).toChar() + ('a'.code + group % 26).toChar()
                words[word] = StoredWord(5_000 - index, index.toLong())
                readings[reading] = readings[reading].orEmpty() + word
            }
            database.replaceUserData(UserDataSnapshot(words, emptyMap(), readings))
            val model = UserModel(database = database)
            assertEquals(5_000L, model.entryCount())
            assertEquals(100L, model.entryCount("readingah"))
            val first = model.entryPage("readingah", 0, 64)
            val second = model.entryPage("readingah", 64, 64)
            assertEquals(64, first.size)
            assertEquals(36, second.size)
            assertTrue((first + second).zipWithNext().all { (left, right) -> left.count >= right.count })
            assertEquals(0 to 0, model.runtimeCacheSizesForTest())
            repeat(1_000) { model.wordBoost("词${it.toString().padStart(5, '0')}") }
            assertTrue(model.runtimeCacheSizesForTest().first <= 256)
        }
    }

    @Test
    fun learnedCandidatesAndCachesStayBoundedAcrossLargePersistedData() {
        val root = root()
        UserDataDatabase.open(root).use { database ->
            val formed = LinkedHashMap<String, Map<String, StoredUsage>>()
            repeat(2_000) { index ->
                formed["学习$index"] = mapOf("xuexi" to StoredUsage((2_000 - index).toDouble(), index.toLong()))
            }
            database.replaceLearning(UserLearningSnapshot(formed, emptyMap(), emptyMap()))
            val learning = UserLearning({ 2_000L }, database)
            assertEquals(128, learning.formedWordsForPage("xuexi", 0, 128).size)
            assertEquals(128, learning.formedWordsForPage("xuexi", 128, 128).size)
            repeat(1_000) { learning.formedWeight("学习$it") }
            assertTrue(learning.runtimeCacheSizesForTest().first <= 256)
            assertEquals(0, learning.runtimeCacheSizesForTest().second)
        }
    }

    @Test
    fun databaseBackedLearningPreservesPromotionFollowAndRemovalSemantics() {
        val root = root()
        UserDataDatabase.open(root).use { database ->
            fun typeName(learning: UserLearning) {
                learning.observeCommit(null, "张", "zhang", 2_000L)
                learning.observeCommit("张", "伟", "wei", 2_000L)
                learning.observeCommit("伟", "明", "ming", 2_000L)
                learning.observeBreak()
            }
            repeat(2) { typeName(UserLearning({ 2_000L }, database)) }
            val learning = UserLearning({ 2_000L }, database)
            typeName(learning)
            assertEquals(listOf("张伟明"), learning.formedWordsFor("zhangweiming"))
            assertEquals(listOf("张伟明"), learning.formedWordsFor(T9Pinyin.toT9("zhangweiming")))
            assertTrue(learning.formedWordsFor("zhangwei").isEmpty())
            assertTrue(learning.formedWordsFor("weiming").isEmpty())
            assertTrue(learning.formedWeight("张伟明") > 0.0)
            assertTrue(learning.followBoost("前张", "伟") > 0.0)

            val reopened = UserLearning({ 2_000L }, database)
            assertEquals(listOf("张伟明"), reopened.formedWordsFor("zhangweiming"))
            reopened.removeWord("张伟明")
            assertTrue(reopened.formedWordsFor("zhangweiming").isEmpty())
            assertEquals(0.0, reopened.formedWeight("张伟明"), 0.0)
        }
    }

    @Test
    fun persistedCollectionsPageReorderAndReadConcurrently() {
        val root = root()
        val preferences = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("paging-${root.name}", 0)
        UserDataDatabase.open(root).use { database ->
            val clipboard = ClipboardStore(root, database).apply { load() }
            assertTrue(clipboard.importHistory((0 until 1_000).map { "clip-$it" }, merge = false))
            assertEquals(60, clipboard.historyPage(0, 60).size)
            assertEquals("clip-900", clipboard.historyPage(900, 1).single())
            assertTrue(clipboard.addCategory("large"))
            assertEquals(500, clipboard.addPhrasesTo("large", (0 until 500).map { "phrase-$it" }))
            assertEquals((200 until 220).map { "phrase-$it" }, clipboard.phrasesPage("large", 200, 20))
            assertTrue(clipboard.reorderPhrase("large", 250, 10))
            assertEquals("phrase-250", clipboard.phrasesPage("large", 10, 1).single())
            val custom = CustomSymbolStore(preferences, "custom_symbols", database)
            repeat(300) { assertTrue(custom.add("custom-$it")) }
            assertEquals(128, custom.list().size)
            assertEquals("custom-250", custom.page(250, 1).single())
            val versionBeforeConcurrentWrites = database.dataVersion()
            val pool = Executors.newFixedThreadPool(4)
            repeat(4) { worker ->
                pool.submit {
                    repeat(100) { step ->
                        database.readUserWordEntries(offset = 0, limit = 32)
                        database.recordWord("thread-$worker-$step", "thread$worker", null, step.toLong(), true)
                        clipboard.historyPage(step, 16)
                    }
                }
            }
            pool.shutdown()
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
            assertTrue(database.dataVersion() > versionBeforeConcurrentWrites)
            assertTrue(database.integrityOk())
            assertTrue(database.foreignKeysOk())
        }
    }

    @Test
    fun everyPersistedPageRejectsAContinuationFromAnOlderDatabaseVersion() {
        val root = root()
        UserDataDatabase.open(root).use { database ->
            database.replaceUserData(
                UserDataSnapshot(
                    words = mapOf("甲" to StoredWord(3, 3L), "乙" to StoredWord(2, 2L)),
                    bigrams = mapOf("前" to mapOf("甲" to 3, "乙" to 2)),
                    readings = mapOf("same" to setOf("甲", "乙")),
                ),
            )
            database.replaceLearning(
                UserLearningSnapshot(
                    formed = mapOf(
                        "学习甲" to mapOf("xuexi" to StoredUsage(3.0, 3L)),
                        "学习乙" to mapOf("xuexi" to StoredUsage(2.0, 2L)),
                    ),
                    pending = emptyMap(),
                    follows = mapOf("前" to mapOf("甲" to StoredUsage(3.0, 3L))),
                ),
            )
            database.replaceClipboardHistory(listOf("剪贴一", "剪贴二"), merge = false)
            database.replacePhraseCategories(
                listOf(StoredPhraseCategory("常用", listOf(StoredPhrase("短语一", ""), StoredPhrase("短语二", "")))),
            )
            database.replaceCustomItems("custom_symbols", listOf("符号一", "符号二"))
            database.replaceRecentItems(
                "emoji",
                listOf("one" to StoredRecentItem("表情一", null), "two" to StoredRecentItem("表情二", "分类")),
                merge = false,
            )
            val reads: List<(Long?) -> PersistedPage<*>> = listOf(
                { version -> database.readUserWordEntriesPage("", 0, 1, version) },
                { version -> database.readUserWordsForKeyPage("same", false, 0, 1, version) },
                { version -> database.readUserSuccessorsPage("前", 0, 1, version) },
                { version -> database.readFormedWordsForKeyPage("xuexi", false, 0, 1, version) },
                { version -> database.readFormedEntriesPage(0, 1, version) },
                { version -> database.readFollowsPage("前", 0, 1, version) },
                { version -> database.readClipboardHistoryPage(0, 1, version) },
                { version -> database.readPhraseCategoryNamesPage(0, 1, version) },
                { version -> database.readPhrasesPage("常用", 0, 1, version) },
                { version -> database.readCustomItemsPage("custom_symbols", 0, 1, version) },
                { version -> database.readRecentItemsPage("emoji", 0, 1, version) },
            )
            val version = database.dataVersion()
            for (read in reads) {
                val first = read(null)
                assertEquals(version, first.version)
                assertFalse(first.restartRequired)
                assertEquals(1, first.items.size)
            }

            database.putMetadata("concurrent-page-write", "done")
            assertTrue(database.dataVersion() > version)
            for (read in reads) {
                val continuation = read(version)
                assertTrue(continuation.restartRequired)
                assertTrue(continuation.items.isEmpty())
                assertTrue(continuation.version > version)
            }
        }
    }

    @Test
    fun offsetTraversalExplicitlyRestartsAfterAnInterleavedWriteAndThenHasNoGapsOrDuplicates() {
        val root = root()
        val beforeWords = LinkedHashMap<String, StoredWord>()
        val beforeReadings = LinkedHashMap<String, Set<String>>()
        repeat(500) { index ->
            val word = "词${index.toString().padStart(3, '0')}"
            beforeWords[word] = StoredWord(500 - index, index.toLong())
            beforeReadings["reading${index.toString().padStart(3, '0')}"] = setOf(word)
        }
        UserDataDatabase.open(root).use { reader ->
            reader.replaceUserData(UserDataSnapshot(beforeWords, emptyMap(), beforeReadings))
            val first = reader.readUserWordEntriesPage("", 0, 37)
            assertFalse(first.restartRequired)
            assertEquals(37, first.items.size)

            UserDataDatabase.open(root).use { writer ->
                val afterWords = LinkedHashMap(beforeWords)
                val afterReadings = LinkedHashMap(beforeReadings)
                afterWords["新首项"] = StoredWord(10_000, 10_000L)
                afterReadings["aaaa"] = setOf("新首项")
                writer.replaceUserData(UserDataSnapshot(afterWords, emptyMap(), afterReadings))
            }

            val rejected = reader.readUserWordEntriesPage("", 37, 37, first.version)
            assertTrue(rejected.restartRequired)
            assertTrue(rejected.items.isEmpty())

            val all = ArrayList<StoredUserWordEntry>()
            var offset = 0
            var version: Long? = null
            while (true) {
                val page = reader.readUserWordEntriesPage("", offset, 37, version)
                assertFalse(page.restartRequired)
                if (version == null) version = page.version else assertEquals(version, page.version)
                all.addAll(page.items)
                if (page.items.size < 37) break
                offset += page.items.size
            }
            assertEquals(501, all.size)
            assertEquals(501, all.map { it.reading to it.word }.distinct().size)
            assertEquals("新首项", all.first().word)
            assertEquals(reader.userWordEntryCount(), all.size.toLong())
        }
    }

    @Test
    fun productionStartupAndManagementUsePagedSources() {
        val sourceRoot = File(System.getProperty("user.dir"), "src/main/java/com/aegis/ime")
        val model = File(sourceRoot, "user/UserModel.kt").readText()
        val learning = File(sourceRoot, "user/UserLearning.kt").readText()
        val clipboard = File(sourceRoot, "user/ClipboardStore.kt").readText()
        val service = File(sourceRoot, "AegisInputMethodService.kt").readText()
        val page = File(sourceRoot, "ui/UserDictPage.kt").readText()
        assertFalse(model.contains("database?.readUserData()?.let(::applyStored)"))
        assertFalse(learning.contains("database?.readLearning()?.let(::applyStored)"))
        assertFalse(clipboard.contains("loadedHistory = backing.readClipboardHistory()"))
        assertFalse(clipboard.contains("backing.readPhraseCategories().mapTo"))
        assertTrue(service.contains("historyPageProvider ="))
        assertTrue(service.contains("phrasePageProvider ="))
        assertTrue(page.contains("UserDictEdit.pageSnapshot"))
        assertTrue(page.contains("restartRequired"))
        assertTrue(page.contains("USER_DICT_PAGE_SIZE = 100"))
        assertFalse(page.contains("UserDictSearch.index"))
        assertFalse(page.contains("UserDictEdit.list(userDb)"))
    }
}
