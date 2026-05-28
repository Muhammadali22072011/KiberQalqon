package com.kiberqalqon

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Aylanib turuvchi radar — Initial scan paytida ko'rinadi.
 *
 * 3 ta konsentrik halqa + aylanuvchi yorug'lik nuri + skan qilingan APK'lar
 * uchun "ping" nuqtalari (random burchakda paydo bo'lib, asta-sekin so'nadi).
 *
 * - Sweep arm: ?attr/kqPrimary rangida 90° gradient, 360°/2.4s aylanadi.
 * - Pings: addPing() chaqirilganda yangi nuqta tushadi, 1.6s ichida fade out.
 * - Hit ping: addPing(isThreat=true) — qizil, kattaroq, danger uchun.
 *
 * Markazda matn yoki son ko'rsatish kerak emas — bu vazifani layoutdagi
 * ustki TextView bajaradi (radar view markazi shaffof).
 */
class RadarScanView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pingPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val arcRect = RectF()

    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var sweepAngle = 0f

    private val sweepAnim: ValueAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2400
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            sweepAngle = it.animatedValue as Float
            // Burchakdan o'tgan ping'larni "yorqin" qilamiz — radar arm tegsa.
            val now = System.currentTimeMillis()
            for (p in pings) {
                val delta = ((sweepAngle - p.angleDeg) + 540f) % 360f - 180f
                if (kotlin.math.abs(delta) < 6f && now - p.lastFlash > 800) {
                    p.lastFlash = now
                }
            }
            invalidate()
        }
    }

    private data class Ping(
        val angleDeg: Float,
        val distance: Float,
        val isThreat: Boolean,
        val createdAt: Long,
        var lastFlash: Long = 0L,
    )

    private val pings = ArrayDeque<Ping>()
    private val maxPings = 18

    init {
        ringPaint.color = ContextCompat.getColor(context, R.color.kq_hairline_strong)
        crossPaint.color = ContextCompat.getColor(context, R.color.kq_hairline)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!sweepAnim.isStarted) sweepAnim.start()
    }

    override fun onDetachedFromWindow() {
        sweepAnim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = min(w, h) / 2f - dp(6f)
        arcRect.set(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius,
        )
        // Sweep gradient — kq accent rang, alpha 0 → 60% (60° kenglikda nur).
        val accent = themeColor(R.attr.kqPrimary)
        val gradient = SweepGradient(
            centerX, centerY,
            intArrayOf(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                accent and 0x00FFFFFF or 0x55000000,
                accent and 0x00FFFFFF or 0x88000000.toInt(),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.78f, 0.92f, 0.99f, 1f),
        )
        sweepPaint.shader = gradient
    }

    private fun themeColor(attrRes: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attrRes, tv, true)
        return tv.data
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 3 ta konsentrik halqa.
        for (frac in floatArrayOf(0.36f, 0.66f, 1.0f)) {
            canvas.drawCircle(centerX, centerY, radius * frac, ringPaint)
        }

        // Krestsimon ko'rsatkichlar.
        canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, crossPaint)
        canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, crossPaint)

        // Radar arm (sweep gradient bilan).
        canvas.save()
        canvas.rotate(sweepAngle - 90f, centerX, centerY)
        canvas.drawArc(arcRect, 0f, 360f, true, sweepPaint)
        canvas.restore()

        // Ping nuqtalar.
        val now = System.currentTimeMillis()
        val accent = themeColor(R.attr.kqPrimary)
        val danger = ContextCompat.getColor(context, R.color.kq_danger)
        val iter = pings.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            val age = now - p.createdAt
            val lifeFrac = age / PING_LIFE_MS.toFloat()
            if (lifeFrac >= 1f) {
                iter.remove()
                continue
            }
            val rad = Math.toRadians(p.angleDeg.toDouble())
            val x = centerX + (radius * p.distance) * cos(rad).toFloat()
            val y = centerY + (radius * p.distance) * sin(rad).toFloat()

            // Asosiy fade alpha + radar arm yaqinida qisqa flash kuchaytirgich.
            val baseAlpha = (255 * (1f - lifeFrac)).toInt().coerceIn(0, 255)
            val flashAge = now - p.lastFlash
            val flashBoost = if (flashAge < 350) ((350 - flashAge) / 350f) * 110f else 0f
            val alpha = (baseAlpha + flashBoost).toInt().coerceIn(0, 255)

            val color = if (p.isThreat) danger else accent
            pingPaint.color = (color and 0x00FFFFFF) or (alpha shl 24)
            pingPaint.style = Paint.Style.FILL
            val size = if (p.isThreat) dp(4f) else dp(2.5f)
            canvas.drawCircle(x, y, size, pingPaint)

            // Threat'lar uchun yana halqa — diqqatni jalb qiladi.
            if (p.isThreat) {
                pingPaint.style = Paint.Style.STROKE
                pingPaint.strokeWidth = 2f
                pingPaint.color = (danger and 0x00FFFFFF) or ((alpha / 2) shl 24)
                canvas.drawCircle(x, y, size + dp(3f) * (1f - lifeFrac) + dp(2f), pingPaint)
            }
        }
    }

    /** Yangi APK skanerlanganda chaqiriladi — radar'ga "topildi" nuqtasi qo'shadi. */
    fun addPing(isThreat: Boolean = false) {
        val angle = Random.nextFloat() * 360f
        val dist = 0.18f + Random.nextFloat() * 0.78f
        if (pings.size >= maxPings) pings.removeFirst()
        pings.addLast(
            Ping(
                angleDeg = angle,
                distance = dist,
                isThreat = isThreat,
                createdAt = System.currentTimeMillis(),
            ),
        )
        invalidate()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        private const val PING_LIFE_MS = 1600L
    }
}
