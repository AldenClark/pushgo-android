package io.ethan.pushgo.ui.markdown

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.Animatable
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import java.lang.ref.WeakReference

internal object MarkdownAnimatedImagePlaybackRegistry {
    private val registered = LinkedHashSet<WeakReference<MarkdownPlayableDrawable>>()
    private var active: WeakReference<MarkdownPlayableDrawable>? = null

    @Synchronized
    fun register(drawable: MarkdownPlayableDrawable) {
        pruneLocked()
        registered.add(WeakReference(drawable))
    }

    @Synchronized
    fun play(drawable: MarkdownPlayableDrawable): Boolean {
        pruneLocked()
        val current = active?.get()
        if (current !== drawable) {
            current?.stopAndReset()
        }
        active = WeakReference(drawable)
        val started = drawable.playOnce()
        if (!started) {
            if (active?.get() === drawable) {
                active = null
            }
            return false
        }
        return true
    }

    @Synchronized
    fun onPlaybackFinished(drawable: MarkdownPlayableDrawable) {
        if (active?.get() === drawable) {
            active = null
        }
    }

    @Synchronized
    fun stopAll() {
        pruneLocked()
        registered.mapNotNull { it.get() }.forEach { it.stopAndReset() }
        active = null
    }

    @Synchronized
    private fun pruneLocked() {
        registered.removeAll { it.get() == null }
    }
}

internal class MarkdownPlayableDrawable private constructor(
    private val resources: Resources,
    baseDrawable: Drawable,
) : Drawable(), Drawable.Callback {
    private var drawable: Drawable = baseDrawable.mutate()
    private var isPlaying = false
    private val density = resources.displayMetrics.density
    private val overlaySizePx = 24f * density
    private val overlayMarginPx = 8f * density
    private val overlayBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
    }
    private val overlayForeground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val animationCallback = object : Animatable2.AnimationCallback() {
        override fun onAnimationEnd(drawable: Drawable?) {
            handleAnimationFinished()
        }
    }
    private val compatAnimationCallback = object : Animatable2Compat.AnimationCallback() {
        override fun onAnimationEnd(drawable: Drawable?) {
            handleAnimationFinished()
        }
    }

    init {
        this.drawable.callback = this
        applyOneShotPolicy(this.drawable)
    }

    val isOverlayVisible: Boolean
        get() = isAnimationCapable(drawable) && !isPlaying

    fun playOnce(): Boolean {
        val animatable = drawable.asAnimatable() ?: return false
        isPlaying = true
        applyOneShotPolicy(drawable)
        animatable.start()
        invalidateSelf()
        return true
    }

    fun stopAndReset() {
        drawable.asAnimatable()?.stop()
        isPlaying = false
        drawable = resetDrawableForFirstFrame(drawable).also {
            it.callback = this
            applyOneShotPolicy(it)
        }
        drawable.bounds = bounds
        invalidateSelf()
    }

    fun isPlayButtonHit(localX: Float, localY: Float): Boolean {
        if (!isOverlayVisible) return false
        val bounds = drawable.bounds
        if (bounds.isEmpty) return false
        val radius = overlaySizePx / 2f
        val centerX = bounds.right - overlayMarginPx - radius
        val centerY = bounds.bottom - overlayMarginPx - radius
        val dx = localX - centerX
        val dy = localY - centerY
        return (dx * dx + dy * dy) <= (radius * radius)
    }

    override fun draw(canvas: Canvas) {
        drawable.draw(canvas)
        if (isOverlayVisible) {
            drawPlayOverlay(canvas)
        }
    }

    override fun setAlpha(alpha: Int) {
        drawable.alpha = alpha
    }

    override fun getAlpha(): Int = drawable.alpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        drawable.colorFilter = colorFilter
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        drawable.bounds = bounds
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        drawable.setVisible(visible, restart)
        return changed
    }

    override fun getIntrinsicWidth(): Int = drawable.intrinsicWidth

    override fun getIntrinsicHeight(): Int = drawable.intrinsicHeight

    override fun getMinimumWidth(): Int = drawable.minimumWidth

    override fun getMinimumHeight(): Int = drawable.minimumHeight

    @Suppress("DEPRECATION")
    override fun getTransparentRegion(): Region? = drawable.transparentRegion

    override fun onLevelChange(level: Int): Boolean {
        return drawable.setLevel(level)
    }

    override fun isStateful(): Boolean = drawable.isStateful

    override fun onStateChange(state: IntArray): Boolean {
        return drawable.setState(state)
    }

    override fun invalidateDrawable(who: Drawable) {
        invalidateSelf()
    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
        scheduleSelf(what, `when`)
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        unscheduleSelf(what)
    }

    private fun drawPlayOverlay(canvas: Canvas) {
        val bounds = drawable.bounds
        if (bounds.isEmpty) return
        val radius = overlaySizePx / 2f
        val centerX = bounds.right - overlayMarginPx - radius
        val centerY = bounds.bottom - overlayMarginPx - radius
        canvas.drawCircle(centerX, centerY, radius, overlayBackground)
        val triangle = Path().apply {
            moveTo(centerX - overlaySizePx * 0.18f, centerY - overlaySizePx * 0.22f)
            lineTo(centerX - overlaySizePx * 0.18f, centerY + overlaySizePx * 0.22f)
            lineTo(centerX + overlaySizePx * 0.24f, centerY)
            close()
        }
        canvas.drawPath(triangle, overlayForeground)
    }

    private fun handleAnimationFinished() {
        isPlaying = false
        drawable = resetDrawableForFirstFrame(drawable).also {
            it.callback = this
            applyOneShotPolicy(it)
        }
        drawable.bounds = bounds
        invalidateSelf()
        MarkdownAnimatedImagePlaybackRegistry.onPlaybackFinished(this)
    }

    private fun applyOneShotPolicy(target: Drawable) {
        when (target) {
            is AnimatedImageDrawable -> {
                target.repeatCount = 0
                target.unregisterAnimationCallback(animationCallback)
                target.registerAnimationCallback(animationCallback)
            }
            is Animatable2 -> {
                target.unregisterAnimationCallback(animationCallback)
                target.registerAnimationCallback(animationCallback)
            }
            is Animatable2Compat -> {
                target.unregisterAnimationCallback(compatAnimationCallback)
                target.registerAnimationCallback(compatAnimationCallback)
            }
        }
    }

    private fun resetDrawableForFirstFrame(target: Drawable): Drawable {
        target.asAnimatable()?.stop()
        val recreated = target.constantState
            ?.newDrawable(resources)
            ?.mutate()
        return recreated ?: target
    }

    private fun Drawable.asAnimatable(): Animatable? {
        return when (this) {
            is Animatable -> this
            is Animatable2Compat -> this
            else -> null
        }
    }

    companion object {
        fun wrapIfAnimated(resources: Resources, drawable: Drawable): Drawable {
            if (!isAnimationCapable(drawable)) {
                return drawable
            }
            return MarkdownPlayableDrawable(resources, drawable)
        }

        private fun isAnimationCapable(drawable: Drawable): Boolean {
            return drawable is Animatable || drawable is Animatable2Compat
        }
    }
}
