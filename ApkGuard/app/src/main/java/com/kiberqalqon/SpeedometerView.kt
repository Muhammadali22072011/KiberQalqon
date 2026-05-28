package com.kiberqalqon

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat

/**
 * Dashboard hero speedometer (270° gauge).
 *
 * Цветовая схема — kq palette per active accent (Anor/Feruz/Za'faron):
 *  - Background arc: kq_hairline (subtle ink line on cream / muted on navy)
 *  - Active arc + glow: kq_primary (current theme attribute resolves via state list)
 *  - Number: kq_ink (high contrast on theme bg)
 *  - "%" sign: kq_ink_2
 *  - Status label below: kq_ink_3
 *  - Ticks: kq_hairline_strong (passed), kq_hairline (remaining)
 *
 * Old code хардкодил cyber neon green/yellow/red на API 26+ выглядело инопланетно
 * на новом светлом дизайне. Свет/тёмный режим теперь работает корректно потому что
 * мы тянем цвета из ContextCompat.getColor() с current theme.
 */
class SpeedometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var protectionLevel = 0f
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val arcRect = RectF()
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    init {
        arcPaint.style = Paint.Style.STROKE
        arcPaint.strokeWidth = 26f
        arcPaint.strokeCap = Paint.Cap.ROUND

        glowPaint.style = Paint.Style.STROKE
        glowPaint.strokeWidth = 36f
        glowPaint.strokeCap = Paint.Cap.ROUND
        glowPaint.maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)

        tickPaint.style = Paint.Style.STROKE
        tickPaint.strokeWidth = 2f

        textPaint.textAlign = Paint.Align.CENTER
        subTextPaint.textAlign = Paint.Align.CENTER
        labelPaint.textAlign = Paint.Align.CENTER
    }

    fun setProtectionLevel(level: Int, animate: Boolean = true) {
        if (animate) {
            ValueAnimator.ofFloat(protectionLevel, level.toFloat()).apply {
                duration = 1200
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    protectionLevel = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            protectionLevel = level.toFloat()
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = (w.coerceAtMost(h) / 2f) - 30f

        arcRect.set(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius
        )
    }

    /** Theme-aware color resolution — resolves through current Activity theme so
     *  `?attr/kqPrimary` lands on whichever accent the user picked. */
    private fun themeColor(attrRes: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attrRes, tv, true)
        return tv.data
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val hairline = ContextCompat.getColor(context, R.color.kq_hairline)
        val hairlineStrong = ContextCompat.getColor(context, R.color.kq_hairline_strong)
        val ink = ContextCompat.getColor(context, R.color.kq_ink)
        val ink2 = ContextCompat.getColor(context, R.color.kq_ink_2)
        val ink3 = ContextCompat.getColor(context, R.color.kq_ink_3)
        val accent = themeColor(R.attr.kqPrimary)

        // Background track (subtle).
        arcPaint.color = hairline
        canvas.drawArc(arcRect, 135f, 270f, false, arcPaint)

        // Active arc with primary accent glow.
        val sweepAngle = (protectionLevel / 100f) * 270f
        glowPaint.color = accent
        canvas.drawArc(arcRect, 135f, sweepAngle, false, glowPaint)
        arcPaint.color = accent
        canvas.drawArc(arcRect, 135f, sweepAngle, false, arcPaint)

        // Ticks (10 evenly spaced — first half passed gets stronger ink).
        drawTicks(canvas, accent, hairlineStrong, hairline)

        // Big percentage number.
        textPaint.textSize = radius / 1.9f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.color = ink
        canvas.drawText(
            "${protectionLevel.toInt()}",
            centerX,
            centerY + textPaint.textSize / 3.2f,
            textPaint
        )

        // Tiny "%" suffix.
        subTextPaint.textSize = radius / 5.5f
        subTextPaint.typeface = Typeface.DEFAULT
        subTextPaint.color = ink2
        val percentX = centerX + textPaint.measureText(protectionLevel.toInt().toString()) / 2f + 8f
        canvas.drawText("%", percentX, centerY, subTextPaint)

        // Status label below the number.
        labelPaint.textSize = radius / 8f
        labelPaint.letterSpacing = 0.18f
        labelPaint.color = ink3
        canvas.drawText(
            getStatusText(protectionLevel.toInt()),
            centerX,
            centerY + radius / 1.8f,
            labelPaint
        )
    }

    private fun drawTicks(canvas: Canvas, accentColor: Int, passedColor: Int, remainingColor: Int) {
        val tickRadius = radius - 22f
        val tickEnd = radius - 34f
        for (i in 0..10) {
            val angle = Math.toRadians((135 + i * 27).toDouble())
            val startX = (centerX + tickRadius * Math.cos(angle)).toFloat()
            val startY = (centerY + tickRadius * Math.sin(angle)).toFloat()
            val endX = (centerX + tickEnd * Math.cos(angle)).toFloat()
            val endY = (centerY + tickEnd * Math.sin(angle)).toFloat()
            val isPassed = (i * 10) <= protectionLevel
            tickPaint.color = if (isPassed) accentColor else remainingColor
            tickPaint.strokeWidth = if (isPassed) 3f else 2f
            canvas.drawLine(startX, startY, endX, endY, tickPaint)
        }
        // suppress unused warning
        @Suppress("UNUSED_PARAMETER")
        val _unused = passedColor
    }

    private fun getStatusText(level: Int): String {
        return when {
            level >= 80 -> "FAOL"
            level >= 50 -> "O'RTACHA"
            else -> "PAST"
        }
    }
}
