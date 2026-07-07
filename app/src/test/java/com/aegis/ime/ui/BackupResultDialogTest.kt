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

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.aegis.ime.R
import com.aegis.ime.backup.BackupError
import com.aegis.ime.backup.BackupException
import com.aegis.ime.backup.BackupItem
import com.aegis.ime.backup.BackupManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BackupResultDialogTest {

    @get:Rule val compose = createAndroidComposeRule<BackupActivity>()

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun zhString(name: String): String {
        val found = Regex("<string name=\"$name\">(.*?)</string>")
            .find(File("src/main/res/values-zh/strings.xml").readText())
        assertTrue("values-zh must define $name", found != null)
        return found!!.groupValues[1]
    }

    @Test fun a_backup_that_left_nothing_out_is_reported_as_a_plain_success() {
        val result = exportResult(BackupManager.ExportReport(emptySet()))
        assertEquals(R.string.backup_export_ok, result.messageRes)
        assertEquals(emptyList<Int>(), result.omittedRes)
    }

    @Test fun a_backup_that_left_something_out_carries_every_name_to_the_dialog() {
        val result = exportResult(
            BackupManager.ExportReport(setOf(BackupItem.LEARNING, BackupItem.EMOJI_USAGE)),
        )
        assertEquals(R.string.backup_export_ok_partial, result.messageRes)
        assertEquals(listOf(R.string.backup_item_learning, R.string.backup_item_emoji), result.omittedRes)
    }

    @Test fun every_name_the_dialog_can_show_is_one_the_page_already_taught_the_user() {
        val res = RuntimeEnvironment.getApplication().resources
        assertEquals(
            "the omission list must name the learned store the way the settings page names it",
            text(R.string.user_dict_auto_title),
            text(R.string.backup_item_learning),
        )
        assertEquals(zhString("user_dict_auto_title"), zhString("backup_item_learning"))

        val intro = text(R.string.backup_intro).lowercase()
        val introZh = zhString("backup_intro")
        for (item in BackupItem.entries) {
            val id = backupItemLabel(item)
            assertTrue(
                "${item.name} can show up in the omission list, so the introduction must say it is backed up",
                intro.contains(text(id).lowercase()),
            )
            assertTrue(
                "ZH introduction must name ${item.name} too",
                introZh.contains(zhString(res.getResourceEntryName(id))),
            )
        }
    }

    @Test fun an_export_that_did_not_happen_is_still_reported_as_a_failure() {
        val result = exportResult(null)
        assertEquals(R.string.backup_export_failed, result.messageRes)
        assertEquals(emptyList<Int>(), result.omittedRes)
    }

    @Test fun a_refused_restore_names_every_part_it_could_not_read() {
        val result = importResult(
            BackupException(
                BackupError.DAMAGED_CONTENT,
                items = setOf(BackupItem.DICTIONARY, BackupItem.LEARNING),
            ),
        )

        assertEquals(R.string.backup_error_damaged_content, result.messageRes)
        assertEquals(
            listOf(R.string.backup_item_dictionary, R.string.backup_item_learning),
            result.omittedRes,
        )
    }

    @Test fun every_other_restore_failure_is_still_reported_on_its_own_terms() {
        assertEquals(
            R.string.backup_error_wrong_password,
            importResult(BackupException(BackupError.WRONG_PASSWORD_OR_CORRUPT)).messageRes,
        )
        assertEquals(R.string.backup_error_io, importResult(BackupException(BackupError.IO_ERROR)).messageRes)
        assertEquals(
            R.string.backup_error_not_a_backup,
            importResult(BackupException(BackupError.NOT_A_BACKUP)).messageRes,
        )
        assertEquals(
            R.string.backup_error_unsupported,
            importResult(BackupException(BackupError.UNSUPPORTED_VERSION)).messageRes,
        )
        assertEquals(
            R.string.backup_error_already_restoring,
            importResult(BackupException(BackupError.ALREADY_RESTORING)).messageRes,
        )
        assertEquals(
            "a plain failure carries no list of parts",
            emptyList<Int>(),
            importResult(BackupException(BackupError.IO_ERROR)).omittedRes,
        )
        assertEquals(
            "every way a restore can fail must be reported as something of its own",
            BackupError.entries.size,
            BackupError.entries.map { importResult(BackupException(it)).messageRes }.toSet().size,
        )
    }

    @Test fun every_store_the_backup_can_leave_out_has_a_name_of_its_own() {
        val labels = BackupItem.entries.map(::backupItemLabel)
        assertEquals("no two stores may share a label", labels.size, labels.toSet().size)
        labels.forEach { assertTrue(text(it).isNotBlank()) }
    }
}
