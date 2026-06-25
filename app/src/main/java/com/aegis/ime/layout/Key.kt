package com.aegis.ime.layout

enum class Lang { CN, EN }

enum class LayoutId { ALPHA, NINE, NUMBER, SYMBOL }

enum class KeyAction {
    COMMIT,
    BACKSPACE,
    ENTER,
    SHIFT,
    SPACE,
    SWITCH_SYMBOLS,
    SWITCH_NUMBERS,
    SWITCH_ALPHA,
    SWITCH_NINE,
    TOGGLE_LANG,
}

data class Key(
    val label: String,
    val output: String = label,
    val action: KeyAction = KeyAction.COMMIT,
    val sub: String? = null,
    val weight: Float = 1f,
)

data class KeyboardRow(val keys: List<Key>)

data class KeyboardLayout(val id: LayoutId, val rows: List<KeyboardRow>)
