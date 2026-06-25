package com.aegis.ime.engine

interface CandidateEngine {
    fun candidates(composing: String, t9: Boolean): List<String>
}
