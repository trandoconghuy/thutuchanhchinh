package com.example.cccdscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class SignatureView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val strokes = mutableListOf<Path>()
    private var activePath: Path? = null
    private var baseSignature: Bitmap? = null
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(18, 52, 112)
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 2.2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        setBackgroundColor(Color.rgb(250, 252, 255))
        isFocusable = true
        contentDescription = "Khung ký tên"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        baseSignature?.let { bitmap ->
            canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, width, height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        strokes.forEach { canvas.drawPath(it, ink) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePath = Path().also { it.moveTo(event.x, event.y); strokes.add(it) }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val historySize = event.historySize
                for (i in 0 until historySize) activePath?.lineTo(event.getHistoricalX(i), event.getHistoricalY(i))
                activePath?.lineTo(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePath?.lineTo(event.x, event.y)
                activePath = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
        }
        return true
    }

    fun clear() {
        baseSignature = null
        strokes.clear()
        activePath = null
        invalidate()
    }

    fun hasSignature(): Boolean = baseSignature != null || strokes.isNotEmpty()

    fun setSignature(bitmap: Bitmap?) {
        baseSignature = bitmap
        strokes.clear()
        invalidate()
    }

    fun asBitmap(): Bitmap? {
        if (!hasSignature() || width <= 0 || height <= 0) return null
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            baseSignature?.let { base ->
                canvas.drawBitmap(base, null, android.graphics.Rect(0, 0, width, height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
            strokes.forEach { canvas.drawPath(it, ink) }
        }
    }
}
