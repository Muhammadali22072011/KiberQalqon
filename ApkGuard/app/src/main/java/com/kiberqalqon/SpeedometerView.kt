package com.uzguard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat

/**
 * Dashboard hero ring — 2026-06 Anor redesign (brand/mockup.html .ring).
 *
 * Полное кольцо (старт сверху), прогресс = уровень защиты. Цветовой язык макета:
 * anor-красный — это БРЕНД (CTA, акценты), а цвет кольца — это СТАТУС:
 *  - >= 80  → kq_safe   (yashil — himoyalangan)
 *  - >= 50  → kq_warn   (sariq — qisman)
 *  - <  50  → kq_danger (qizil — xavf)
 * Внутри — диск (kq_bg_sunken) и щит с галочкой цвета статуса.
 * Track: kq_hairline. Цвета берутся из ресурсов текущей темы (light/dark).
 */
class SpeedometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var protectionLevel = 0f
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shieldFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shieldStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val arcRect = RectF()
    private val shieldPath = Path()
    private val checkPath = Path()
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    init {
        trackPaint.style = Paint.Style.STROKE
        trackPaint.strokeCap = Paint.Cap.ROUND

        arcPaint.style = Paint.Style.STROKE
        arcPaint.strokeCap = Paint.Cap.ROUND

        glowPaint.style = Paint.Style.STROKE
        glowPaint.strokeCap = Paint.Cap.ROUND
        glowPaint.maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)

        discPaint.style = Paint.Style.FILL

        shieldFillPaint.style = Paint.Style.FILL
        shieldStrokePaint.style = Paint.Style.STROKE
        shieldStrokePaint.strokeJoin = Paint.Join.ROUND

        checkPaint.style = Paint.Style.STROKE
        checkPaint.strokeCap = Paint.Cap.ROUND
        checkPaint.strokeJoin = Paint.Join.ROUND
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
        radius = (w.coerceAtMost(h) / 2f) - 18f

        arcRect.set(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius
        )

        val ringWidth = radius * 0.16f
        trackPaint.strokeWidth = ringWidth
        arcPaint.strokeWidth = ringWidth
        glowPaint.strokeWidth = ringWidth * 1.35f

        buildShieldPaths()
    }

    /**
     * Щит + галочка из макета (svg 24×24: M12 2 L20 5 V11 C20 16 16.5 19.5 12 21
     * C7.5 19.5 4 16 4 11 V5 Z; check M8.5 12 L11 14.5 L15.5 9.5), отскейлены
     * под внутренний диск.
     */
    private fun buildShieldPaths() {
        val s = radius * 0.052f // 24-юнитная сетка → ~62% диаметра диска
        val ox = centerX - 12f * s
        val oy = centerY - 12f * s

        shieldPath.reset()
        shieldPath.moveTo(ox + 12f * s, oy + 2f * s)
        shieldPath.lineTo(ox + 20f * s, oy + 5f * s)
        shieldPath.lineTo(ox + 20f * s, oy + 11f * s)
        shieldPath.cubicTo(
            ox + 20f * s, oy + 16f * s,
            ox + 16.5f * s, oy + 19.5f * s,
            ox + 12f * s, oy + 21f * s
        )
        shieldPath.cubicTo(
            ox + 7.5f * s, oy + 19.5f * s,
            ox + 4f * s, oy + 16f * s,
            ox + 4f * s, oy + 11f * s
        )
        shieldPath.lineTo(ox + 4f * s, oy + 5f * s)
        shieldPath.close()

        checkPath.reset()
        checkPath.moveTo(ox + 8.5f * s, oy + 12f * s)
        checkPath.lineTo(ox + 11f * s, oy + 14.5f * s)
        checkPath.lineTo(ox + 15.5f * s, oy + 9.5f * s)

        shieldStrokePaint.strokeWidth = 1.6f * s
        checkPaint.strokeWidth = 1.8f * s
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val hairline = ContextCompat.getColor(context, R.color.kq_hairline)
        val sunken = ContextCompat.getColor(context, R.color.kq_bg_sunken)
        val status = statusColor()

        // Track (остаток кольца).
        trackPaint.color = hairline
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)

        // Прогресс со свечением — старт сверху (-90°).
        val sweepAngle = (protectionLevel / 100f) * 360f
        glowPaint.color = status
        glowPaint.alpha = 110
        canvas.drawArc(arcRect, -90f, sweepAngle, false, glowPaint)
        arcPaint.color = status
        canvas.drawArc(arcRect, -90f, sweepAngle, false, arcPaint)

        // Внутренний диск (как .ring::before в макете).
        discPaint.color = sunken
        canvas.drawCircle(centerX, centerY, radius - arcPaint.strokeWidth * 0.9f, discPaint)

        // Щит + галочка цвета статуса.
        shieldFillPaint.color = status
        shieldFillPaint.alpha = 50
        canvas.drawPath(shieldPath, shieldFillPaint)
        shieldStrokePaint.color = status
        canvas.drawPath(shieldPath, shieldStrokePaint)
        checkPaint.color = status
        canvas.drawPath(checkPath, checkPaint)
    }

    private fun statusColor(): Int {
        val res = when {
            protectionLevel >= 80f -> R.color.kq_safe
            protectionLevel >= 50f -> R.color.kq_warn
            else -> R.color.kq_danger
        }
        return ContextCompat.getColor(context, res)
    }
}
