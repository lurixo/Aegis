package com.aegis.ime.engine

class StubEngine : CandidateEngine {
    override fun candidates(composing: String, t9: Boolean): List<String> {
        if (composing.isEmpty()) return emptyList()
        return listOf(composing)
    }
}
