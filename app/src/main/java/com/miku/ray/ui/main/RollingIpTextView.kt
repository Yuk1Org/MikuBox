package com.miku.ray.ui.main

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.miku.ray.marquee.text.AutoMarqueeTextView
import kotlin.math.cos
import kotlin.math.sin

/** Two adjacent prism faces rotate upward; the live text stays accessible and marquee-capable. */
class RollingIpTextView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AutoMarqueeTextView(context, attrs) {
    private val camera = Camera()
    private val transform = Matrix()
    private var previous: Bitmap? = null
    private var animator: ValueAnimator? = null
    private var progress = 1f
    private var target: String? = null
    private val shadePaint = Paint()

    fun showValue(value: String, animate: Boolean) {
        if (value == target) return
        val hadText = !text.isNullOrBlank()
        releaseAnimation()
        if (animate && hadText && isAttachedToWindow && isLaidOut && width > 0 && height > 0) {
            previous = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }
        }
        target = value
        text = value
        if (previous == null) return
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380L
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { releaseAnimation(); invalidate() }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val old = previous
        if (old == null || progress >= 1f) { super.onDraw(canvas); return }
        val radians = progress * Math.PI / 2
        val radius = height / 2f
        val checkpoint = canvas.save()
        canvas.clipRect(0, 0, width, height)

        // Old face rotates away (top edge tipping backward into the screen).
        face(canvas, -90f * progress, -radius * sin(radians).toFloat(), 1f - progress) {
            canvas.drawBitmap(old, 0f, 0f, null)
        }
        // New face rotates in from below (bottom edge swinging up toward viewer).
        face(canvas, 90f * (1f - progress), radius * cos(radians).toFloat(), progress) {
            super.onDraw(canvas)
        }
        canvas.restoreToCount(checkpoint)
    }

    private inline fun face(canvas: Canvas, angle: Float, offset: Float, visibility: Float, draw: () -> Unit) {
        val checkpoint = canvas.save()
        camera.save()
        camera.rotateX(angle)
        camera.getMatrix(transform)
        camera.restore()
        transform.preTranslate(-width / 2f, -height / 2f)
        transform.postTranslate(width / 2f, height / 2f + offset)
        canvas.concat(transform)

        // Shade the face as it turns away from the viewer, so the rotation
        // reads as a 3D prism edge rather than a flat slide.
        val shade = ((1f - visibility) * 110f).toInt().coerceIn(0, 110)
        if (shade > 0) {
            shadePaint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                0x70000000 or (shade shl 16) or (shade shl 8) or shade,
                0x00000000,
                Shader.TileMode.CLAMP
            )
            draw()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadePaint)
        } else {
            draw()
        }
        canvas.restoreToCount(checkpoint)
    }

    private fun releaseAnimation() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        previous?.recycle()
        previous = null
        progress = 1f
    }

    override fun onDetachedFromWindow() { releaseAnimation(); super.onDetachedFromWindow() }
}
