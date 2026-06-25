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

package com.aegis.ime

import android.content.pm.ApplicationInfo
import android.content.res.XmlResourceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformBackupExclusionTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private val everyDomain = listOf(
        "root",
        "file",
        "database",
        "sharedpref",
        "external",
        "device_root",
        "device_file",
        "device_database",
        "device_sharedpref",
    )

    private data class Rule(val section: String, val tag: String, val domain: String?, val path: String?)

    private fun rules(): List<Rule> {
        val parser: XmlResourceParser = ctx.resources.getXml(R.xml.data_extraction_rules)
        val out = ArrayList<Rule>()
        var section = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "data-extraction-rules" -> Unit
                    "cloud-backup", "device-transfer" -> section = parser.name
                    else -> out.add(
                        Rule(
                            section,
                            parser.name,
                            parser.getAttributeValue(null, "domain"),
                            parser.getAttributeValue(null, "path"),
                        ),
                    )
                }
            }
            event = parser.next()
        }
        parser.close()
        return out
    }

    @Test fun the_app_opts_out_of_android_auto_backup_altogether() {
        assertEquals(
            "PRIVACY.md and both READMEs promise no cloud backup, which rides on this manifest flag",
            0,
            ctx.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP,
        )
    }

    @Test fun every_storage_domain_is_excluded_from_cloud_backup_and_device_transfer() {
        val parsed = rules()
        assertEquals(
            "the rules file must cover exactly the two extraction paths",
            setOf("cloud-backup", "device-transfer"),
            parsed.map { it.section }.toSet(),
        )
        for (section in listOf("cloud-backup", "device-transfer")) {
            val inSection = parsed.filter { it.section == section }
            assertEquals(
                "$section must exclude and never include, or the excluded domains would be reinstated",
                emptyList<String>(),
                inSection.filter { it.tag != "exclude" }.map { it.tag },
            )
            val excluded = inSection.filter { it.path == "." }.mapNotNull { it.domain }.toSet()
            for (domain in everyDomain) {
                assertTrue(
                    "$section leaves the $domain domain open: an exclude on root alone does not " +
                        "prune the traversal that starts at $domain, was $excluded",
                    domain in excluded,
                )
            }
        }
    }

    @Test fun every_store_the_privacy_statement_names_sits_in_an_excluded_domain() {
        val privacy = File("../PRIVACY.md").readText()
        val excluded = rules().filter { it.tag == "exclude" && it.path == "." }.mapNotNull { it.domain }.toSet()
        val stored = listOf(
            "filesDir/userlearn.txt",
            "filesDir/userdb.txt",
            "filesDir/clipboard.txt",
            "filesDir/phrases.txt",
            "filesDir/symbol_usage.txt",
            "filesDir/emoji/symbol_usage.txt",
        )
        for (path in stored) {
            assertTrue("PRIVACY.md must keep naming $path", privacy.contains(path))
            assertTrue("$path is named as living under filesDir", path.startsWith("filesDir/"))
        }
        assertTrue("filesDir is the file domain, which must be excluded, was $excluded", "file" in excluded)
        assertTrue(
            "the settings the statement mentions live in shared preferences, which must be excluded, was $excluded",
            "sharedpref" in excluded,
        )
    }
}
