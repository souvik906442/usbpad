package com.example.usbpad

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/** Shared colourful "glass" look: gradient background, glowing orbs, translucent bordered panels. */
object Glass {
    val STROKE = 0x88FFFFFF.toInt()
    val STROKE_SOFT = 0x66FFFFFF
    val FILL = 0x30FFFFFF
    val GREEN = intArrayOf(0xFF11998E.toInt(), 0xFF38EF7D.toInt())
    val BLUE = intArrayOf(0xFF00C6FF.toInt(), 0xFF0072FF.toInt())
    val PURPLE = intArrayOf(0xFF8E2DE2.toInt(), 0xFF4A00E0.toInt())
    val PINK = intArrayOf(0xFFFF4D8D.toInt(), 0xFFC2185B.toInt())
    val ORANGE = intArrayOf(0xFFFF512F.toInt(), 0xFFF09819.toInt())
    val RED = intArrayOf(0xFFFF416C.toInt(), 0xFFFF4B2B.toInt())

    fun dp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    private fun strokePx(ctx: Context): Int = dp(ctx, 1f).toInt().coerceAtLeast(1)

    /** Translucent rounded panel with a light border. */
    fun panel(ctx: Context, radiusDp: Float = 18f, fill: Int = FILL, stroke: Int = STROKE_SOFT): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(ctx, radiusDp)
        g.setStroke(strokePx(ctx), stroke)
        return g
    }

    /** Glass button. [acc] = two gradient colours (tinted glass), null = plain glass, [on] = active (green). */
    fun button(ctx: Context, acc: IntArray?, on: Boolean): GradientDrawable {
        val g: GradientDrawable
        if (on) {
            g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, GREEN)
        } else if (acc != null) {
            val a = (acc[0] and 0xFFFFFF) or 0xCC000000.toInt()
            val b = (acc[1] and 0xFFFFFF) or 0xCC000000.toInt()
            g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(a, b))
        } else {
            g = GradientDrawable()
            g.setColor(FILL)
        }
        g.cornerRadius = dp(ctx, 16f)
        g.setStroke(strokePx(ctx), STROKE)
        return g
    }

    private fun orb(ctx: Context, color: Int, sizeDp: Float): View {
        val v = View(ctx)
        val g = GradientDrawable()
        g.gradientType = GradientDrawable.RADIAL_GRADIENT
        g.gradientRadius = dp(ctx, sizeDp) / 2f
        g.colors = intArrayOf((color and 0xFFFFFF) or 0xB0000000.toInt(), color and 0xFFFFFF)
        v.background = g
        return v
    }

    /** Wraps [content] in a gradient background with three glowing colour orbs behind it. */
    fun decorate(ctx: Context, content: View): FrameLayout {
        val f = FrameLayout(ctx)
        f.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF1A1446.toInt(), 0xFF5B1FA8.toInt(), 0xFF0B6FD6.toInt())
        )
        fun add(color: Int, size: Float, gravity: Int, mx: Float, my: Float) {
            val s = dp(ctx, size).toInt()
            val lp = FrameLayout.LayoutParams(s, s, gravity)
            lp.topMargin = dp(ctx, my).toInt()
            lp.leftMargin = dp(ctx, mx).toInt()
            lp.rightMargin = dp(ctx, mx).toInt()
            lp.bottomMargin = dp(ctx, my).toInt()
            f.addView(orb(ctx, color, size), lp)
        }
        add(0xFFFF4D8D.toInt(), 300f, Gravity.TOP or Gravity.END, -90f, -40f)
        add(0xFF00E5FF.toInt(), 320f, Gravity.CENTER_VERTICAL or Gravity.START, -140f, 0f)
        add(0xFFFFA000.toInt(), 280f, Gravity.BOTTOM or Gravity.END, -60f, -60f)
        f.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return f
    }
}
