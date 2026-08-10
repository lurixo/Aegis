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
import com.aegis.ime.user.LiveUserData
import java.io.File
import java.io.IOException

internal class RestoreJournal private constructor(
    private val filesDir: File,
    private val dir: File,
) {

    fun markDone() {
        AtomicFileSwap.write(File(dir, DONE), TAG, "")
    }

    fun discard() {
        dir.deleteRecursively()
    }

    fun canRollBack(): Boolean = File(dir, BEFORE).isDirectory

    fun rollBack(prefs: SharedPreferences) {
        if (!canRollBack()) {
            throw IOException("the record of what the device held before the restore is gone")
        }
        val before = File(dir, BEFORE)

        for (relativePath in FILE_TARGETS) {
            val live = File(filesDir, relativePath)
            val copy = File(before, relativePath)
            if (copy.isFile) {
                live.parentFile?.mkdirs()
                AtomicFileSwap.copy(copy, live, TAG)
            } else if (live.isFile && !live.delete()) {
                throw IOException("$relativePath could not be taken back off the device")
            }
        }

        putClipsBack(File(before, CLIPS))

        val settings = File(before, SETTINGS)
        if (settings.isFile) {
            val editor = prefs.edit().clear()
            for ((key, value) in PrefsCodec.decode(settings.readBytes())) PrefsCodec.put(editor, key, value)
            if (!editor.commit()) throw IOException("the settings from before the restore could not be put back")
        }

        discard()
    }

    private fun putClipsBack(copy: File) {
        val live = File(filesDir, CLIPS)
        if (!copy.isDirectory) {
            if (live.exists() && !live.deleteRecursively()) {
                throw IOException("$CLIPS could not be taken back off the device")
            }
            return
        }
        val staged = AtomicFileSwap.stagingFor(live, TAG)
        staged.deleteRecursively()
        if (runCatching { copy.copyRecursively(staged, overwrite = true) }.getOrNull() != true) {
            staged.deleteRecursively()
            throw IOException("the copy of $CLIPS taken before the restore could not be laid back down")
        }
        swapClipsIn(staged, live)
    }

    private fun swapClipsIn(staged: File, live: File) {
        if (!live.exists()) {
            if (staged.renameTo(live)) return
            staged.deleteRecursively()
            throw IOException("$CLIPS could not be put back")
        }
        val aside = File(staged.path + ASIDE)
        aside.deleteRecursively()
        if (!live.renameTo(aside)) {
            staged.deleteRecursively()
            throw IOException("$CLIPS could not be moved out of the way")
        }
        if (staged.renameTo(live)) {
            aside.deleteRecursively()
            return
        }
        aside.renameTo(live)
        staged.deleteRecursively()
        throw IOException("$CLIPS could not be put back")
    }

    companion object {

        private const val DIR = "restore_journal"
        private const val BEFORE = "before"
        private const val READY = "ready"
        private const val DONE = "done"
        private const val SETTINGS = "settings.bin"
        private const val CLIPS = "clips"
        private const val ASIDE = ".aside"
        private const val TAG = 0L

        private val FILE_TARGETS = BackupItem.entries.map { it.relativePath }

        fun open(filesDir: File, prefs: SharedPreferences): RestoreJournal {
            val dir = File(filesDir, DIR)
            dir.deleteRecursively()
            val opened = runCatching { capture(filesDir, dir, prefs) }
            if (opened.isFailure) {
                dir.deleteRecursively()
                throw opened.exceptionOrNull() ?: IOException("the restore journal could not be opened")
            }
            return RestoreJournal(filesDir, dir)
        }

        fun finishAnyInterrupted(filesDir: File, prefs: SharedPreferences): Boolean {
            if (LiveUserData.restoreInProgress) return false
            val dir = File(filesDir, DIR)
            if (!dir.isDirectory) return false
            val journal = RestoreJournal(filesDir, dir)
            if (File(dir, DONE).isFile || !File(dir, READY).isFile || !journal.canRollBack()) {
                journal.discard()
                return false
            }
            journal.rollBack(prefs)
            return true
        }

        private fun capture(filesDir: File, dir: File, prefs: SharedPreferences) {
            val before = File(dir, BEFORE)
            if (!before.mkdirs()) throw IOException("the restore journal could not be opened")
            for (relativePath in FILE_TARGETS) {
                val live = File(filesDir, relativePath)
                if (!live.isFile) continue
                val copy = File(before, relativePath)
                copy.parentFile?.mkdirs()
                live.copyTo(copy, overwrite = true)
            }
            val liveClips = File(filesDir, CLIPS)
            if (liveClips.isDirectory) liveClips.copyRecursively(File(before, CLIPS), overwrite = true)
            File(before, SETTINGS).writeBytes(PrefsCodec.encode(prefs.all))
            AtomicFileSwap.write(File(dir, READY), TAG, "")
        }
    }
}
