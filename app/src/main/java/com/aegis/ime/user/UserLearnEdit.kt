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

object UserLearnEdit {

    fun list(userLearn: File): List<UserLearning.Formed> {
        UserDictHot.host?.let { return it.learnedEntries() }
        return loaded(userLearn).formedEntries()
    }

    fun remove(userLearn: File, word: String, reading: String) {
        UserDictHot.host?.let {
            it.removeLearned(word, reading)
            return
        }
        val learning = loaded(userLearn)
        learning.removeFormed(word, reading)
        if (learning.dirty) learning.save(userLearn)
    }

    fun clear(userLearn: File) {
        UserDictHot.host?.let {
            it.clearLearned()
            return
        }
        val learning = loaded(userLearn)
        learning.clear()
        if (learning.dirty) learning.save(userLearn)
    }

    private fun loaded(userLearn: File): UserLearning =
        UserLearning().apply { if (userLearn.exists()) load(userLearn) }
}
