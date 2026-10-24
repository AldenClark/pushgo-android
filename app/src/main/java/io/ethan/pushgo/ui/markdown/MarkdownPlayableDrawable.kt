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
import android.os.Looper
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
    fun unregister(drawable: MarkdownPlayableDrawable) {
        registered.removeAll { it.get() == null || it.get() === drawable }
        if (active?.get() === drawable) active = null
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
    private var isReleased = false
    private var retryAction: (() -> Unit)? = null
    private var showsTemporaryFallback = false
    private var hideOverlayUntilPlaybackFinishes = false
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
    private val animationCallback = PlatformAnimationCallback(this)
    private val compatAnimationCallback = CompatAnimationCallback(this)

    init {
        this.drawable.callback = this
        applyOneShotPolicy(this.drawable)
        // Keep initial frame deterministic even if a decoder attempts eager playback.
        this.drawable.asAnimatable()?.stop()
    }

    val isOverlayVisible: Boolean
        get() = !isReleased && isAnimationCapable(drawable) &&
            !hideOverlayUntilPlaybackFinishes &&
            !drawable.isActuallyRunning()

    val isRetryVisible: Boolean get() = !isReleased && retryAction != null
    val isTemporaryFallback: Boolean get() = isRetryVisible && showsTemporaryFallback

    fun showRetry(temporaryFallback: Boolean, action: () -> Unit) {
        showsTemporaryFallback = temporaryFallback
        retryAction = action
        invalidateSelf()
    }

    fun retry(): Boolean {
        val action = retryAction ?: return false
        release()
        action()
        return true
    }

    fun isRetryButtonHit(localX: Float, localY: Float): Boolean = isRetryVisible &&
        localX >= bounds.left && localX <= bounds.right &&
        localY >= bounds.top && localY <= minOf(bounds.bottom.toFloat(), bounds.top + 48f * density)

    fun playOnce(): Boolean {
        if (isReleased) return false
        val animatable = drawable.asAnimatable() ?: return false
        isPlaying = true
        hideOverlayUntilPlaybackFinishes = true
        applyOneShotPolicy(drawable)
        animatable.start()
        invalidateSelf()
        return true
    }

    fun stopAndReset() {
        if (isReleased) return
        drawable.asAnimatable()?.stop()
        isPlaying = false
        hideOverlayUntilPlaybackFinishes = false
        drawable = resetDrawableForFirstFrame(drawable).also {
            it.callback = this
            applyOneShotPolicy(it)
        }
        drawable.bounds = bounds
        invalidateSelf()
    }

    /** A detached span must not keep frame callbacks or its display owner alive. */
    fun release() {
        if (isReleased) return
        isReleased = true
        retryAction = null
        animationCallback.detach()
        compatAnimationCallback.detach()
        drawable.asAnimatable()?.stop()
        when (val target = drawable) {
            // API 37's already-posted animation-end Runnable dereferences its
            // callback list after invoking callbacks. Removing its last entry can
            // null that list mid-dispatch. Keep only this weak, now-detached hook.
            is AnimatedImageDrawable -> Unit
            is Animatable2 -> target.unregisterAnimationCallback(animationCallback)
            is Animatable2Compat -> target.unregisterAnimationCallback(compatAnimationCallback)
        }
        drawable.callback = null
        callback = null
        isPlaying = false
        hideOverlayUntilPlaybackFinishes = false
        MarkdownAnimatedImagePlaybackRegistry.unregister(this)
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

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        drawable.setVisible(visible, restart)
        if (!visible && !isReleased) stopAndReset()
        return changed
    }

    override fun draw(canvas: Canvas) {
        if (isReleased) return
        drawable.draw(canvas)
        if (isOverlayVisible) {
            drawPlayOverlay(canvas)
        }
        if (isRetryVisible) {
            val left = bounds.left.toFloat()
            val bottom = minOf(bounds.bottom.toFloat(), bounds.top + 48f * density)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFF1D6.toInt() }
            canvas.drawRect(left, bounds.top.toFloat(), bounds.right.toFloat(), bottom, paint)
            paint.color = 0xFF6D4100.toInt()
            val label = resources.getString(if (showsTemporaryFallback) io.ethan.pushgo.R.string.image_cached_fallback_retry else io.ethan.pushgo.R.string.action_retry)
            val text = android.text.TextPaint(paint).apply { textSize = 12f * density }
            val layout = android.text.StaticLayout.Builder.obtain(label, 0, label.length, text, maxOf(1, bounds.width() - (12f * density).toInt()))
                .setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
            canvas.save()
            canvas.clipRect(left, bounds.top.toFloat(), bounds.right.toFloat(), bottom)
            canvas.translate(left + 6f * density, bounds.top + 4f * density)
            layout.draw(canvas)
            canvas.restore()
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

    private fun handleAnimationFinished(source: Drawable?) {
        if (isReleased || source !== drawable) return
        isPlaying = false
        hideOverlayUntilPlaybackFinishes = false
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
                if (Looper.myLooper() == null) return
                // Registration is idempotent for this callback. Do not empty the
                // platform callback list while its end notification may be queued.
                target.registerAnimationCallback(animationCallback)
            }
            is Animatable2 -> {
                if (Looper.myLooper() == null) return
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
        when (target) {
            is AnimatedImageDrawable -> Unit
            is Animatable2 -> target.unregisterAnimationCallback(animationCallback)
            is Animatable2Compat -> target.unregisterAnimationCallback(compatAnimationCallback)
        }
        target.callback = null
        val recreated = target.constantState
            ?.newDrawable(resources)
            ?.mutate()
        return recreated ?: target
    }

    private class PlatformAnimationCallback(owner: MarkdownPlayableDrawable) : Animatable2.AnimationCallback() {
        private val owner = WeakReference(owner)
        override fun onAnimationEnd(drawable: Drawable?) { owner.get()?.handleAnimationFinished(drawable) }
        fun detach() { owner.clear() }
    }

    private class CompatAnimationCallback(owner: MarkdownPlayableDrawable) : Animatable2Compat.AnimationCallback() {
        private val owner = WeakReference(owner)
        override fun onAnimationEnd(drawable: Drawable?) { owner.get()?.handleAnimationFinished(drawable) }
        fun detach() { owner.clear() }
    }

    private fun Drawable.asAnimatable(): Animatable? {
        return when (this) {
            is Animatable -> this
            is Animatable2Compat -> this
            else -> null
        }
    }

    private fun Drawable.isActuallyRunning(): Boolean {
        return when (this) {
            is Animatable -> this.isRunning
            is Animatable2Compat -> this.isRunning
            else -> false
        }
    }

    companion object {
        fun wrapResource(resources: Resources, drawable: Drawable): MarkdownPlayableDrawable =
            MarkdownPlayableDrawable(resources, drawable)

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
