package com.example.usbpad

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText

/**
 * Invisible 1px text field that owns the soft keyboard and forwards typing to the PC.
 *
 * It is a real EditText, so Gboard's tricky edits (Bengali "ami" -> composing "এ" -> "এম" -> "আমি",
 * replace, delete, re-compose) all land in an Editable. After every edit we compare the Editable
 * with [shadow] (what the PC already has) and send only the difference: backspaces + new text.
 */
class KeyCaptureView(ctx: Context, private val send: (String) -> Unit) : EditText(ctx) {

    /** 0 = Off (live typing, non-ASCII dropped), 1 = Alt+hex Unicode codes, 2 = InScript keys. */
    var bnMode = 0
    /** true (ADB / Wi-Fi PC mode): the PC server types real Unicode, so send the text untouched. */
    var native = false

    /** In these modes a word still being composed is NOT sent; it goes out when Gboard commits it. */
    private val hold: Boolean get() = native || bnMode != 0

    /** The text the PC has received so far. */
    private var shadow = ""

    init {
        background = null
        setPadding(0, 0, 0, 0)
        isCursorVisible = false
        setTextColor(0)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private fun composingRange(): IntRange? {
        val e = text ?: return null
        val a = BaseInputConnection.getComposingSpanStart(e)
        val b = BaseInputConnection.getComposingSpanEnd(e)
        return if (a >= 0 && b > a && b <= e.length) a until b else null
    }

    /** What the PC should contain now. */
    private fun target(): String {
        val s = text?.toString() ?: ""
        val r = composingRange() ?: return s
        return if (hold) s.removeRange(r.first, r.last + 1) else s
    }

    /** Number of backspaces the PC needs to delete this character again. */
    private fun cost(c: Char): Int = when {
        Character.isLowSurrogate(c) -> 0
        !native && bnMode == 2 -> maxOf(1, BengaliInscript.toKeys(c.toString()).length)
        else -> 1
    }

    private fun sync() {
        val t = target()
        if (t == shadow) return
        var p = 0
        val m = minOf(t.length, shadow.length)
        while (p < m && t[p] == shadow[p]) p++
        if (p > 0 && Character.isHighSurrogate(t[p - 1])) p--
        var back = 0
        for (i in p until shadow.length) back += cost(shadow[i])
        repeat(back) { send("K tap backspace") }
        typeText(t.substring(p))
        shadow = t
        // Keep the field small: start over when nothing is being composed.
        if (t.length > 150 && composingRange() == null) resetField()
    }

    private fun resetField() {
        text?.clear()
        shadow = ""
    }

    private fun typeText(t: String) {
        val parts = t.replace("\r", "").split("\n")
        parts.forEachIndexed { i, p ->
            if (i > 0) send("K tap enter")
            if (p.isEmpty()) return@forEachIndexed
            if (native) {
                send("T $p")
            } else if (bnMode == 1) {
                val ascii = StringBuilder()
                for (c in p) {
                    if (c.code < 128) {
                        ascii.append(c)
                    } else if (!Character.isSurrogate(c)) {
                        if (ascii.isNotEmpty()) { send("T $ascii"); ascii.setLength(0) }
                        send("X " + Integer.toHexString(c.code))
                    }
                }
                if (ascii.isNotEmpty()) send("T $ascii")
            } else if (bnMode == 2) {
                send("T " + BengaliInscript.toKeys(p))
            } else {
                send("T $p")
            }
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(base, true) {
            override fun setComposingText(t: CharSequence?, n: Int): Boolean =
                super.setComposingText(t, n).also { sync() }

            override fun setComposingRegion(start: Int, end: Int): Boolean =
                super.setComposingRegion(start, end).also { sync() }

            override fun commitText(t: CharSequence?, n: Int): Boolean =
                super.commitText(t, n).also { sync() }

            override fun finishComposingText(): Boolean =
                super.finishComposingText().also { sync() }

            override fun deleteSurroundingText(b: Int, a: Int): Boolean {
                val shadowEmpty = shadow.isEmpty() && (text?.isEmpty() != false)
                if (shadowEmpty) {  // nothing in the field: the user is deleting text that is on the PC
                    repeat(b) { send("K tap backspace") }
                    repeat(a) { send("K tap delete") }
                    return true
                }
                return super.deleteSurroundingText(b, a).also { sync() }
            }

            override fun deleteSurroundingTextInCodePoints(b: Int, a: Int): Boolean {
                val shadowEmpty = shadow.isEmpty() && (text?.isEmpty() != false)
                if (shadowEmpty) {
                    repeat(b) { send("K tap backspace") }
                    repeat(a) { send("K tap delete") }
                    return true
                }
                return super.deleteSurroundingTextInCodePoints(b, a).also { sync() }
            }
        }
    }

    override fun onEditorAction(actionCode: Int) {
        resetField()
        send("K tap enter")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val name = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
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
            resetField()
            send("K tap $name")
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DEL && shadow.isEmpty()) {
            send("K tap backspace"); return true
        }
        if (keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
            send("K tap delete"); return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        val r = super.onKeyDown(keyCode, event)
        sync()
        return r
    }
}
