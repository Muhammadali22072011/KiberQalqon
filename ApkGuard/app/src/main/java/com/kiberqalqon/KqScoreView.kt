package com.uzguard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Xavfsizlik bali indikatori — dizayn v2 (brand/mockup2.html, .gauge).
 *
 * SpeedometerView'dagi yaxlit halqa o'rniga 48 ta alohida "tick"dan iborat
 * 240° segmentli yoy: score foiziga teng qismi status rangida yonadi
 * (>=80 yashil, >=50 sariq, <50 qizil), qolgani xira hairline.
 * Markazga raqam layout'da TextView bilan qo'yiladi (view faqat yoyni chizadi).
 */
class KqScoreView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private companion object {
        const val TICKS = 48
        const val START_DEG = 150f   // chap-past
        const val SWEEP_DEG = 240f   // pastki o'rta ochiq qoladi
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var displayed = 0f
    private var target = 0
    private var animator: ValueAnimator? = null

    fun setScore(score: Int, animate: Boolean = true) {
        target = score.coerceIn(0, 100)
        animator?.cancel()
        if (!animate) {
            displayed = target.toFloat()
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(displayed, target.toFloat()).apply {
            duration = 900
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                displayed = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** DashboardNewActivity'dagi status ranglariga 1:1 mos porog. */
    private fun activeColor(): Int = context.getColor(
        when {
            target >= 80 -> R.color.kq_safe
            target >= 50 -> R.color.kq_warn
            else -> R.color.kq_danger
        }
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val cx = w / 2f
        val cy = h / 2f
        val half = min(w, h) / 2f
        val tickLen = half * 0.20f
        val rOut = half - half * 0.04f
        val rIn = rOut - tickLen
        paint.strokeWidth = half * 0.052f

        val on = activeColor()
        val off = context.getColor(R.color.kq_hairline)

        for (i in 0 until TICKS) {
            val frac = i.toFloat() / (TICKS - 1)
            val deg = START_DEG + SWEEP_DEG * frac
            val rad = Math.toRadians(deg.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            paint.color = if (frac * 100f <= displayed && displayed > 0f) on else off
            canvas.drawLine(cx + c * rIn, cy + s * rIn, cx + c * rOut, cy + s * rOut, paint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
