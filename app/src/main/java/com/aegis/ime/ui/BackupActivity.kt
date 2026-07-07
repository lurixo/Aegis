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

internal const val BACKUP_MIN_PASSWORD_LENGTH = 6

internal fun passwordProblem(password: String, confirm: String): Int? = when {
    password.isEmpty() -> R.string.backup_password_empty
    password.length < BACKUP_MIN_PASSWORD_LENGTH -> R.string.backup_password_too_short
    password != confirm -> R.string.backup_password_mismatch
    else -> null
}
