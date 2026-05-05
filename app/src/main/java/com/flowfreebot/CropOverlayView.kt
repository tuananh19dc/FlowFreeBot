package com.flowfreebot

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class CropOverlayView(context: Context) : View(context) {
    // Tọa độ mặc định của khung quét
    var cropRect = RectF(100f, 300f, 800f, 1000f)

    private val paintDim = Paint().apply { color = Color.parseColor("#99000000") } // Nền đen mờ 60%
    private val paintClear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) } // Đục lỗ trong suốt
    private val paintBorder = Paint().apply {
        color = Color.parseColor("#00E676") // Màu xanh lá giống hình 2
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val paintHandle = Paint().apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.FILL
    }

    private var draggingEdge = 0 // 1:Trái, 2:Trên, 3:Phải, 4:Dưới, 5:Giữa
    private var lastX = 0f
    private var lastY = 0f
    private val TOLERANCE = 80f // Khu vực bắt chạm (rộng ra để dễ bấm)

    init {
        // Bắt buộc phải có dòng này để PorterDuff.Mode.CLEAR hoạt động
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 1. Phủ mờ toàn màn hình
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintDim)
        // 2. Đục lỗ trong suốt tại vùng chọn
        canvas.drawRect(cropRect, paintClear)
        // 3. Vẽ viền xanh
        canvas.drawRect(cropRect, paintBorder)

        // 4. Vẽ 4 cục vuông ở 4 góc để tạo cảm giác giống hình 2
        val r = 15f
        canvas.drawRect(cropRect.left - r, cropRect.top - r, cropRect.left + r, cropRect.top + r, paintHandle)
        canvas.drawRect(cropRect.right - r, cropRect.top - r, cropRect.right + r, cropRect.top + r, paintHandle)
        canvas.drawRect(cropRect.left - r, cropRect.bottom - r, cropRect.left + r, cropRect.bottom + r, paintHandle)
        canvas.drawRect(cropRect.right - r, cropRect.bottom - r, cropRect.right + r, cropRect.bottom + r, paintHandle)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = x
                lastY = y
                draggingEdge = getTouchedArea(x, y)
                return draggingEdge != 0
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY
                lastX = x
                lastY = y

                when (draggingEdge) {
                    1 -> cropRect.left = (cropRect.left + dx).coerceAtMost(cropRect.right - 100f)
                    2 -> cropRect.top = (cropRect.top + dy).coerceAtMost(cropRect.bottom - 100f)
                    3 -> cropRect.right = (cropRect.right + dx).coerceAtLeast(cropRect.left + 100f)
                    4 -> cropRect.bottom = (cropRect.bottom + dy).coerceAtLeast(cropRect.top + 100f)
                    5 -> cropRect.offset(dx, dy) // Di chuyển nguyên khối
                }
                invalidate() // Vẽ lại
            }
            MotionEvent.ACTION_UP -> draggingEdge = 0
        }
        return true
    }

    // Nhận diện ngón tay đang chạm vào viền nào hoặc chạm vào giữa
    private fun getTouchedArea(x: Float, y: Float): Int {
        if (abs(x - cropRect.left) < TOLERANCE && y > cropRect.top && y < cropRect.bottom) return 1
        if (abs(y - cropRect.top) < TOLERANCE && x > cropRect.left && x < cropRect.right) return 2
        if (abs(x - cropRect.right) < TOLERANCE && y > cropRect.top && y < cropRect.bottom) return 3
        if (abs(y - cropRect.bottom) < TOLERANCE && x > cropRect.left && x < cropRect.right) return 4
        if (cropRect.contains(x, y)) return 5
        return 0
    }
}