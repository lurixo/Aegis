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

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserDictCardTest {

    @Test fun learning_dictionary_card_no_longer_owns_the_app_version_label() {
        val source = File("src/main/java/com/aegis/ime/ui/UserDictCard.kt").readText()
        assertFalse("learning dictionary card must not read package versionName", source.contains("getPackageInfo"))
        assertFalse("learning dictionary card must not own the app version card", source.contains("AppVersionCard"))
        assertFalse("learning dictionary card must not own the app release label", source.contains("appReleaseLabel"))
        assertFalse("old footer helper must be gone", source.contains("currentAppVersionLabel"))
    }
}
