package com.aegis.ime.dict

/**
 * Fuzzy pinyin normalization — MUST stay identical to `tools/Pinyin.fuzzyNormalize` (the fuzzy
 * index is built with that function; queries are normalized with this one). Collapses the most
 * common confusions: 平翘舌 zh/ch/sh -> z/c/s and 前后鼻音 ang/eng/ing -> an/en/in.
 */
object Fuzzy {
    fun normalize(s: String): String {
        var r = s
        r = r.replace("zh", "z").replace("ch", "c").replace("sh", "s")
        r = r.replace("ang", "an").replace("eng", "en").replace("ing", "in")
        return r
    }
}
