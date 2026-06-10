package com.kiberqalqon

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * v4 dizayn `Ring` komponenti (design_v4_extracted/icons.jsx → Ring):
 * dumaloq progress-halqa — orqada track, ustida rangli yoy (round cap),
 * markazda istalgan kontent (layoutda ustiga FrameLayout bilan qo'yiladi).
 *
 * Layout misol:
 *   <FrameLayout>
 *     <com.kiberqalqon.KqRingView android:id="@+id/ring" ... />
 *     <ImageView android:layout_gravity="center" ... />
 *   </FrameLayout>
 *
 * Kotlin:
 *   ring.setValue(100f)                       // 0..100, animatsiya bilan
 *   ring.ringColor = getColor(R.color.kq_safe)
 */
class KqRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Yoy qalinligi (px emas — dp'da beriladi, default dizayndagi 13). */
    var strokeWidthDp: Float = 13f
        set(v) { field = v; invalidate() }

    var ringColor: Int = context.getColor(R.color.kq_safe)
        set(v) { field = v; invalidate() }

    var trackColor: Int = context.getColor(R.color.kq_surface_2)
        set(v) { field = v; invalidate() }

    private var value: Float = 0f
    private var animator: ValueAnimator? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    /** 0..100. Dizayndagi 1.2s ease bilan animatsiya qiladi. */
    fun setValue(target: Float, animate: Boolean = true) {
        animator?.cancel()
        val clamped = target.coerceIn(0f, 100f)
        if (!animate) {
            value = clamped; invalidate(); return
        }
        animator = ValueAnimator.ofFloat(value, clamped).apply {
            duration = 1200
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { value = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sw = strokeWidthDp * resources.displayMetrics.density
        paint.strokeWidth = sw
        val half = sw / 2f
        rect.set(half, half, width - half, height - half)

        paint.color = trackColor
        canvas.drawArc(rect, 0f, 360f, false, paint)

        if (value > 0f) {
            paint.color = ringColor
            canvas.drawArc(rect, -90f, 360f * (value / 100f), false, paint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
