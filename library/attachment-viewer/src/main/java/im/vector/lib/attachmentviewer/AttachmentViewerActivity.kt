/*
 * Copyright 2020-2024 New Vector Ltd.
 * Copyright 2018 stfalcon.com
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.attachmentviewer

import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.transition.TransitionManager
import androidx.viewpager2.widget.ViewPager2
import im.vector.lib.attachmentviewer.databinding.ActivityAttachmentViewerBinding
import im.vector.lib.ui.styles.R
import java.lang.ref.WeakReference
import kotlin.math.abs

abstract class AttachmentViewerActivity : AppCompatActivity(), AttachmentEventListener {

    protected val rootView: View get() = views.rootContainer
    protected val pager2: ViewPager2 get() = views.attachmentPager
    protected val imageTransitionView: ImageView get() = views.transitionImageView
    protected val transitionImageContainer: ViewGroup get() = views.transitionImageContainer

    private var topInset = 0
    private var bottomInset = 0
    private var systemUiVisibility = false

    var overlayView: View? = null
        set(value) {
            if (value == overlayView) return
            overlayView?.let { views.rootContainer.removeView(it) }
            views.rootContainer.addView(value)
            value?.updatePadding(top = topInset, bottom = bottomInset)
            field = value
        }

    private lateinit var views: ActivityAttachmentViewerBinding

    private lateinit var swipeDismissHandler: SwipeToDismissHandler
    private lateinit var directionDetector: SwipeDirectionDetector
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    var currentPosition = 0
        private set

    private var swipeDirection: SwipeDirection? = null
    private fun isScaled() = attachmentsAdapter.isScaled(currentPosition)
    private val attachmentsAdapter = AttachmentsAdapter()
    private var wasScaled: Boolean = false
    private var isSwipeToDismissAllowed: Boolean = true
    private var isOverlayWasClicked = false
    private var isImagePagerIdle = true
    private var lastEventConsumedByOverlay = false
    fun setSourceProvider(sourceProvider: AttachmentSourceProvider) {
        attachmentsAdapter.attachmentSourceProvider = sourceProvider
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setDecorViewFullScreen()
        views = ActivityAttachmentViewerBinding.inflate(layoutInflater)
        setContentView(views.root)
        views.backgroundView.alpha = 0f

        views.attachmentPager.apply {
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
            adapter = attachmentsAdapter
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrollStateChanged(state: Int) {
                    isImagePagerIdle = state == ViewPager2.SCROLL_STATE_IDLE
                }
                override fun onPageSelected(position: Int) {
                    onSelectedPositionChanged(position)
                }
            })
            // ViewPager2 does not fire onPageSelected for the initial page, so the
            // first video/image never receives onSelected(true) and stays unselected
            // until the user swipes away and back. Force-select once the pager has
            // settled on its starting position.
            doOnLayout {
                onSelectedPositionChanged(currentItem)
            }
        }

        directionDetector = createSwipeDirectionDetector()
        gestureDetector = createGestureDetector()
        scaleDetector = createScaleGestureDetector()
        swipeDismissHandler = createSwipeToDismissHandler()

        // Feed touch events to the dismiss handler directly too, so it sees
        // every intermediate ACTION_MOVE during a drag, not just what
        // dispatchTouchEvent forwards on DOWN/UP.
        views.rootContainer.setOnTouchListener(swipeDismissHandler)

        views.rootContainer.viewTreeObserver.addOnGlobalLayoutListener {
            swipeDismissHandler.translationLimit = views.dismissContainer.height / 4
        }
    }

    private fun setDecorViewFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.windowInsetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    fun onSelectedPositionChanged(position: Int) {
        attachmentsAdapter.recyclerView?.findViewHolderForAdapterPosition(currentPosition)?.let {
            (it as? BaseViewHolder)?.onSelected(false)
        }
        attachmentsAdapter.recyclerView?.findViewHolderForAdapterPosition(position)?.let {
            (it as? BaseViewHolder)?.onSelected(true)
            if (it is VideoViewHolder) {
                it.eventListener = WeakReference(this)
            }
        }
        currentPosition = position
        overlayView = attachmentsAdapter.attachmentSourceProvider?.overlayViewAtPosition(this, position)
        overlayView?.isVisible = true
        systemUiVisibility = true
    }

    override fun onPause() {
        attachmentsAdapter.onPause(currentPosition)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        attachmentsAdapter.onResume(currentPosition)
    }

    // Restored: this is what actually feeds taps to gestureDetector/scaleDetector,
    // and routes swipe-to-dismiss vs pager-swipe vs scaled-zoom touch handling.
    // Without this override, single taps never reached onSingleTapConfirmed,
    // so the overlay (back button, play/pause, etc.) could never be toggled
    // back on once hidden.

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val overlayConsumed = overlayView?.isVisible == true && overlayView?.dispatchTouchEvent(ev) == true
        lastEventConsumedByOverlay = overlayConsumed

        if (ev.action == MotionEvent.ACTION_DOWN) {
            isOverlayWasClicked = overlayConsumed
        }

        if (overlayConsumed) {
            return true
        }

        handleUpDownEvent(ev)

        if (ev.pointerCount > 1 || wasScaled) {
            wasScaled = true
            return views.attachmentPager.dispatchTouchEvent(ev)
        }

        return if (isScaled()) {
            super.dispatchTouchEvent(ev)
        } else {
            handleTouchIfNotScaled(ev)
        }
    }

    private fun handleUpDownEvent(event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_UP) handleEventActionUp(event)
        if (event.action == MotionEvent.ACTION_DOWN) handleEventActionDown(event)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
    }

    // NOTE: previously these two also called views.attachmentPager.dispatchTouchEvent(event)
    // directly. That caused a double-dispatch: the pager (and its child views, including
    // VideoViewHolder's videoControlIcon) could receive ACTION_DOWN or ACTION_UP twice for
    // a single physical tap — once here, once again via handleTouchIfNotScaled() below.
    // Forwarding to the pager now happens exactly once, at the bottom of handleTouchIfNotScaled,
    // so every tap reaches child views as a single clean DOWN/UP pair.
    private fun handleEventActionDown(event: MotionEvent) {
        swipeDirection = null
        wasScaled = false
        swipeDismissHandler.onTouch(views.rootContainer, event)
    }

    private fun handleEventActionUp(event: MotionEvent) {
        swipeDismissHandler.onTouch(views.rootContainer, event)
    }

    private fun handleSingleTap(event: MotionEvent, isOverlayWasClicked: Boolean) {
        if (!isOverlayWasClicked && overlayView != null) {
            toggleOverlayViewVisibility()
        }
    }

    private fun toggleOverlayViewVisibility() {
        TransitionManager.beginDelayedTransition(views.rootContainer)
        if (systemUiVisibility) {
            hideSystemUI()
        } else {
            showSystemUI()
        }
    }

    private fun handleTouchIfNotScaled(event: MotionEvent): Boolean {
        directionDetector.handleTouchEvent(event)
        return when (swipeDirection) {
            SwipeDirection.Up, SwipeDirection.Down ->
                if (isSwipeToDismissAllowed && !wasScaled && isImagePagerIdle) {
                    swipeDismissHandler.onTouch(views.rootContainer, event)
                } else true
            // A plain tap leaves swipeDirection null (it's reset on every ACTION_DOWN and
            // only set once a real swipe is detected). The null case must be forwarded to
            // the pager just like Left/Right so child views receive a clean DOWN/UP pair;
            // otherwise a single tap on the video control would be dropped.
            else -> views.attachmentPager.dispatchTouchEvent(event)
        }
    }

    private fun handleSwipeViewMove(translationY: Float, translationLimit: Int) {
        val alpha = 1.0f - 1.0f / translationLimit.toFloat() / 4f * abs(translationY)
        views.backgroundView.alpha = alpha
        views.dismissContainer.alpha = alpha
        overlayView?.alpha = alpha
    }

    private fun dispatchOverlayTouch(event: MotionEvent): Boolean =
            overlayView?.let { it.isVisible && it.dispatchTouchEvent(event) } ?: false

    private fun createSwipeToDismissHandler() = SwipeToDismissHandler(
            swipeView = views.dismissContainer,
            shouldAnimateDismiss = { shouldAnimateDismiss() },
            onDismiss = { animateClose() },
            onSwipeViewMove = ::handleSwipeViewMove
    )

    private fun createSwipeDirectionDetector() = SwipeDirectionDetector(this) { swipeDirection = it }

    private fun createScaleGestureDetector() = ScaleGestureDetector(this, ScaleGestureDetector.SimpleOnScaleGestureListener())

    private fun createGestureDetector() = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (isImagePagerIdle) handleSingleTap(e, isOverlayWasClicked)
            return false
        }
    })

    override fun onEvent(event: AttachmentEvents) {
        (overlayView as? AttachmentEventListener)?.onEvent(event)
    }

    protected open fun shouldAnimateDismiss(): Boolean = true

    protected open fun animateClose() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Android 15+ — colors handled via WindowCompat, nothing needed
        } else {
            @Suppress("DEPRECATION")
            window.statusBarColor = ContextCompat.getColor(this, R.color.half_transparent_status_bar)
        }
        finish()
    }
    fun handle(commands: AttachmentCommands) {
        val holder = attachmentsAdapter.recyclerView?.findViewHolderForAdapterPosition(currentPosition)
        android.util.Log.d("PauseDebug", "handle($commands) currentPosition=$currentPosition holder=$holder")
        (holder as? BaseViewHolder)?.handleCommand(commands)
    }
    private fun hideSystemUI() {
        overlayView?.isVisible = false
        systemUiVisibility = false
    }

    private fun showSystemUI() {
        overlayView?.isVisible = true
        systemUiVisibility = true
    }
}
