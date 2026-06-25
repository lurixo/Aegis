package com.aegis.ime.user

import java.io.File
import kotlin.math.ln

class UserModel {
    private val count = HashMap<String, Int>()
    private val lastUsed = HashMap<String, Long>()
    private val bigram = HashMap<String, HashMap<String, Int>>()

    var dirty: Boolean = false
        private set

    fun record(prevWord: String?, word: String, now: Long) {
        if (word.isEmpty()) return
        count[word] = (count[word] ?: 0) + 1
        lastUsed[word] = now
        if (!prevWord.isNullOrEmpty()) {
            val m = bigram.getOrPut(prevWord) { HashMap() }
            m[word] = (m[word] ?: 0) + 1
        }
        dirty = true
    }

    fun wordBoost(word: String): Double {
        val c = count[word] ?: return 0.0
        return BOOST_WEIGHT * ln(1.0 + c)
    }

    fun successors(prevWord: String, limit: Int): List<String> {
        val m = bigram[prevWord] ?: return emptyList()
        return m.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    fun isEmpty(): Boolean = count.isEmpty()


    fun save(file: File) {
        file.bufferedWriter().use { w ->
            w.write("aegis-userdb 1\n")
            for ((word, c) in count) w.write("W\t$word\t$c\t${lastUsed[word] ?: 0}\n")
            for ((prev, m) in bigram) for ((word, c) in m) w.write("B\t$prev\t$word\t$c\n")
        }
        dirty = false
    }

    fun load(file: File) {
        if (!file.exists()) return
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                val p = line.split('\t')
                when {
                    p.size == 4 && p[0] == "W" -> {
                        val word = p[1]
                        count[word] = p[2].toIntOrNull() ?: continue
                        lastUsed[word] = p[3].toLongOrNull() ?: 0
                    }
                    p.size == 4 && p[0] == "B" ->
                        bigram.getOrPut(p[1]) { HashMap() }[p[2]] = p[3].toIntOrNull() ?: continue
                }
            }
        }
        dirty = false
    }

    fun importFrom(file: File, now: Long) {
        val other = UserModel().apply { load(file) }
        for ((word, c) in other.count) {
            count[word] = (count[word] ?: 0) + c
            lastUsed[word] = maxOf(lastUsed[word] ?: 0, other.lastUsed[word] ?: now)
        }
        for ((prev, m) in other.bigram) {
            val dst = bigram.getOrPut(prev) { HashMap() }
            for ((word, c) in m) dst[word] = (dst[word] ?: 0) + c
        }
        if (!other.isEmpty()) dirty = true
    }

    private companion object {
        const val BOOST_WEIGHT = 1.0
    }
}
