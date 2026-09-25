package com.example.cccdscanner

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

/** Image view optimized for tall contract previews: fit-width, pinch zoom, pan and double tap. */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {
    private val drawMatrix = Matrix()
    private var baseScale = 1f
    private var zoom = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val previous = zoom
                zoom = (zoom * detector.scaleFactor).coerceIn(1f, 5f)
                val factor = zoom / previous
                drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                constrainMatrix()
                imageMatrix = drawMatrix
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true

            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (zoom > 1.05f) resetToFitWidth()
                else {
                    zoom = 2f
                    drawMatrix.postScale(2f, 2f, event.x, event.y)
                    constrainMatrix()
                    imageMatrix = drawMatrix
                }
                return true
            }
        })

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
        isHapticFeedbackEnabled = false
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { resetToFitWidth() }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width > 0 && drawable != null) resetToFitWidth()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                if (scaleDetector.isInProgress) {
                    lastX = event.x; lastY = event.y
                } else {
                    drawMatrix.postTranslate(event.x - lastX, event.y - lastY)
                    lastX = event.x; lastY = event.y
                    constrainMatrix(); imageMatrix = drawMatrix
                    postInvalidateOnAnimation()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                if (remaining < event.pointerCount) { lastX = event.getX(remaining); lastY = event.getY(remaining) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun resetToFitWidth() {
        val image = drawable ?: return
        if (width <= 0 || image.intrinsicWidth <= 0) return
        baseScale = width.toFloat() / image.intrinsicWidth
        zoom = 1f
        drawMatrix.reset()
        drawMatrix.postScale(baseScale, baseScale)
        drawMatrix.postTranslate(0f, 0f)
        constrainMatrix()
        imageMatrix = drawMatrix
    }

    private fun constrainMatrix() {
        val image = drawable ?: return
        val values = FloatArray(9)
        drawMatrix.getValues(values)
        val scaledWidth = image.intrinsicWidth * values[Matrix.MSCALE_X]
        val scaledHeight = image.intrinsicHeight * values[Matrix.MSCALE_Y]
        var dx = 0f
        var dy = 0f
        val left = values[Matrix.MTRANS_X]
        val top = values[Matrix.MTRANS_Y]

        dx = if (scaledWidth <= width) (width - scaledWidth) / 2f - left
        else when {
            left > 0f -> -left
            left + scaledWidth < width -> width - (left + scaledWidth)
            else -> 0f
        }
        dy = if (scaledHeight <= height) (height - scaledHeight) / 2f - top
        else when {
            top > 0f -> -top
            top + scaledHeight < height -> height - (top + scaledHeight)
            else -> 0f
        }
        drawMatrix.postTranslate(dx, dy)
    }
}
