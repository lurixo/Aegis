package com.aegis.ime.dict

object Fuzzy {
    fun normalize(s: String): String {
        var r = s
        r = r.replace("zh", "z").replace("ch", "c").replace("sh", "s")
        r = r.replace("ang", "an").replace("eng", "en").replace("ing", "in")
        return r
    }
}
