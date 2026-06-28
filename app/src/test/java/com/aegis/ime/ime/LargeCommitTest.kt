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

package com.aegis.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeCommitTest {

    @Test fun million_char_text_chunks_reassemble_exactly_and_stay_under_the_limit() {
        val text = buildString { repeat(1_000_000) { append('a' + (it % 26)) } }
        val chunks = ArrayList<String>()
        LargeCommit.commit(text) { chunks.add(it.toString()) }
        assertEquals("no truncation: the chunks rejoin to the original", text, chunks.joinToString(""))
        assertTrue("every chunk is within the binder-safe size", chunks.all { it.length <= LargeCommit.CHUNK })
        assertEquals(
            "chunk count = ceil(len / CHUNK)",
            (text.length + LargeCommit.CHUNK - 1) / LargeCommit.CHUNK,
            chunks.size,
        )
    }

    @Test fun text_within_one_chunk_is_committed_whole() {
        val chunks = ArrayList<String>()
        LargeCommit.commit("hello") { chunks.add(it.toString()) }
        assertEquals(listOf("hello"), chunks)
    }

    @Test fun text_exactly_at_the_chunk_boundary_is_one_piece() {
        val text = "b".repeat(LargeCommit.CHUNK)
        val chunks = ArrayList<String>()
        LargeCommit.commit(text) { chunks.add(it.toString()) }
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0])
    }
}
