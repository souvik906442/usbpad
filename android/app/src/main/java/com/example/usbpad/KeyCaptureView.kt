package com.example.usbpad

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/** Invisible 1px view that owns the soft keyboard and forwards typing to the PC. */
class KeyCaptureView(ctx: Context, private val send: (String) -> Unit) : View(ctx) {
    private var composing = ""

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    /** true: Bengali etc. is sent with Alt+hex codes (PC needs EnableHexNumpad); false: InScript keys. */
    var bnHex = true
    /** true (ADB mode): the PC server types real Unicode, so send the text untouched. */
    var native = false

    // How many key presses the current composing text produced (Bengali -> InScript keys).
    private var composingKeys = 0

    /** Sends the text (Bengali mapped to InScript keys); returns how many key presses it took. */
    private fun typeText(t: String): Int {
        var n = 0
        val parts = t.replace("\r", "").split("\n")
        parts.forEachIndexed { i, p ->
            if (i > 0) { send("K tap enter"); n++ }
            if (p.isEmpty()) return@forEachIndexed
            if (native) {
                send("T $p")
                n += p.length
            } else if (bnHex) {
                val ascii = StringBuilder()
                for (c in p) {
                    if (c.code < 128) {
                        ascii.append(c)
                    } else if (!Character.isSurrogate(c)) {
                        if (ascii.isNotEmpty()) { send("T $ascii"); n += ascii.length; ascii.setLength(0) }
                        send("X " + Integer.toHexString(c.code))
                        n++
                    }
                }
                if (ascii.isNotEmpty()) { send("T $ascii"); n += ascii.length }
            } else {
                val keys = BengaliInscript.toKeys(p)
                send("T $keys")
                n += keys.length
            }
        }
        return n
    }

    private fun replaceComposing(new: String) {
        repeat(composingKeys) { send("K tap backspace") }
        composingKeys = typeText(new)
        composing = new
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Plain text (not password) so Gboard allows other languages, e.g. Bengali via the globe key.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                replaceComposing(text.toString())
                return true
            }

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                replaceComposing(text.toString())
                composing = ""; composingKeys = 0
                return true
            }

            override fun finishComposingText(): Boolean {
                composing = ""; composingKeys = 0
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { send("K tap backspace") }
                repeat(afterLength) { send("K tap delete") }
                composing = ""; composingKeys = 0
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val name = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
            KeyEvent.KEYCODE_DEL -> "backspace"
            KeyEvent.KEYCODE_FORWARD_DEL -> "delete"
            KeyEvent.KEYCODE_TAB -> "tab"
            KeyEvent.KEYCODE_ESCAPE -> "esc"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_MOVE_HOME -> "home"
            KeyEvent.KEYCODE_MOVE_END -> "end"
            KeyEvent.KEYCODE_PAGE_UP -> "pgup"
            KeyEvent.KEYCODE_PAGE_DOWN -> "pgdn"
            else -> null
        }
        if (name != null) {
            composing = ""; composingKeys = 0
            send("K tap $name")
            return true
        }
        if (keyCode != KeyEvent.KEYCODE_BACK) {
            val u = event.unicodeChar
            if (u > 0 && !event.isCtrlPressed && !event.isAltPressed) {
                typeText(String(Character.toChars(u)))
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
