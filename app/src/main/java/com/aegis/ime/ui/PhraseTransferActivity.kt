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

package com.aegis.ime.ui

import com.aegis.ime.R
import com.aegis.ime.user.UnreadablePhrasesException

internal fun phraseExportMessage(outcome: Result<Boolean>): Int = when {
    outcome.exceptionOrNull() is UnreadablePhrasesException ->
        R.string.phrase_transfer_toast_export_store_unreadable
    outcome.isFailure -> R.string.phrase_transfer_toast_export_write_failed
    outcome.getOrDefault(false) -> R.string.phrase_transfer_toast_export_ok
    else -> R.string.phrase_transfer_toast_export_empty
}

internal fun phraseImportMessage(outcome: Result<Boolean>, merge: Boolean): Int = when {
    outcome.exceptionOrNull() is UnreadablePhrasesException ->
        R.string.phrase_transfer_toast_import_store_unreadable
    outcome.isFailure -> R.string.phrase_transfer_toast_import_write_failed
    outcome.getOrDefault(false) ->
        if (merge) R.string.phrase_transfer_toast_import_merged
        else R.string.phrase_transfer_toast_import_overwritten
    else -> R.string.phrase_transfer_toast_import_invalid
}
