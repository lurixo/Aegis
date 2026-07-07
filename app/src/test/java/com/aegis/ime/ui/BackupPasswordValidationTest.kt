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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupPasswordValidationTest {

    @Test fun accepts_a_matching_password_of_sufficient_length() {
        assertNull(passwordProblem("s3cret!", "s3cret!"))
        assertNull(passwordProblem("a".repeat(BACKUP_MIN_PASSWORD_LENGTH), "a".repeat(BACKUP_MIN_PASSWORD_LENGTH)))
    }

    @Test fun rejects_an_empty_password() {
        assertEquals(R.string.backup_password_empty, passwordProblem("", ""))
    }

    @Test fun rejects_a_too_short_password() {
        assertEquals(R.string.backup_password_too_short, passwordProblem("abc", "abc"))
    }

    @Test fun rejects_a_mismatched_confirmation() {
        assertEquals(R.string.backup_password_mismatch, passwordProblem("longenough", "longenouth"))
    }
}
