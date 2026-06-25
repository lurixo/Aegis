package com.aegis.ime.engine

interface CandidateEngine {
    fun candidates(composing: String, t9: Boolean): List<String>

    fun predict(prevWord: String?): List<String> = emptyList()

    fun learn(prevWord: String?, word: String) {}
}
