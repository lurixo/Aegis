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

import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class LiveUserDictHost(
    private val model: UserModel,
    private val userDb: File,
    private val userLearning: UserLearning? = null,
    private val userLearnFile: File? = null,
    private val onSaved: (userDbMtime: Long?, userLearnMtime: Long?) -> Unit = { _, _ -> },
) : UserDictHot.Host {

    @Volatile
    private var writer: Thread? = null

    @Volatile
    var writing: Boolean = false
        private set

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "aegis-userdict-io").apply { isDaemon = true }.also { writer = it }
    }

    override fun addWord(reading: String, word: String, now: Long): Boolean {
        if (word.isBlank() || !model.readable) return false
        if (!model.addManualWord(reading, word, now)) return false
        return save().dictionary
    }

    override fun removeWord(reading: String, word: String): Boolean {
        if (word.isBlank() || !model.readable) return false
        model.removeWord(reading, word)
        userLearning?.removeWord(word)
        return save().both
    }

    override fun importUserDict(importFile: File, merge: Boolean, now: Long): Boolean {
        if (!importFile.exists() || importFile.length() == 0L) return false
        if (merge && !model.readable) return false
        try {
            if (merge) {
                if (!model.importFrom(importFile, now)) return false
            } else {
                val incoming = UserModel().apply { load(importFile, sweepStale = false) }
                if (incoming.isEmpty()) return false
                model.reload(importFile)
            }
        } catch (_: IllegalArgumentException) {
            return false
        } catch (_: IOException) {
            return false
        }
        return save().dictionary
    }

    override fun entries(): List<UserModel.Entry> = model.userWordEntries()

    override fun forgottenCount(): Int = model.forgottenCount

    override fun dictionaryReadable(): Boolean = model.readable

    override fun learnedEntries(): List<UserLearning.Formed> = userLearning?.formedEntries().orEmpty()

    override fun hasLearnedData(): Boolean = userLearning?.isEmpty() == false

    override fun learnedReadable(): Boolean = userLearning?.readable != false

    override fun removeLearned(word: String, reading: String): Boolean {
        val learning = userLearning
        if (learning != null && !learning.readable) return false
        learning?.removeFormed(word, reading)
        return saveLearning().learning
    }

    override fun clearLearned(): Boolean {
        userLearning?.clear()
        return saveLearning().learning
    }

    override fun flush(): Boolean {
        if (!anythingUnsaved()) return true
        return onWriterThread(::persistUnsaved).both
    }

    override fun flushDictionary(): Boolean {
        if (!anythingUnsaved()) return true
        return onWriterThread(::persistUnsaved).dictionary
    }

    fun scheduleSave() {
        val queued = runCatching { io.execute { persistUnsaved() } }.isSuccess
        if (!queued) persistUnsaved()
    }

    fun handOff(work: () -> Unit): Boolean = runCatching { io.execute(work) }.isSuccess

    fun stopSaving() {
        runCatching { io.shutdown() }
    }

    private fun anythingUnsaved(): Boolean = model.dirty || userLearning?.dirty == true

    private fun persistUnsaved(): PersistResult =
        if (anythingUnsaved()) persistHere(writeUserDb = model.dirty) else PersistResult.DONE

    private fun save(): PersistResult = onWriterThread { persistHere(writeUserDb = true) }

    private fun saveLearning(): PersistResult = onWriterThread { persistHere(writeUserDb = false) }

    private fun onWriterThread(work: () -> PersistResult): PersistResult {
        val queued = Callable(work)
        if (Thread.currentThread() === writer) return queued.call()
        val pending = runCatching { io.submit(queued) }.getOrNull() ?: return queued.call()
        return try {
            pending.get(WRITE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            PersistResult.FAILED
        } catch (_: ExecutionException) {
            PersistResult.FAILED
        } catch (_: TimeoutException) {
            PersistResult.FAILED
        }
    }

    private fun persistHere(writeUserDb: Boolean): PersistResult {
        val outer = writing
        writing = true
        try {
            return writeNow(writeUserDb)
        } finally {
            writing = outer
        }
    }

    private fun writeNow(writeUserDb: Boolean): PersistResult {
        var savedUserDbMtime: Long? = null
        var savedUserLearnMtime: Long? = null
        val userDbWritten = !writeUserDb || runCatching {
            model.save(userDb)
            savedUserDbMtime = userDb.lastModified()
        }.isSuccess
        val learningWritten = runCatching { savedUserLearnMtime = saveDirtyLearning() }.isSuccess
        if (savedUserDbMtime != null || savedUserLearnMtime != null) {
            onSaved(savedUserDbMtime, savedUserLearnMtime)
        }
        return PersistResult(dictionary = userDbWritten, learning = learningWritten)
    }

    private fun saveDirtyLearning(): Long? {
        val learning = userLearning ?: return null
        val file = userLearnFile ?: return null
        if (!learning.dirty) return null
        learning.save(file)
        return file.lastModified()
    }

    private data class PersistResult(val dictionary: Boolean, val learning: Boolean) {
        val both: Boolean get() = dictionary && learning

        companion object {
            val DONE = PersistResult(dictionary = true, learning = true)
            val FAILED = PersistResult(dictionary = false, learning = false)
        }
    }

    private companion object {
        const val WRITE_TIMEOUT_MILLIS = 5_000L
    }
}
