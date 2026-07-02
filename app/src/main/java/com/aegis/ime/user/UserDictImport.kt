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

/** Chinese IME behavior note. */
object UserDictImport {

    /**
      * Chinese IME behavior note.
     *
     * Returns true on success. **Never silently wipes** [userDb]: if [importFile] is missing/empty,
      * Chinese IME behavior note.
     * loads the existing dict first, so it is wipe-safe even with a junk import.)
     */
    fun apply(importFile: File, userDb: File, merge: Boolean, now: Long): Boolean {
        if (!importFile.exists() || importFile.length() == 0L) return false
        return if (merge) {
            val target = UserModel().apply { if (userDb.exists()) load(userDb) }
            target.importFrom(importFile, now)
            target.save(userDb)
            true
        } else {
            val incoming = UserModel().apply { load(importFile) }
            if (incoming.isEmpty()) return false // Chinese IME behavior note.
            incoming.save(userDb)
            true
        }
    }
}
