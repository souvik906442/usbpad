package com.example.usbpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

private val PAD_WHITE = 0xFFE0E0E0.toInt()

/**
 * On-screen gamepad (two sticks, D-pad, A/B/X/Y, LB/RB, LT/RT, Back/Start/Guide/Share, L3/R3).
 * Drawn on a fixed 2400 x 1080 canvas that is scaled to fit. Multi-touch.
 * Every change is sent as one line:  G <buttons> <hat> <lx> <ly> <rx> <ry> <lt> <rt>
 *   buttons = bit mask (bit0 A, 1 B, 2 X, 3 Y, 4 LB, 5 RB, 6 Back, 7 Start, 8 L3, 9 R3, 10 Guide, 11 Share)
 *   hat     = 0..7 clockwise from up, 8 = centered;  sticks 0..255 (128 = centre);  triggers 0 or 255
 */
class GamepadView(ctx: Context, private val send: (String) -> Unit) : View(ctx) {

    /** round: x,y = centre, w = radius.  rect: x,y = top-left, w,h = size. */
    private class Btn(
        val label: String, val round: Boolean,
        val x: Float, val y: Float, val w: Float, val h: Float,
        val bit: Int, val trigger: Int, val color: Int
    )

    private val btns = listOf(
        Btn("A", true, 2026f, 422f, 60f, 0f, 0, 0, 0xFF66BB6A.toInt()),
        Btn("B", true, 2131f, 317f, 60f, 0f, 1, 0, 0xFFEF5350.toInt()),
        Btn("X", true, 1921f, 317f, 60f, 0f, 2, 0, 0xFF42A5F5.toInt()),
        Btn("Y", true, 2026f, 212f, 60f, 0f, 3, 0, 0xFFFFCA28.toInt()),
        Btn("LB", false, 756f, 278f, 310f, 82f, 4, 0, PAD_WHITE),
        Btn("RB", false, 1337f, 278f, 310f, 82f, 5, 0, PAD_WHITE),
        Btn("LT", false, 756f, 140f, 310f, 120f, -1, 1, PAD_WHITE),
        Btn("RT", false, 1337f, 140f, 310f, 120f, -1, 2, PAD_WHITE),
        Btn("BACK", true, 889f, 598f, 42f, 0f, 6, 0, PAD_WHITE),
        Btn("START", true, 1512f, 598f, 42f, 0f, 7, 0, PAD_WHITE),
        Btn("GUIDE", true, 1201f, 460f, 65f, 0f, 10, 0, PAD_WHITE),
        Btn("SHARE", true, 1201f, 598f, 42f, 0f, 11, 0, PAD_WHITE),
        Btn("L3", true, 247f, 213f, 38f, 0f, 8, 0, PAD_WHITE),
        Btn("R3", true, 1780f, 577f, 38f, 0f, 9, 0, PAD_WHITE)
    )

    private val lsX0 = 375f
    private val lsY0 = 342f
    private val rsX0 = 1907f
    private val rsY0 = 705f
    private val stickR = 125f
    private val dpX = 460f
    private val dpY = 728f

    // pointer id -> control: 0 left stick, 1 right stick, 2 D-pad, 100+i = button i
    private val owner = HashMap<Int, Int>()
    private val pressed = BooleanArray(btns.size)
    private var lsDx = 0f
    private var lsDy = 0f
    private var rsDx = 0f
    private var rsDy = 0f
    private var hat = 8
    private var lastLine = ""

    private var sc = 1f
    private var offX = 0f
    private var offY = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        sc = min(w / 2400f, h / 1080f)
        offX = (w - 2400f * sc) / 2f
        offY = (h - 1080f * sc) / 2f
    }

    private fun vx(x: Float) = (x - offX) / sc
    private fun vy(y: Float) = (y - offY) / sc

    private fun hit(x: Float, y: Float): Int {
        for (i in btns.indices) {
            val b = btns[i]
            if (b.round) {
                if (hypot(x - b.x, y - b.y) <= b.w * 1.3f) return 100 + i
            } else if (x >= b.x - 15f && x <= b.x + b.w + 15f && y >= b.y - 15f && y <= b.y + b.h + 15f) {
                return 100 + i
            }
        }
        if (hypot(x - lsX0, y - lsY0) < 150f) return 0
        if (hypot(x - rsX0, y - rsY0) < 150f) return 1
        if (abs(x - dpX) < 175f && abs(y - dpY) < 175f) return 2
        return -1
    }

    private fun axis(d: Float): Int = (128f + d / stickR * 127f).roundToInt().coerceIn(0, 255)

    private fun update(e: MotionEvent) {
        var mask = 0
        var h = 8
        var lx = 128
        var ly = 128
        var rx = 128
        var ry = 128
        var lt = 0
        var rt = 0
        java.util.Arrays.fill(pressed, false)
        lsDx = 0f; lsDy = 0f; rsDx = 0f; rsDy = 0f

        for (i in 0 until e.pointerCount) {
            val ctl = owner[e.getPointerId(i)] ?: continue
            val x = vx(e.getX(i))
            val y = vy(e.getY(i))
            if (ctl == 0 || ctl == 1) {
                val cx = if (ctl == 0) lsX0 else rsX0
                val cy = if (ctl == 0) lsY0 else rsY0
                var dx = x - cx
                var dy = y - cy
                val len = hypot(dx, dy)
                if (len > stickR) {
                    dx = dx * stickR / len
                    dy = dy * stickR / len
                }
                if (ctl == 0) { lx = axis(dx); ly = axis(dy); lsDx = dx; lsDy = dy }
                else { rx = axis(dx); ry = axis(dy); rsDx = dx; rsDy = dy }
            } else if (ctl == 2) {
                val dx = x - dpX
                val dy = y - dpY
                if (hypot(dx, dy) >= 45f) {
                    val ang = Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())) + 360.0
                    h = (((ang + 22.5) / 45.0).toInt()) % 8
                }
            } else if (ctl >= 100) {
                val b = btns[ctl - 100]
                pressed[ctl - 100] = true
                if (b.bit >= 0) mask = mask or (1 shl b.bit)
                if (b.trigger == 1) lt = 255
                if (b.trigger == 2) rt = 255
            }
        }
        hat = h
        val line = "G $mask $h $lx $ly $rx $ry $lt $rt"
        if (line != lastLine) {
            lastLine = line
            send(line)
        }
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                val c = hit(vx(e.getX(idx)), vy(e.getY(idx)))
                if (c >= 0) {
                    owner[e.getPointerId(idx)] = c
                    if (c >= 100) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> owner.remove(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> owner.clear()
        }
        update(e)
        return true
    }

    // ---------- drawing ----------

    private fun drawStick(c: Canvas, cx: Float, cy: Float, dx: Float, dy: Float) {
        fill.color = 0xFF1F1F21.toInt(); stroke.color = 0xFF3C3C40.toInt()
        c.drawCircle(cx, cy, stickR, fill)
        c.drawCircle(cx, cy, stickR, stroke)
        fill.color = 0xFF2E2E31.toInt()
        c.drawCircle(cx + dx * 0.55f, cy + dy * 0.55f, 62f, fill)
        c.drawCircle(cx + dx * 0.55f, cy + dy * 0.55f, 62f, stroke)
    }

    private fun drawDpad(c: Canvas) {
        fill.color = 0xFF333336.toInt(); stroke.color = 0xFF1A1A1C.toInt()
        rect.set(dpX - 145f, dpY - 48f, dpX + 145f, dpY + 48f)
        c.drawRoundRect(rect, 22f, 22f, fill); c.drawRoundRect(rect, 22f, 22f, stroke)
        rect.set(dpX - 48f, dpY - 145f, dpX + 48f, dpY + 145f)
        c.drawRoundRect(rect, 22f, 22f, fill); c.drawRoundRect(rect, 22f, 22f, stroke)
        fill.color = 0xFF5A5A60.toInt()
        if (hat == 7 || hat == 0 || hat == 1) { rect.set(dpX - 48f, dpY - 145f, dpX + 48f, dpY - 30f); c.drawRoundRect(rect, 22f, 22f, fill) }
        if (hat in 1..3) { rect.set(dpX + 30f, dpY - 48f, dpX + 145f, dpY + 48f); c.drawRoundRect(rect, 22f, 22f, fill) }
        if (hat in 3..5) { rect.set(dpX - 48f, dpY + 30f, dpX + 48f, dpY + 145f); c.drawRoundRect(rect, 22f, 22f, fill) }
        if (hat == 5 || hat == 6 || hat == 7) { rect.set(dpX - 145f, dpY - 48f, dpX - 30f, dpY + 48f); c.drawRoundRect(rect, 22f, 22f, fill) }
        fill.color = 0xFF222224.toInt()
        c.drawCircle(dpX, dpY, 50f, fill)
        text.color = 0xFF8A8A90.toInt(); text.textSize = 40f
        c.drawText("▲", dpX, dpY - 82f + 14f, text)
        c.drawText("▼", dpX, dpY + 82f + 14f, text)
        c.drawText("◀", dpX - 92f, dpY + 14f, text)
        c.drawText("▶", dpX + 92f, dpY + 14f, text)
    }

    private fun drawBtn(c: Canvas, b: Btn, down: Boolean) {
        fill.color = if (down) 0xFF5A5A60.toInt() else 0xFF333336.toInt()
        stroke.color = 0xFF1A1A1C.toInt()
        val big = b.label.length == 1
        text.color = b.color
        if (b.round) {
            c.drawCircle(b.x, b.y, b.w, fill)
            c.drawCircle(b.x, b.y, b.w, stroke)
            text.textSize = if (big) 56f else if (b.w > 50f) 24f else 22f
            c.drawText(b.label, b.x, b.y + text.textSize * 0.35f, text)
        } else {
            rect.set(b.x, b.y, b.x + b.w, b.y + b.h)
            c.drawRoundRect(rect, 30f, 30f, fill)
            c.drawRoundRect(rect, 30f, 30f, stroke)
            text.textSize = 44f
            c.drawText(b.label, b.x + b.w / 2f, b.y + b.h / 2f + 15f, text)
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF262628.toInt())
        c.save()
        c.translate(offX, offY)
        c.scale(sc, sc)
        drawStick(c, lsX0, lsY0, lsDx, lsDy)
        drawStick(c, rsX0, rsY0, rsDx, rsDy)
        drawDpad(c)
        for (i in btns.indices) drawBtn(c, btns[i], pressed[i])
        c.restore()
    }
}
