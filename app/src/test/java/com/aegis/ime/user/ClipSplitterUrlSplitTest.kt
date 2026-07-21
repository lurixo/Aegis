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

package com.aegis.ime.user

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipSplitterUrlSplitTest {

    @Test fun claude_artifact_url_splits_into_origin_and_path_segments() {
        assertEquals(
            listOf("https://claude.ai", "code", "artifact", "preview"),
            ClipSplitter.copyBlocks("https://claude.ai/code/artifact/preview"),
        )
    }

    @Test fun chatgpt_share_url_splits_into_origin_path_and_fragment() {
        assertEquals(
            listOf("https://share-chatgpt.openai.site", "g", "session", "#submit-post"),
            ClipSplitter.copyBlocks("https://share-chatgpt.openai.site/g/session/#submit-post"),
        )
    }

    @Test fun a_query_url_splits_path_segments_and_keeps_the_query_whole() {
        assertEquals(
            listOf("http://x.io", "a-b", "c2", "?x=10&y=z"),
            ClipSplitter.copyBlocks("http://x.io/a-b/c2?x=10&y=z"),
        )
    }

    @Test fun a_host_only_url_stays_a_single_block() {
        assertEquals(listOf("https://x.com"), ClipSplitter.copyBlocks("https://x.com"))
    }

    @Test fun surrounding_text_stays_separate_while_the_link_is_broken_up() {
        assertEquals(
            listOf("看", "这个", "https://claude.ai", "code", "artifact", "preview"),
            ClipSplitter.copyBlocks("看这个https://claude.ai/code/artifact/preview"),
        )
    }
}
