package com.aegis.tools

import java.io.File

/**
 * Builds the English dictionary (BinaryDict format) from a `word count` frequency list.
 * Key = the word's letters only, lowercased (so "dont" matches "don't"); value = the original
 * word; freq = count. Reuses the shared external-sort + binary writer.
 */
object EnBuilder {
    fun build(rawArgs: Array<String>) {
        val args = Args(rawArgs)
        val out = File(args.required("--out"))
        val input = File(args.positionals.first())

        val tmp = File.createTempFile("aegis-en-", ".tsv").apply { deleteOnExit() }
        val tmpSorted = File.createTempFile("aegis-en-sorted-", ".tsv").apply { deleteOnExit() }

        var words = 0L
        tmp.bufferedWriter().use { w ->
            input.bufferedReader().forEachLine { line ->
                val sp = line.indexOf(' ')
                if (sp <= 0) return@forEachLine
                val word = line.substring(0, sp).trim()
                val freq = line.substring(sp + 1).trim().toIntOrNull() ?: return@forEachLine
                val key = buildString { for (c in word.lowercase()) if (c in 'a'..'z') append(c) }
                if (key.isEmpty()) return@forEachLine
                w.write(key); w.write("\t"); w.write(word); w.write("\t"); w.write(freq.toString()); w.write("\n")
                words++
            }
        }
        externalSort(tmp, tmpSorted)
        val (numKeys, numEntries) = writeBinary(tmpSorted, out, Int.MAX_VALUE)
        println("wrote ${out.path}: keys=$numKeys entries=$numEntries from $words words")
    }
}
