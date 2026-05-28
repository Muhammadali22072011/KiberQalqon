package com.kiberqalqon

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

object AnimationHelper {
    
    /**
     * Плавное появление View (fade in + scale)
     */
    fun fadeIn(view: View, duration: Long = 300, delay: Long = 0) {
        view.alpha = 0f
        view.scaleX = 0.8f
        view.scaleY = 0.8f
        view.visibility = View.VISIBLE
        
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(duration)
            .setStartDelay(delay)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
    
    /**
     * Плавное исчезновение View (fade out + scale)
     */
    fun fadeOut(view: View, duration: Long = 300, onEnd: (() -> Unit)? = null) {
        view.animate()
            .alpha(0f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .setDuration(duration)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.visibility = View.GONE
                    onEnd?.invoke()
                }
            })
            .start()
    }
    
    /**
     * Появление снизу вверх (slide up)
     */
    fun slideUp(view: View, duration: Long = 400, delay: Long = 0) {
        view.translationY = view.height.toFloat()
        view.alpha = 0f
        view.visibility = View.VISIBLE
        
        view.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(duration)
            .setStartDelay(delay)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
    
    /**
     * Исчезновение вниз (slide down)
     */
    fun slideDown(view: View, duration: Long = 400, onEnd: (() -> Unit)? = null) {
        view.animate()
            .translationY(view.height.toFloat())
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.visibility = View.GONE
                    onEnd?.invoke()
                }
            })
            .start()
    }
    
    /**
     * Пульсация (для кнопок и важных элементов)
     */
    fun pulse(view: View, duration: Long = 1000, repeat: Boolean = true) {
        val animator = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.1f, 1f)
        animator.duration = duration
        animator.interpolator = AccelerateDecelerateInterpolator()
        if (repeat) {
            animator.repeatCount = ValueAnimator.INFINITE
        }
        animator.start()
        
        val animatorY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.1f, 1f)
        animatorY.duration = duration
        animatorY.interpolator = AccelerateDecelerateInterpolator()
        if (repeat) {
            animatorY.repeatCount = ValueAnimator.INFINITE
        }
        animatorY.start()
    }
    
    /**
     * Встряхивание (для ошибок)
     */
    fun shake(view: View, duration: Long = 500) {
        val animator = ObjectAnimator.ofFloat(view, "translationX", 0f, 25f, -25f, 25f, -25f, 15f, -15f, 6f, -6f, 0f)
        animator.duration = duration
        animator.start()
    }
    
    /**
     * Отскок (bounce)
     */
    fun bounce(view: View, duration: Long = 600) {
        view.scaleX = 0f
        view.scaleY = 0f
        view.visibility = View.VISIBLE
        
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(duration)
            .setInterpolator(OvershootInterpolator())
            .start()
    }
    
    /**
     * Вращение (для иконок загрузки)
     */
    fun rotate(view: View, duration: Long = 1000, repeat: Boolean = true) {
        val animator = ObjectAnimator.ofFloat(view, "rotation", 0f, 360f)
        animator.duration = duration
        animator.interpolator = AccelerateDecelerateInterpolator()
        if (repeat) {
            animator.repeatCount = ValueAnimator.INFINITE
        }
        animator.start()
    }
    
    /**
     * Ripple эффект (для кнопок)
     */
    fun ripple(view: View) {
        view.isClickable = true
        view.isFocusable = true
        view.foreground = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x40000000),
            null,
            null
        )
    }
    
    /**
     * Последовательное появление списка элементов
     */
    fun staggeredFadeIn(views: List<View>, delayBetween: Long = 100) {
        views.forEachIndexed { index, view ->
            fadeIn(view, delay = index * delayBetween)
        }
    }

    /**
     * Cyber glow pulse — медленная пульсация для светящихся элементов (radar/shield)
     */
    fun glowPulse(view: View, duration: Long = 1400) {
        val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.12f, 1f).apply {
            this.duration = duration
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
        }
        val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.12f, 1f).apply {
            this.duration = duration
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
        }
        val alpha = ObjectAnimator.ofFloat(view, "alpha", 0.4f, 0.9f, 0.4f).apply {
            this.duration = duration
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
        }
        scaleX.start()
        scaleY.start()
        alpha.start()
    }

    /**
     * Scan wave — расходящаяся волна от центра, имитация скана
     */
    fun scanWave(view: View, duration: Long = 2000) {
        val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 0.4f, 1.4f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
        }
        val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 0.4f, 1.4f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
        }
        val alpha = ObjectAnimator.ofFloat(view, "alpha", 0.8f, 0f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
        }
        scaleX.start()
        scaleY.start()
        alpha.start()
    }

    /**
     * Медленное непрерывное вращение (для радара)
     */
    fun radarRotate(view: View, duration: Long = 5000) {
        rotate(view, duration = duration, repeat = true)
    }

    /**
     * Slide in slowly с fade — для появления верхней панели/чипа
     */
    fun slideInTop(view: View, duration: Long = 600, delay: Long = 0) {
        view.translationY = -view.height.toFloat().coerceAtLeast(80f)
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(duration)
            .setStartDelay(delay)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * Каскадное появление дочерних элементов ViewGroup
     */
    fun cascadeChildren(parent: android.view.ViewGroup, delayBetween: Long = 80) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            child.alpha = 0f
            child.translationY = 40f
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(500)
                .setStartDelay(i * delayBetween)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }
}
