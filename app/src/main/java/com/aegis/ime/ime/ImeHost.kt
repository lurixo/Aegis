package com.aegis.ime.ime

interface ImeHost {
    fun commitText(text: CharSequence)
    fun deleteBackward()
    fun performEnter()
}
