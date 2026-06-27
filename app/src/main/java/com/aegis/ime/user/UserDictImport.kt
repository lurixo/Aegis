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

/** Pure (JVM-testable) apply step for 学习词库 import, separated from the SAF/Compose plumbing. */
object UserDictImport {

    /**
     * Apply [importFile] onto [userDb] as a merge (counts accumulate) or 覆盖 (wholesale replace).
     *
     * Returns true on success. **Never silently wipes** [userDb]: if [importFile] is missing/empty,
     * or — for 覆盖 — parses to no entries, it returns false and leaves [userDb] untouched. (Merge
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
            if (incoming.isEmpty()) return false // 覆盖 with no entries would erase the dictionary
            incoming.save(userDb)
            true
        }
    }
}
