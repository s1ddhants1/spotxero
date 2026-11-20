package app.spotxero.hooks.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.RelativeLayout
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

object ArtworkSeekHook {

    private const val TAG_SEEK_OVERLAY = "spotxero_artwork_seek_overlay"

    private val candidateContainers = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val resourceNames = ConcurrentHashMap<Int, String>()

    init {
        PreferencesManager.addOnPreferenceChangedListener {
            mainHandler.post {
                val enabled = PreferencesManager.isEnabled(PrefKeys.KEY_DOUBLE_TAP_TO_SEEK, true)
                val containers = synchronized(candidateContainers) { candidateContainers.toList() }
                containers.forEach { container ->
                    if (container.isAttachedToWindow) {
                        if (enabled) {
                            attachOverlay(container)
                        } else {
                            container.findViewWithTag<View>(TAG_SEEK_OVERLAY)?.let { overlay ->
                                container.removeView(overlay)
                            }
                        }
                    }
                }
            }
        }
    }

    @Volatile
    private var activeMediaSession: WeakReference<MediaSession>? = null

    @Volatile
    private var activeMediaController: WeakReference<MediaController>? = null

    fun updateMediaSession(session: MediaSession) {
        activeMediaSession = WeakReference(session)
        runCatching {
            activeMediaController = WeakReference(session.controller)
        }
    }

    fun getController(fallbackContext: Context? = null): MediaController? {
        activeMediaController?.get()?.let { return it }
        activeMediaSession?.get()?.controller?.let {
            activeMediaController = WeakReference(it)
            return it
        }
        if (fallbackContext is Activity) {
            fallbackContext.mediaController?.let {
                activeMediaController = WeakReference(it)
                return it
            }
        }
        return null
    }

    fun seekBy(deltaMs: Long, context: Context? = null): Boolean {
        val controller = getController(context) ?: return false
        val state = controller.playbackState ?: return false
        val currentPosition = if (state.state == PlaybackState.STATE_PLAYING) {
            val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            state.position + (elapsed * state.playbackSpeed).toLong()
        } else {
            state.position
        }
        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val targetPosition = if (duration > 0) {
            (currentPosition + deltaMs).coerceIn(0L, duration)
        } else {
            (currentPosition + deltaMs).coerceAtLeast(0L)
        }
        controller.transportControls.seekTo(targetPosition)
        return true
    }

    fun install(module: XposedModule) {
        hookMediaSession(module)
        hookViewAttach(module)
    }

    private fun hookMediaSession(module: XposedModule) {
        runCatching {
            val ctor = MediaSession::class.java.getDeclaredConstructor(Context::class.java, String::class.java)
            module.deoptimize(ctor)
            module.hook(ctor)
                .setId("artwork_seek_media_session_ctor")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.getThisObject() as? MediaSession)?.let { updateMediaSession(it) }
                    result
                }
        }

        runCatching {
            val setActiveMethod = MediaSession::class.java.getDeclaredMethod("setActive", Boolean::class.javaPrimitiveType)
            module.deoptimize(setActiveMethod)
            module.hook(setActiveMethod)
                .setId("artwork_seek_media_session_active")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    (chain.getThisObject() as? MediaSession)?.let { updateMediaSession(it) }
                    chain.proceed()
                }
        }

        runCatching {
            val setPlaybackStateMethod = MediaSession::class.java.getDeclaredMethod("setPlaybackState", PlaybackState::class.java)
            module.deoptimize(setPlaybackStateMethod)
            module.hook(setPlaybackStateMethod)
                .setId("artwork_seek_media_session_state")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    (chain.getThisObject() as? MediaSession)?.let { updateMediaSession(it) }
                    chain.proceed()
                }
        }

        runCatching {
            val setMetadataMethod = MediaSession::class.java.getDeclaredMethod("setMetadata", MediaMetadata::class.java)
            module.deoptimize(setMetadataMethod)
            module.hook(setMetadataMethod)
                .setId("artwork_seek_media_session_metadata")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    (chain.getThisObject() as? MediaSession)?.let { updateMediaSession(it) }
                    chain.proceed()
                }
        }
    }

    private fun hookViewAttach(module: XposedModule) {
        val method = runCatching {
            View::class.java.getDeclaredMethod("onAttachedToWindow")
        }.getOrNull() ?: return

        module.deoptimize(method)
        module.hook(method)
            .setId("artwork_seek_view_attach")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                if (!PreferencesManager.isEnabled(PrefKeys.KEY_DOUBLE_TAP_TO_SEEK, true)) {
                    return@intercept result
                }
                val view = chain.getThisObject() as? View
                if (view != null) {
                    attachOverlayIfTarget(view)
                }
                result
            }
    }

    private fun findArtworkContainer(view: View): ViewGroup? {
        val resName = resourceEntryName(view)
        val className = view.javaClass.name.lowercase()

        if (resName.contains("avatar") || resName.contains("face") || className.contains("faceview")) {
            return null
        }
        if (resName.contains("mini") || resName.contains("small") || resName.contains("thumb")) {
            return null
        }
        if (resName.contains("header") || resName.contains("entity") || resName.contains("playlist") || resName.contains("browse")) {
            return null
        }
        if (resName.contains("row") || resName.contains("item")) {
            return null
        }

        if (resName == "cover_art_container" || resName == "artwork_container" || resName == "cover_art_view") {
            return view as? ViewGroup
        }

        if (resName == "cover_art_image" || resName == "ads_mode_cover_art_image" || resName == "album_artwork" ||
            resName == "fallback_cover_art_image" || resName == "multitap_seek_overlay"
        ) {
            return view.parent as? ViewGroup
        }

        if (resName == "image" || resName == "cover_image") {
            val parent = view.parent as? View ?: return null
            val parentRes = resourceEntryName(parent)
            if (parentRes == "cover_art_container" || parentRes.contains("cover") || parentRes == "music_container") {
                return view.parent as? ViewGroup
            }
        }

        if (resName == "video_surface" || resName == "canvas_container") {
            return view.parent as? ViewGroup
        }

        return null
    }

    private fun attachOverlayIfTarget(view: View) {
        val targetContainer = findArtworkContainer(view) ?: return
        candidateContainers.add(targetContainer)
        attachOverlay(targetContainer)
    }

    private fun resourceEntryName(view: View): String {
        val id = view.id
        if (id == View.NO_ID) return ""
        return resourceNames.computeIfAbsent(id) {
            runCatching { view.resources.getResourceEntryName(id).lowercase() }.getOrDefault("")
        }
    }

    private fun attachOverlay(targetContainer: ViewGroup) {
        if (!PreferencesManager.isEnabled(PrefKeys.KEY_DOUBLE_TAP_TO_SEEK, true)) {
            return
        }

        if (targetContainer.findViewWithTag<View>(TAG_SEEK_OVERLAY) != null) {
            return
        }

        val overlay = DoubleTapSeekOverlayView(targetContainer.context)
        overlay.tag = TAG_SEEK_OVERLAY

        val lp = when (targetContainer) {
            is FrameLayout -> FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            is RelativeLayout -> RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            else -> ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        targetContainer.addView(overlay, lp)
        overlay.bringToFront()
    }
}

@SuppressLint("ViewConstructor")
internal class DoubleTapSeekOverlayView(context: Context) : View(context) {

    private val gestureDetector: GestureDetector
    private val mainHandler = Handler(Looper.getMainLooper())

    private var skipMultiplier = 1
    private var lastTapTime = 0L
    private var lastIsForward: Boolean? = null

    private var isFeedbackVisible = false
    private var feedbackIsForward = true
    private var feedbackDeltaSeconds = 10
    private var arrowNudge = 0f
    private var feedbackAlpha = 0f
    private var feedbackScale = 1f

    private var nudgeAnimator: ValueAnimator? = null
    private var fadeAnimator: ValueAnimator? = null

    private val hideRunnable = Runnable { fadeOut() }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val pillRect = RectF()
    private val trianglePath1 = Path()
    private val trianglePath2 = Path()

    init {
        setWillNotDraw(false)

        val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return PreferencesManager.isEnabled(PrefKeys.KEY_DOUBLE_TAP_TO_SEEK, true)
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                parent?.requestDisallowInterceptTouchEvent(false)
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!PreferencesManager.isEnabled(PrefKeys.KEY_DOUBLE_TAP_TO_SEEK, true)) {
                    return false
                }
                parent?.requestDisallowInterceptTouchEvent(true)

                val isForward = e.x >= width / 2f
                triggerSeek(isForward)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val parentGroup = parent as? ViewGroup ?: return false
                for (i in 0 until parentGroup.childCount) {
                    val child = parentGroup.getChildAt(i)
                    if (child !== this@DoubleTapSeekOverlayView && child.visibility == VISIBLE && child.isClickable) {
                        return child.performClick()
                    }
                }
                return parentGroup.performClick()
            }
        }
        gestureDetector = GestureDetector(context, gestureListener)
    }

    private fun triggerSeek(isForward: Boolean) {
        val stepSeconds = PreferencesManager.getInt(PrefKeys.KEY_SEEK_DURATION_SECONDS, 10).coerceIn(5, 30)
        val isProgressive = PreferencesManager.isEnabled(PrefKeys.KEY_SEEK_PROGRESSIVE, false)

        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastTapTime
        if (isProgressive && lastIsForward == isForward && elapsed in 0..1000L) {
            skipMultiplier++
        } else {
            skipMultiplier = 1
        }
        lastTapTime = now
        lastIsForward = isForward

        val totalSeconds = stepSeconds * skipMultiplier
        val deltaMs = if (isForward) totalSeconds * 1000L else -totalSeconds * 1000L

        ArtworkSeekHook.seekBy(deltaMs, context)
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        showFeedback(isForward, totalSeconds)
    }

    private fun showFeedback(isForward: Boolean, totalSeconds: Int) {
        isFeedbackVisible = true
        feedbackIsForward = isForward
        feedbackDeltaSeconds = totalSeconds

        mainHandler.removeCallbacks(hideRunnable)

        nudgeAnimator?.cancel()
        val startNudge = dpToPx(if (isForward) 14f else -14f)
        nudgeAnimator = ValueAnimator.ofFloat(startNudge, 0f).apply {
            duration = 320
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                arrowNudge = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(feedbackAlpha, 1f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                feedbackAlpha = it.animatedValue as Float
                feedbackScale = 0.75f + (0.25f * it.animatedFraction)
                invalidate()
            }
            start()
        }

        mainHandler.postDelayed(hideRunnable, 650)
    }

    private fun fadeOut() {
        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(feedbackAlpha, 0f).apply {
            duration = 220
            interpolator = AccelerateInterpolator()
            addUpdateListener {
                feedbackAlpha = it.animatedValue as Float
                feedbackScale = 1.0f - (0.1f * it.animatedFraction)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isFeedbackVisible = false
                    skipMultiplier = 1
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!PreferencesManager.isEnabled(PrefKeys.KEY_DOUBLE_TAP_TO_SEEK, true)) {
            return false
        }

        val handled = gestureDetector.onTouchEvent(event)
        return handled || super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isFeedbackVisible && feedbackAlpha <= 0f) return

        val cx = if (feedbackIsForward) width * 0.72f else width * 0.28f
        val cy = height * 0.5f

        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(feedbackScale, feedbackScale)

        val pillRadius = dpToPx(48f)
        pillRect.set(-pillRadius, -pillRadius, pillRadius, pillRadius)
        bgPaint.color = Color.argb((145 * feedbackAlpha).toInt(), 18, 18, 18)
        canvas.drawRoundRect(pillRect, pillRadius, pillRadius, bgPaint)

        val triWidth = dpToPx(13f)
        val triHeight = dpToPx(16f)
        val spacing = dpToPx(3f)
        val iconY = -dpToPx(10f)

        iconPaint.color = Color.WHITE
        iconPaint.alpha = (255 * feedbackAlpha).toInt()

        trianglePath1.reset()
        trianglePath2.reset()

        if (feedbackIsForward) {
            val startX1 = -triWidth - spacing / 2f + arrowNudge
            trianglePath1.moveTo(startX1, iconY - triHeight / 2f)
            trianglePath1.lineTo(startX1 + triWidth, iconY)
            trianglePath1.lineTo(startX1, iconY + triHeight / 2f)
            trianglePath1.close()

            val startX2 = spacing / 2f + arrowNudge
            trianglePath2.moveTo(startX2, iconY - triHeight / 2f)
            trianglePath2.lineTo(startX2 + triWidth, iconY)
            trianglePath2.lineTo(startX2, iconY + triHeight / 2f)
            trianglePath2.close()
        } else {
            val startX1 = triWidth + spacing / 2f + arrowNudge
            trianglePath1.moveTo(startX1, iconY - triHeight / 2f)
            trianglePath1.lineTo(startX1 - triWidth, iconY)
            trianglePath1.lineTo(startX1, iconY + triHeight / 2f)
            trianglePath1.close()

            val startX2 = -spacing / 2f + arrowNudge
            trianglePath2.moveTo(startX2, iconY - triHeight / 2f)
            trianglePath2.lineTo(startX2 - triWidth, iconY)
            trianglePath2.lineTo(startX2, iconY + triHeight / 2f)
            trianglePath2.close()
        }

        canvas.drawPath(trianglePath1, iconPaint)
        canvas.drawPath(trianglePath2, iconPaint)

        val label = "$feedbackDeltaSeconds s"
        textPaint.color = Color.WHITE
        textPaint.alpha = (255 * feedbackAlpha).toInt()
        textPaint.textSize = dpToPx(14f)
        textPaint.setShadowLayer(dpToPx(4f), 0f, dpToPx(1f), Color.argb((160 * feedbackAlpha).toInt(), 0, 0, 0))
        canvas.drawText(label, 0f, dpToPx(24f), textPaint)

        canvas.restore()
    }

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            Resources.getSystem().displayMetrics
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mainHandler.removeCallbacks(hideRunnable)
        nudgeAnimator?.cancel()
        fadeAnimator?.cancel()
    }
}
