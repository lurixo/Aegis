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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppSelfUpdateAbsentTest {

    @Test
    fun manifestDoesNotRequestPackageInstallerPermission() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertFalse(manifest.contains("REQUEST_INSTALL_PACKAGES"))
        assertFalse(manifest.contains("android.permission.INSTALL_PACKAGES"))
    }

    @Test
    fun mainSourcesDoNotContainApkInstallWiring() {
        val forbidden = listOf(
            "PackageInstaller",
            "ACTION_INSTALL_PACKAGE",
            "ACTION_VIEW",
            "application/vnd.android.package-archive",
            "REQUEST_INSTALL_PACKAGES",
        )
        val allowedActionViewFiles = setOf(
            "src/main/java/com/aegis/ime/ui/ActivityExternalLink.kt",
        ).map { File(it).normalize().path }.toSet()

        val offenders = File("src/main").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "xml") }
            .flatMap { file ->
                val path = file.normalize().path
                forbidden.mapNotNull { term ->
                    if (term == "ACTION_VIEW" && path in allowedActionViewFiles) null
                    else if (file.readText().contains(term)) "$path contains $term"
                    else null
                }
            }
            .toList()

        assertTrue(offenders.joinToString(separator = "\n"), offenders.isEmpty())
    }
}
