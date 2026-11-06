package com.example.usbpad

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.max

/**
 * 1 finger move  = mouse move
 * 1 finger tap   = left click
 * 2 finger tap   = right click
 * 2 finger move  = scroll
 * tap, then touch again quickly and move = drag
 */
class TouchpadView(ctx: Context, private val send: (String) -> Unit) : View(ctx) {
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastTapUp = 0L
    private var moved = false
    private var dragging = false
    private var maxPointers = 0
    private var remX = 0f
    private var remY = 0f
    private var scrollAcc = 0f
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    var sensitivity = 1.8f
    var scrollStep = 14f   // pixels of two-finger travel per wheel notch

    init {
        setBackgroundColor(0xFF262626.toInt())
    }

    private fun avgX(e: MotionEvent, skip: Int): Float {
        var s = 0f
        var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            s += e.getX(i)
            n++
        }
        return if (n > 0) s / n else 0f
    }

    private fun avgY(e: MotionEvent, skip: Int): Float {
        var s = 0f
        var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            s += e.getY(i)
            n++
        }
        return if (n > 0) s / n else 0f
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                lastX = e.x; lastY = e.y
                downTime = e.eventTime
                moved = false
                maxPointers = 1
                remX = 0f; remY = 0f; scrollAcc = 0f
                if (e.eventTime - lastTapUp < 250) {
                    dragging = true
                    send("D L")
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = max(maxPointers, e.pointerCount)
                lastX = avgX(e, -1); lastY = avgY(e, -1)
                downX = lastX; downY = lastY
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastX = avgX(e, e.actionIndex)
                lastY = avgY(e, e.actionIndex)
            }
            MotionEvent.ACTION_MOVE -> {
                val x = avgX(e, -1)
                val y = avgY(e, -1)
                val dx = x - lastX
                val dy = y - lastY
                lastX = x; lastY = y
                if (!moved && hypot(x - downX, y - downY) > slop) moved = true
                if (e.pointerCount >= 2) {
                    scrollAcc += dy
                    val steps = (scrollAcc / scrollStep).toInt()
                    if (steps != 0) {
                        scrollAcc -= steps * scrollStep
                        send("W ${steps * 120}")
                    }
                } else if (maxPointers == 1) {
                    remX += dx * sensitivity
                    remY += dy * sensitivity
                    val ix = remX.toInt()
                    val iy = remY.toInt()
                    if (ix != 0 || iy != 0) {
                        remX -= ix; remY -= iy
                        send("M $ix $iy")
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    send("U L")
                    dragging = false
                } else if (!moved && e.eventTime - downTime < 250) {
                    if (maxPointers == 1) {
                        send("C L")
                        lastTapUp = e.eventTime
                    } else if (maxPointers == 2) {
                        send("C R")
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    send("U L")
                    dragging = false
                }
            }
        }
        return true
    }
}
