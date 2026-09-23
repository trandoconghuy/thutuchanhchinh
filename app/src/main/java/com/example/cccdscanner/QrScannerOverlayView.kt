package com.example.cccdscanner

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.min

class QrScannerOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val shade = Paint().apply { color = Color.argb(105, 3, 11, 25) }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(71, 164, 255); style = Paint.Style.STROKE
        strokeWidth = 4f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val tracked = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(77, 231, 167); style = Paint.Style.STROKE
        strokeWidth = 3f * density; strokeCap = Paint.Cap.ROUND
    }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f * density
    }
    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * density }
    private var scanProgress = 0f
    private var focusX = -1f
    private var focusY = -1f
    private var focusAlpha = 0f
    private var trackedRect: RectF? = null
    private var success = false

    private val scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1550
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { scanProgress = it.animatedValue as Float; invalidate() }
        start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width * .78f, height * .47f)
        val frame = RectF((width - side) / 2f, (height - side) / 2f - 12f * density, (width + side) / 2f, (height + side) / 2f - 12f * density)
        canvas.drawRect(0f, 0f, width.toFloat(), frame.top, shade)
        canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), shade)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, shade)
        canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, shade)

        corner.color = if (success) Color.rgb(75, 230, 157) else Color.rgb(71, 164, 255)
        val length = 46f * density
        val path = Path().apply {
            moveTo(frame.left, frame.top + length); lineTo(frame.left, frame.top); lineTo(frame.left + length, frame.top)
            moveTo(frame.right - length, frame.top); lineTo(frame.right, frame.top); lineTo(frame.right, frame.top + length)
            moveTo(frame.right, frame.bottom - length); lineTo(frame.right, frame.bottom); lineTo(frame.right - length, frame.bottom)
            moveTo(frame.left + length, frame.bottom); lineTo(frame.left, frame.bottom); lineTo(frame.left, frame.bottom - length)
        }
        canvas.drawPath(path, corner)

        if (!success) {
            val y = frame.top + frame.height() * scanProgress
            scanPaint.shader = LinearGradient(frame.left, y, frame.right, y,
                intArrayOf(Color.TRANSPARENT, Color.rgb(80, 189, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawLine(frame.left + 12f * density, y, frame.right - 12f * density, y, scanPaint)
        }
        trackedRect?.let { canvas.drawRoundRect(it, 14f * density, 14f * density, tracked) }
        if (focusAlpha > 0f && focusX >= 0f) {
            focusPaint.alpha = (focusAlpha * 255).toInt()
            canvas.drawCircle(focusX, focusY, 25f * density, focusPaint)
        }
    }

    fun track(box: Rect, imageWidth: Int, imageHeight: Int, rotation: Int) {
        val rotated = rotation == 90 || rotation == 270
        val sourceWidth = if (rotated) imageHeight.toFloat() else imageWidth.toFloat()
        val sourceHeight = if (rotated) imageWidth.toFloat() else imageHeight.toFloat()
        val scale = maxOf(width / sourceWidth, height / sourceHeight)
        val dx = (width - sourceWidth * scale) / 2f
        val dy = (height - sourceHeight * scale) / 2f
        trackedRect = RectF(box.left * scale + dx, box.top * scale + dy, box.right * scale + dx, box.bottom * scale + dy)
        invalidate()
    }

    fun pulseFocus(x: Float, y: Float) {
        focusX = x; focusY = y
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 650
            addUpdateListener { focusAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun showSuccess() {
        success = true
        scanAnimator.cancel()
        invalidate()
    }

    fun showSearching() {
        trackedRect = null
        invalidate()
    }

    override fun onDetachedFromWindow() {
        scanAnimator.cancel()
        super.onDetachedFromWindow()
    }
}
