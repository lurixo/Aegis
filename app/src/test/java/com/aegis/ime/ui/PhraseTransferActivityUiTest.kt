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

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.aegis.ime.R
import com.aegis.ime.user.LiveUserData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhraseTransferActivityUiTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val phrases = File(ctx.filesDir, "phrases.txt")

    @After fun releaseTheLiveStore() {
        LiveUserData.clipboardHost = null
        phrases.delete()
    }

    private fun exportTo(target: Uri) {
        val activity = Robolectric.buildActivity(
            PhraseTransferActivity::class.java,
            Intent(ctx, PhraseTransferActivity::class.java).putExtra(PhraseTransferActivity.EXTRA_EXPORT, true),
        ).setup().get()
        val picked = shadowOf(activity).peekNextStartedActivityForResult()
        assertNotNull("precondition: the export must reach the document picker", picked)
        shadowOf(activity).receiveResult(picked.intent, Activity.RESULT_OK, Intent().setData(target))
    }

    @Test fun a_phrase_export_over_a_longer_file_leaves_none_of_the_old_one_behind() {
        phrases.writeText("C\t工作\nP\t已收到\n")
        val target = File(ctx.cacheDir, "phrases-over-a-longer-file.txt")
        target.parentFile?.mkdirs()
        target.writeBytes(ByteArray(200_000))
        val stale = target.length()

        exportTo(Uri.fromFile(target))

        assertEquals(
            "precondition: the export itself must go through",
            ctx.getString(R.string.phrase_transfer_toast_export_ok),
            ShadowToast.getTextOfLatestToast(),
        )
        assertTrue(
            "a phrase export must not leave the tail of a longer file behind, was ${target.length()} of $stale bytes",
            target.length() < stale,
        )
    }
}
