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
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BackupManifestTest {

    @Test fun backupActivity_is_a_private_standard_settings_page() {
        val activity = activityNamed(".ui.BackupActivity")
        assertEquals("false", activity.androidAttr("exported"))
        assertEquals("@style/Theme.Aegis", activity.androidAttr("theme"))
        assertEquals("@string/settings_backup_title", activity.androidAttr("label"))
        assertEquals("adjustResize", activity.androidAttr("windowSoftInputMode"))
    }

    @Test fun default_password_auth_declares_biometric_permission() {
        val manifest = parseXml("src/main/AndroidManifest.xml")
        val permissions = manifest.getElementsByTagName("uses-permission")
        var found = false
        for (i in 0 until permissions.length) {
            val permission = permissions.item(i) as Element
            found = found || permission.androidAttr("name") == "android.permission.USE_BIOMETRIC"
        }
        assertEquals(true, found)
    }

    private fun activityNamed(name: String): Element {
        val manifest = parseXml("src/main/AndroidManifest.xml")
        val activities = manifest.getElementsByTagName("activity")
        for (i in 0 until activities.length) {
            val activity = activities.item(i) as Element
            if (activity.androidAttr("name") == name) return activity
        }
        error("$name is missing from AndroidManifest.xml")
    }

    private fun parseXml(path: String): Document =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File(path))

    private fun Element.androidAttr(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)
}
