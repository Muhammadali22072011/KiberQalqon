package com.kiberqalqon

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

class StatsGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var data = listOf(0, 0, 0, 0, 0, 0, 0)
    private var animatedData = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f)
    // Uzbek day labels — Du(Mon)..Ya(Sun)
    private val labels = listOf("Du", "Se", "Cho", "Pa", "Ju", "Sha", "Ya")

    init {
        barPaint.style = Paint.Style.FILL

        glowPaint.style = Paint.Style.FILL
        glowPaint.maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)

        gridPaint.style = Paint.Style.STROKE
        gridPaint.strokeWidth = 1f
        gridPaint.color = Color.parseColor("#1E3A5F")
        gridPaint.pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 28f
        textPaint.color = Color.parseColor("#5A7290")
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

        valuePaint.textAlign = Paint.Align.CENTER
        valuePaint.textSize = 24f
        valuePaint.color = Color.parseColor("#48CAE4")
        valuePaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun setData(weekData: List<Int>, animate: Boolean = true) {
        data = weekData

        if (animate) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    val progress = animator.animatedValue as Float
                    animatedData = data.map { it * progress }
                    invalidate()
                }
                start()
            }
        } else {
            animatedData = data.map { it.toFloat() }
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (data.isEmpty()) return

        val maxValue = data.maxOrNull()?.toFloat()?.coerceAtLeast(1f) ?: 1f
        val barWidth = width / (data.size * 2f)
        val bottomPadding = 70f
        val topPadding = 30f
        val maxHeight = height - bottomPadding - topPadding

        // Сетка фоном (горизонтальные пунктирные линии)
        for (i in 0..3) {
            val y = topPadding + (maxHeight / 3f) * i
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
        }

        // Столбцы с cyber-glow
        animatedData.forEachIndexed { index, value ->
            val barHeight = if (maxValue > 0) (value / maxValue) * maxHeight else 0f
            val left = index * barWidth * 2 + barWidth / 2
            val top = height - bottomPadding - barHeight
            val right = left + barWidth
            val bottom = height - bottomPadding

            val gradient = LinearGradient(
                left, top, left, bottom,
                Color.parseColor("#00F5FF"),
                Color.parseColor("#0077B6"),
                Shader.TileMode.CLAMP
            )
            barPaint.shader = gradient

            val rect = RectF(left, top, right, bottom)

            // Glow за баром
            if (barHeight > 5f) {
                glowPaint.shader = gradient
                canvas.drawRoundRect(rect, 12f, 12f, glowPaint)
            }

            // Сам бар
            canvas.drawRoundRect(rect, 12f, 12f, barPaint)

            // Значение над баром
            if (data[index] > 0) {
                canvas.drawText(
                    data[index].toString(),
                    left + barWidth / 2,
                    top - 8f,
                    valuePaint
                )
            }

            // Подпись дня
            canvas.drawText(
                labels[index],
                left + barWidth / 2,
                height - 20f,
                textPaint
            )
        }

        barPaint.shader = null
        glowPaint.shader = null
    }
}
