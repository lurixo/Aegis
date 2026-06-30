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
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class PhraseTransferManifestTest {

    @Test fun phraseTransferActivity_allows_activity_result_callbacks() {
        val activity = phraseTransferActivity()

        assertEquals(
            "PhraseTransferActivity must survive SAF picker handoff so export/import callbacks can run",
            "",
            activity.androidAttr("noHistory"),
        )
        assertEquals("false", activity.androidAttr("exported"))
        assertEquals("true", activity.androidAttr("excludeFromRecents"))
    }

    private fun phraseTransferActivity(): Element {
        val manifest = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val activities = manifest.getElementsByTagName("activity")
        for (i in 0 until activities.length) {
            val activity = activities.item(i) as Element
            if (activity.androidAttr("name") == ".ui.PhraseTransferActivity") return activity
        }
        error("PhraseTransferActivity is missing from AndroidManifest.xml")
    }

    private fun Element.androidAttr(name: String): String = getAttributeNS(ANDROID_NS, name)

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
