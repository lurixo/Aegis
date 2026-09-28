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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppVersionCardTest {

    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun app_release_label_uses_android_visible_package_version() {
        val version = ctx.packageManager
            .getPackageInfo(ctx.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            .versionName!!

        assertEquals("Aegis v$version", appReleaseLabel(ctx))
    }

    @Test fun release_version_code_matches_the_application_candidate() {
        val gradle = File("build.gradle.kts").readText()

        assertTrue("release version code should match the application candidate", gradle.contains("versionCode = 9230"))
    }

    @Test fun app_release_label_resource_is_not_a_stale_hard_coded_version() {
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue("app release label should use a version format string", strings.contains("app_release_label_format"))
        assertFalse("old stale version label resource must be gone", strings.contains("app_release_label_value"))
    }
}
