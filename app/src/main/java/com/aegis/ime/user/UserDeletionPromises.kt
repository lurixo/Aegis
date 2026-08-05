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

object UserDeletionPromises {

    fun keep(model: UserModel, userDb: File, learning: UserLearning, userLearn: File): Boolean {
        val owed = model.tombstones()
        if (owed.isEmpty() || !learning.readable) return false
        for ((word, reading) in owed) {
            if (reading.isEmpty()) learning.removeWord(word) else learning.removeFormed(word, reading)
        }
        var wrote = false
        if (learning.dirty) {
            if (!runCatching { learning.save(userLearn) }.isSuccess) return false
            wrote = true
        }
        model.dropTombstones(owed)
        return runCatching { model.save(userDb) }.isSuccess || wrote
    }
}
