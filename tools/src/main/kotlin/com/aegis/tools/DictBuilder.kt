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

package com.aegis.tools

import java.io.File

internal fun externalSortByKeyWord(input: File, output: File) {
    val pb = ProcessBuilder("sort", "-t", "\t", "-k1,1", "-k2,2")
        .redirectInput(input).redirectOutput(output)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
    pb.environment()["LC_ALL"] = "C"
    check(pb.start().waitFor() == 0) { "sort (key,word) failed" }
}

internal fun mergeAdjacentDuplicates(input: File, output: File): Long {
    var folded = 0L
    input.bufferedReader().use { r ->
        output.bufferedWriter().use { w ->
            var curKey: String? = null
            var curWord: String? = null
            var untaggedMax = 0L
            var haveUntagged = false
            val perSource = HashMap<String, Long>()
            var rows = 0
            fun flush() {
                if (curKey != null) {
                    var f = if (haveUntagged) untaggedMax else 0L
                    for (v in perSource.values) f += v
                    w.write(curKey); w.write("\t"); w.write(curWord); w.write("\t")
                    w.write(f.coerceAtMost(Int.MAX_VALUE.toLong()).toString()); w.write("\n")
                    if (rows > 1) folded += rows - 1
                }
                untaggedMax = 0L; haveUntagged = false; perSource.clear(); rows = 0
            }
            while (true) {
                val line = r.readLine() ?: break
                val c = line.split("\t")
                if (c.size < 3) continue
                val key = c[0]; val word = c[1]
                val freq = c[2].toLongOrNull() ?: continue
                val src = c.getOrNull(3) ?: ""
                if (key != curKey || word != curWord) { flush(); curKey = key; curWord = word }
                rows++
                if (src.isEmpty()) { haveUntagged = true; if (freq > untaggedMax) untaggedMax = freq }
                else perSource.merge(src, freq, ::maxOf)
            }
            flush()
        }
    }
    return folded
}

internal fun externalSort(input: File, output: File) {
    val pb = ProcessBuilder("sort", "-t", "\t", "-k1,1", "-k3,3nr")
        .redirectInput(input)
        .redirectOutput(output)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
    pb.environment()["LC_ALL"] = "C"
    val code = pb.start().waitFor()
    check(code == 0) { "sort failed with exit code $code" }
}


internal fun java.io.OutputStream.writeLeInt(v: Int) {
    write(v and 0xFF); write((v ushr 8) and 0xFF); write((v ushr 16) and 0xFF); write((v ushr 24) and 0xFF)
}

internal fun java.io.OutputStream.writeLeLong(v: Long) {
    writeLeInt((v and 0xFFFFFFFFL).toInt()); writeLeInt((v ushr 32).toInt())
}

internal class Args(argv: Array<String>) {
    val positionals = ArrayList<String>()
    private val named = HashMap<String, String>()
    init {
        var i = 0
        while (i < argv.size) {
            val a = argv[i]
            if (a.startsWith("--")) { named[a] = argv.getOrElse(i + 1) { "" }; i += 2 }
            else { positionals.add(a); i += 1 }
        }
    }
    fun required(k: String) = named[k] ?: error("missing $k")
    fun optional(k: String) = named[k]
}
