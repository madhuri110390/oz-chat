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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
    private var systemUiVisibility = true

    private var overlayView: View? = null
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

    fun setSourceProvider(sourceProvider: AttachmentSourceProvider) {
        attachmentsAdapter.attachmentSourceProvider = sourceProvider
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setDecorViewFullScreen()
        views = ActivityAttachmentViewerBinding.inflate(layoutInflater)
        setContentView(views.root)
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
        }

        directionDetector = createSwipeDirectionDetector()
        gestureDetector = createGestureDetector()
        scaleDetector = createScaleGestureDetector()

        swipeDismissHandler = createSwipeToDismissHandler()
        views.rootContainer.setOnTouchListener(swipeDismissHandler)
        views.rootContainer.viewTreeObserver.addOnGlobalLayoutListener {
            swipeDismissHandler.translationLimit = views.dismissContainer.height / 4
        }
        ViewCompat.setOnApplyWindowInsetsListener(views.rootContainer) { _, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            overlayView?.updatePadding(top = systemBarsInsets.top, bottom = systemBarsInsets.bottom)
            topInset = systemBarsInsets.top
            bottomInset = systemBarsInsets.bottom
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setDecorViewFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window,true)
            window.decorView.windowInsetsController?.hide(WindowInsets.Type.systemBars())
            window.decorView.windowInsetsController?.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
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
    }

    override fun onPause() {
        attachmentsAdapter.onPause(currentPosition)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        attachmentsAdapter.onResume(currentPosition)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (overlayView?.isVisible == true && overlayView?.dispatchTouchEvent(ev) == true) return true

        handleUpDownEvent(ev)

        return if (swipeDirection == null && (scaleDetector.isInProgress || ev.pointerCount > 1 || wasScaled)) {
            wasScaled = true
            views.attachmentPager.dispatchTouchEvent(ev)
        } else {
            if (isScaled()) super.dispatchTouchEvent(ev) else handleTouchIfNotScaled(ev)
        }
    }

    private fun handleUpDownEvent(event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_UP) handleEventActionUp(event)
        if (event.action == MotionEvent.ACTION_DOWN) handleEventActionDown(event)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
    }

    private fun handleEventActionDown(event: MotionEvent) {
        swipeDirection = null
        wasScaled = false
        views.attachmentPager.dispatchTouchEvent(event)
        swipeDismissHandler.onTouch(views.rootContainer, event)
        isOverlayWasClicked = dispatchOverlayTouch(event)
    }

    private fun handleEventActionUp(event: MotionEvent) {
        swipeDismissHandler.onTouch(views.rootContainer, event)
        views.attachmentPager.dispatchTouchEvent(event)
        isOverlayWasClicked = dispatchOverlayTouch(event)
    }

    private fun handleSingleTap(event: MotionEvent, isOverlayWasClicked: Boolean) {
        if (overlayView != null && !isOverlayWasClicked) {
            toggleOverlayViewVisibility()
            super.dispatchTouchEvent(event)
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
            SwipeDirection.Left, SwipeDirection.Right -> views.attachmentPager.dispatchTouchEvent(event)
            else -> true
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
        @Suppress("DEPRECATION")
        window.statusBarColor = ContextCompat.getColor(this, R.color.half_transparent_status_bar)
        finish()
    }

    fun handle(commands: AttachmentCommands) {
        (attachmentsAdapter.recyclerView?.findViewHolderForAdapterPosition(currentPosition) as? BaseViewHolder)?.handleCommand(commands)
    }

    private fun hideSystemUI() {
        overlayView?.isVisible = false
        systemUiVisibility = false
 /*       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window,false)
            window.decorView.windowInsetsController?.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            window.decorView.windowInsetsController?.hide(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = ContextCompat.getColor(this, R.color.half_transparent_status_bar)
        @Suppress("DEPRECATION")
        window.navigationBarColor = ContextCompat.getColor(this, R.color.half_transparent_status_bar)*/
    }

    private fun showSystemUI() {
        overlayView?.isVisible = true
        systemUiVisibility = true
/*        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window,false)
            window.decorView.windowInsetsController?.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = ContextCompat.getColor(this, R.color.half_transparent_status_bar)
        @Suppress("DEPRECATION")
        window.navigationBarColor = ContextCompat.getColor(this, R.color.half_transparent_status_bar)*/
    }
}
