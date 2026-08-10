package com.foldmessenger.app

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Background for the message card: a deep navy body with the blue glow held
 * tight to the left and right edges and a soft purple bloom along the top.
 *
 * Drawn in code rather than as a <gradient> shape because the design needs four
 * colour stops across the width — XML shapes only allow three, which smears the
 * blue across the whole card.
 *
 * If you have the designer's artwork, drop it in as
 * res/drawable-nodpi/bg_card.png and set the card background to
 * R.drawable.bg_card in MainActivity.applyCardCorners() instead.
 */
class CardBackground(private val radius: Float) : Drawable() {

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onBoundsChange(bounds: Rect) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return

        bodyPaint.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(EDGE_BLUE, BODY, BODY, EDGE_BLUE_R),
            floatArrayOf(0f, 0.34f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )
        glowPaint.shader = RadialGradient(
            w / 2f, 0f, maxOf(w, h) * 0.62f,
            intArrayOf(TOP_GLOW, TOP_GLOW_OUT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        val r = RectF(bounds)
        canvas.drawRoundRect(r, radius, radius, bodyPaint)
        canvas.drawRoundRect(r, radius, radius, glowPaint)
    }

    override fun setAlpha(alpha: Int) {
        bodyPaint.alpha = alpha
        glowPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bodyPaint.colorFilter = colorFilter
        glowPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val EDGE_BLUE = 0xFF2C49F0.toInt()
        const val EDGE_BLUE_R = 0xFF2440E4.toInt()
        const val BODY = 0xFF16092F.toInt()
        const val TOP_GLOW = 0x704C2BB4
        const val TOP_GLOW_OUT = 0x0016092F
    }
}
