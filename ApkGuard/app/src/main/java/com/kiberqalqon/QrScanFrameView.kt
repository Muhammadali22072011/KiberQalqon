package com.uzguard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat

/**
 * QR skan ramkasi — KAMERA USTIDA shaffof oyna + 4 burchak qavs (anor rangida).
 *
 * Avval `kq4_card_selected` (to'liq primary-soft FON) ishlatilardi → kamera ko'rinmay,
 * markazda to'q kvadrat chiqardi (foydalanuvchi: "kubik hech narsa ko'rsatmaydi").
 * Bu View esa FAQAT burchaklarni chizadi — markaz shaffof, QR kadr aniq ko'rinadi.
 *
 * Rang — joriy aksent (?attr/kqPrimary), tema o'zgarsa avtomatik moslashadi.
 */
class QrScanFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    // Burchak qavs uzunligi va chiziq qalinligi (dp → px).
    private val armLen = 34f * density
    private val strokeW = 4f * density
    private val radius = 18f * density

    private val accentColor: Int = run {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(R.attr.kqPrimary, tv, true)) {
            if (tv.resourceId != 0) ContextCompat.getColor(context, tv.resourceId) else tv.data
        } else {
            ContextCompat.getColor(context, R.color.kq_primary)
        }
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeW
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = accentColor
    }

    // Markazni biroz qoraytirib, ramka ichini ajratib ko'rsatuvchi yengil hoshiya (ixtiyoriy).
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = (accentColor and 0x00FFFFFF) or 0x33000000  // shaffof-anor nozik chegara
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = strokeW / 2f
        val l = inset
        val t = inset
        val r = width - inset
        val b = height - inset

        // Ichki nozik to'rtburchak chegara (kamera ustida juda yengil).
        canvas.drawRoundRect(l, t, r, b, radius, radius, edgePaint)

        // 4 burchak qavs — har biri ikki yoy/chiziqdan: L shaklida, burchakda yumaloq.
        // Yuqori-chap
        corner(canvas, l, t, +1f, +1f)
        // Yuqori-o'ng
        corner(canvas, r, t, -1f, +1f)
        // Past-chap
        corner(canvas, l, b, +1f, -1f)
        // Past-o'ng
        corner(canvas, r, b, -1f, -1f)
    }

    private val cornerPath = Path()

    /**
     * Bitta burchak qavsini chizadi: (x,y) tashqi burchak nuqtasi, (sx,sy) — ichkariga
     * yo'nalish (±1). Gorizontal arm → yumaloq egilish → vertikal arm bitta yo'lda
     * (strokeJoin ROUND), shuning uchun burchak silliq.
     */
    private fun corner(canvas: Canvas, x: Float, y: Float, sx: Float, sy: Float) {
        val bend = minOf(radius, armLen)
        cornerPath.reset()
        cornerPath.moveTo(x + sx * armLen, y)        // gorizontal armning uchi
        cornerPath.lineTo(x + sx * bend, y)          // burchakka yaqinlashish
        cornerPath.quadTo(x, y, x, y + sy * bend)    // burchakni yumaloqlash
        cornerPath.lineTo(x, y + sy * armLen)        // vertikal arm
        canvas.drawPath(cornerPath, cornerPaint)
    }
}
