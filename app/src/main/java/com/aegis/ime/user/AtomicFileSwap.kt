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

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

internal object AtomicFileSwap {

    fun stagingFor(dest: File, tag: Long): File = File(dest.parentFile, "${dest.name}.$tag.tmp")

    fun write(dest: File, tag: Long, text: String) {
        val staged = stagingFor(dest, tag)
        stage(staged, dest) {
            FileOutputStream(staged).use { out ->
                out.write(text.toByteArray())
                out.fd.sync()
            }
        }
        replace(staged, dest)
    }

    fun copy(source: File, dest: File, tag: Long) {
        val staged = stagingFor(dest, tag)
        stage(staged, dest) {
            FileInputStream(source).use { input ->
                FileOutputStream(staged).use { out ->
                    input.copyTo(out)
                    out.fd.sync()
                }
            }
        }
        replace(staged, dest)
    }

    fun replace(staged: File, dest: File) {
        if (staged.renameTo(dest)) return
        if (!staged.isFile) throw IOException("the staged copy of ${dest.name} is gone")
        val kept = File(staged.path + ".kept")
        val movedAside = dest.isFile && dest.renameTo(kept)
        if (dest.exists()) {
            staged.delete()
            throw IOException("${dest.name} could not be moved out of the way")
        }
        if (staged.renameTo(dest)) {
            if (movedAside) kept.delete()
            return
        }
        val lost = movedAside && !kept.renameTo(dest)
        staged.delete()
        if (lost) throw IOException("${dest.name} is gone and what it held is kept as ${kept.name}")
        throw IOException("${dest.name} could not be replaced")
    }

    private fun stage(staged: File, dest: File, work: () -> Unit) {
        val done = runCatching(work)
        if (done.isFailure) {
            staged.delete()
            throw done.exceptionOrNull() ?: IOException("${dest.name} could not be staged")
        }
    }
}
