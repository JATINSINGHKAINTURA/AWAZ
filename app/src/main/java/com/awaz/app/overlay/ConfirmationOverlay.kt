package com.awaz.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

enum class ConfirmationResult {
    CONFIRMED,
    DENIED,
    TIMEOUT
}

/**
 * Full-screen confirmation overlay built with classic Android Views (FrameLayout + ImageViews).
 * Never uses ComposeView to prevent crashes inside service windows without a LifecycleOwner.
 * Uses TYPE_ACCESSIBILITY_OVERLAY from AccessibilityService context.
 * Guards against double-show, removes on timeout or destroy, and completes result exactly once.
 */
class ConfirmationOverlay(private val serviceContext: Context) : ConfirmationPresenter {

    override suspend fun confirm(summary: String): ConfirmationResult {
        return show().await()
    }

    private val windowManager: WindowManager =
        serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeView: View? = null
    private var activeDeferred: CompletableDeferred<ConfirmationResult>? = null
    private var timeoutRunnable: Runnable? = null
    private var isShowing: Boolean = false

    @Synchronized
    fun show(): Deferred<ConfirmationResult> {
        // Guard against double-show
        if (isShowing && activeDeferred != null) {
            return activeDeferred!!
        }

        val deferred = CompletableDeferred<ConfirmationResult>()
        activeDeferred = deferred
        isShowing = true

        mainHandler.post {
            try {
                val layoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            or WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                }

                // Root dark semi-transparent container
                val rootLayout = FrameLayout(serviceContext).apply {
                    setBackgroundColor(0xCC000000.toInt())
                }

                val buttonsRow = LinearLayout(serviceContext).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    val params = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.CENTER
                    }
                    layoutParams = params
                }

                val density = serviceContext.resources.displayMetrics.density
                val buttonSizePx = (140 * density).toInt()
                val iconSizePx = (80 * density).toInt()
                val marginPx = (24 * density).toInt()

                // Giant Green Tick Button (Confirm)
                val confirmButton = FrameLayout(serviceContext).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xFF10B981.toInt()) // High contrast emerald
                    }
                    elevation = 16f * density

                    val iconView = ImageView(serviceContext).apply {
                        setImageDrawable(createCheckmarkDrawable(density))
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                    }
                    addView(iconView, FrameLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                        gravity = Gravity.CENTER
                    })

                    setOnClickListener {
                        finishWithResult(ConfirmationResult.CONFIRMED)
                    }
                }

                val confirmParams = LinearLayout.LayoutParams(buttonSizePx, buttonSizePx).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setMargins(0, marginPx, 0, marginPx)
                }
                buttonsRow.addView(confirmButton, confirmParams)

                // Giant Red Cross Button (Deny)
                val denyButton = FrameLayout(serviceContext).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xFFEF4444.toInt()) // High contrast crimson
                    }
                    elevation = 16f * density

                    val iconView = ImageView(serviceContext).apply {
                        setImageDrawable(createCrossDrawable(density))
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                    }
                    addView(iconView, FrameLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                        gravity = Gravity.CENTER
                    })

                    setOnClickListener {
                        finishWithResult(ConfirmationResult.DENIED)
                    }
                }

                val denyParams = LinearLayout.LayoutParams(buttonSizePx, buttonSizePx).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setMargins(0, marginPx, 0, marginPx)
                }
                buttonsRow.addView(denyButton, denyParams)

                rootLayout.addView(buttonsRow)

                // 20-second auto-timeout: counts as DENIED
                timeoutRunnable = Runnable {
                    finishWithResult(ConfirmationResult.TIMEOUT)
                }
                mainHandler.postDelayed(timeoutRunnable!!, 20_000L)

                windowManager.addView(rootLayout, layoutParams)
                activeView = rootLayout
            } catch (e: Exception) {
                finishWithResult(ConfirmationResult.DENIED)
            }
        }

        return deferred
    }

    @Synchronized
    private fun finishWithResult(result: ConfirmationResult) {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null

        activeView?.let { view ->
            mainHandler.post {
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {}
            }
            activeView = null
        }

        isShowing = false
        // Complete deferred exactly once
        activeDeferred?.complete(result)
        activeDeferred = null
    }

    @Synchronized
    fun dismiss() {
        finishWithResult(ConfirmationResult.DENIED)
    }

    private fun createCheckmarkDrawable(density: Float): android.graphics.drawable.Drawable {
        return object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 8f * density
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }

            override fun draw(canvas: android.graphics.Canvas) {
                val b = bounds
                val w = b.width().toFloat()
                val h = b.height().toFloat()
                val path = android.graphics.Path().apply {
                    moveTo(b.left + w * 0.25f, b.top + h * 0.52f)
                    lineTo(b.left + w * 0.45f, b.top + h * 0.72f)
                    lineTo(b.left + w * 0.78f, b.top + h * 0.32f)
                }
                canvas.drawPath(path, paint)
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    private fun createCrossDrawable(density: Float): android.graphics.drawable.Drawable {
        return object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 8f * density
                strokeCap = android.graphics.Paint.Cap.ROUND
            }

            override fun draw(canvas: android.graphics.Canvas) {
                val b = bounds
                val w = b.width().toFloat()
                val h = b.height().toFloat()
                val margin = w * 0.28f

                canvas.drawLine(b.left + margin, b.top + margin, b.right - margin, b.bottom - margin, paint)
                canvas.drawLine(b.right - margin, b.top + margin, b.left + margin, b.bottom - margin, paint)
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    companion object : ConfirmationPresenter {
        private var instance: ConfirmationOverlay? = null

        override suspend fun confirm(summary: String): ConfirmationResult {
            val service = com.awaz.app.service.AccessibilityServiceHolder.service.value
                ?: return ConfirmationResult.DENIED
            val overlay = instance ?: ConfirmationOverlay(service).also { instance = it }
            return overlay.show().await()
        }

        @Synchronized
        fun show(context: Context, onResult: (ConfirmationResult) -> Unit) {
            val overlay = instance ?: ConfirmationOverlay(context).also { instance = it }
            val deferred = overlay.show()
            deferred.invokeOnCompletion {
                if (!deferred.isCancelled) {
                    onResult(deferred.getCompleted())
                }
            }
        }

        @Synchronized
        fun dismiss() {
            instance?.dismiss()
            instance = null
        }
    }
}

