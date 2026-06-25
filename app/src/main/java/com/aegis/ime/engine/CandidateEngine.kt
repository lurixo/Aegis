package com.aegis.ime.engine

interface CandidateEngine {
    fun candidates(composing: String, t9: Boolean): List<String>

    fun english(typed: String): List<String> = emptyList()

    fun predict(prevWord: String?): List<String> = emptyList()

    fun learn(prevWord: String?, word: String) {}
}
