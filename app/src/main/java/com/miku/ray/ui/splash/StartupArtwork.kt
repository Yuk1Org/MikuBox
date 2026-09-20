package com.miku.ray.ui.splash

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Build
import android.view.animation.DecelerateInterpolator
import androidx.core.view.updatePadding
import com.miku.ray.R
import com.miku.ray.util.AppNameHelper

/** The original splash artwork, covering an already laid-out home screen. */
object StartupArtwork {
    const val EXTRA_SHOW = "com.miku.ray.SHOW_STARTUP_ARTWORK"

    const val EXTRA_SYSTEM_HANDOFF = "com.miku.ray.WAIT_SYSTEM_SPLASH"

    fun install(activity: Activity, showArtwork: Boolean, awaitSystemHandoff: Boolean = false) {
        val onSystemExit = if (showArtwork) {
            attach(activity)
            revealArtwork(activity, Build.VERSION.SDK_INT >= 31 && awaitSystemHandoff)
        } else null
        if (Build.VERSION.SDK_INT >= 31) {
            activity.splashScreen.setOnExitAnimationListener { splash ->
                if (showArtwork) {
                    splash.remove()
                    onSystemExit?.invoke()
                } else {
                    splash.animate().alpha(0f).setDuration(400L)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction { splash.remove() }.start()
                }
            }
        }
    }

    private const val ARTWORK_TAG = "mikubox.startup.artwork"
    private fun revealArtwork(activity: Activity, awaitSystemHandoff: Boolean): () -> Unit {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val artwork = requireNotNull(content.findViewWithTag<WaveMaskLayout>(ARTWORK_TAG))
        var systemHandoffComplete = !awaitSystemHandoff
        var firstFrameCommitted = false
        var started = false
        var disposed = false
        var animator: ValueAnimator? = null
        var focusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
        val observer = content.viewTreeObserver
        fun removeFocusListener() {
            focusListener?.let { if (observer.isAlive) observer.removeOnWindowFocusChangeListener(it) }
            focusListener = null
        }
        fun startWhenVisible() {
            if (started || disposed || !systemHandoffComplete || !firstFrameCommitted || !content.hasWindowFocus()) return
            started = true
            removeFocusListener()
            artwork.postOnAnimation {
                if (disposed) return@postOnAnimation
                // Reveal home spatially: artwork stays opaque below the wave,
                // so the logo never ghosts over the home screen.
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 380L
                    interpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
                    addUpdateListener { animation ->
                        artwork.revealProgress = animation.animatedValue as Float
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            content.removeView(artwork)
                        }
                    })
                    start()
                }
            }
        }
        focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) startWhenVisible()
        }
        observer.addOnWindowFocusChangeListener(focusListener)
        artwork.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                disposed = true
                removeFocusListener()
                animator?.removeAllListeners()
                animator?.cancel()
            }
        })
        content.doOnPreDraw {
            // Pre-draw is too early: the starting window can still hide this frame.
            // Commit + focus prevents spending the transition before it is visible.
            if (Build.VERSION.SDK_INT >= 29 && content.isHardwareAccelerated) {
                content.viewTreeObserver.registerFrameCommitCallback {
                    content.post {
                        firstFrameCommitted = true
                        startWhenVisible()
                    }
                }
            } else {
                content.postOnAnimation {
                    firstFrameCommitted = true
                    startWhenVisible()
                }
            }
        }
        return {
            systemHandoffComplete = true
            startWhenVisible()
        }
    }

    private fun attach(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val artwork = WaveMaskLayout(activity)
        activity.layoutInflater.inflate(R.layout.uwu_activity_splash, artwork, true)
        artwork.tag = ARTWORK_TAG
        artwork.isClickable = true
        artwork.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        artwork.findViewById<TextView>(R.id.splash_name).text = AppNameHelper.getDisplayName(activity)
        artwork.findViewById<TextView>(R.id.splash_version).text = activity.getString(
            R.string.uwu_splash_summary,
            activity.getString(R.string.uwu_version_name),
            activity.getString(R.string.uwu_version_code).toInt(),
        )
        ViewCompat.setOnApplyWindowInsetsListener(artwork.getChildAt(0)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        content.addView(artwork, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ViewCompat.requestApplyInsets(artwork)
    }
}

/** Opaque splash below a descending, softly curved wave; home is visible above it. */
private class WaveMaskLayout(context: android.content.Context) : android.widget.FrameLayout(context) {
    init {
        // ViewGroup otherwise bypasses draw() when it has no background.
        setWillNotDraw(false)
    }

    private val mask = android.graphics.Path()
    var revealProgress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun draw(canvas: android.graphics.Canvas) {
        if (revealProgress == 0f) {
            super.draw(canvas)
            return
        }
        if (revealProgress == 1f) return
        val w = width.toFloat()
        val h = height.toFloat()
        val depth = minOf(88f * resources.displayMetrics.density, h * 0.12f)
        val y = -depth + (h + depth * 2f) * revealProgress
        mask.rewind()
        mask.moveTo(0f, y)
        mask.cubicTo(w * 0.32f, y - depth, w * 0.68f, y + depth, w, y)
        mask.lineTo(w, h + depth)
        mask.lineTo(0f, h + depth)
        mask.close()
        val checkpoint = canvas.save()
        canvas.clipPath(mask)
        super.draw(canvas)
        canvas.restoreToCount(checkpoint)
    }
}
