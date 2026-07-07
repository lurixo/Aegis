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

object LiveUserData {
    @Volatile
    var onRestored: (() -> Unit)? = null

    @Volatile
    var onLexiconsRestored: (() -> Unit)? = null

    @Volatile
    var clipboardHost: ClipboardStore? = null

    private val clipboardPersistenceHookLock = Any()
    private var beforeExportHook: (() -> Unit)? = null
    private var beforeRestoreHook: (() -> Unit)? = null

    var onBeforeExport: (() -> Unit)?
        get() = synchronized(clipboardPersistenceHookLock) { beforeExportHook }
        set(value) = synchronized(clipboardPersistenceHookLock) { beforeExportHook = value }

    var onBeforeRestore: (() -> Unit)?
        get() = synchronized(clipboardPersistenceHookLock) { beforeRestoreHook }
        set(value) = synchronized(clipboardPersistenceHookLock) { beforeRestoreHook = value }

    internal fun flushBeforeExport() {
        val hook = synchronized(clipboardPersistenceHookLock) { beforeExportHook }
        hook?.invoke()
    }

    @Volatile
    var restoreInProgress: Boolean = false

    @Volatile
    var restoreTrouble: RestoreTrouble? = null
}

enum class RestoreTrouble {
    ROLLBACK_FAILED,
    ROLLBACK_IMPOSSIBLE,
}
