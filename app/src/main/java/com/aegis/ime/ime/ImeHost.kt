package com.aegis.ime.ime

/** Editor operations the controller needs from the IME service (backed by InputConnection). */
interface ImeHost {
    fun commitText(text: CharSequence)
    fun deleteBackward()
    fun performEnter()
}
