package com.flowfreebot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

class CropOverlayView(context: Context) : View(context) {

    var cropRect = RectF()

    // Cọ vẽ viền xanh
    private val paintBox = Paint().apply {
        color = Color.parseColor("#32ff7e")
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    // Cọ vẽ 4 góc vuông cầm nắm
    private val paintCorner = Paint().apply {
        color = Color.parseColor("#32ff7e")
        style = Paint.Style.FILL
    }

    private var activeCorner = -1
    private var isDragging = false
    private var lastX = 0f
    private var lastY = 0f

    // Vùng chạm để bắt góc (rộng ra một chút để ngón tay dễ bấm hơn)
    private val touchRadius = 80f
    // Kích thước tối thiểu không cho bóp khung quá nhỏ (chống lật khung)
    private val minSize = 200f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Vẽ khung chính
        canvas.drawRect(cropRect, paintBox)

        // Vẽ 4 cục vuông ở 4 góc
        val cornerSize = 15f
        canvas.drawRect(cropRect.left - cornerSize, cropRect.top - cornerSize, cropRect.left + cornerSize, cropRect.top + cornerSize, paintCorner) // Góc trên trái
        canvas.drawRect(cropRect.right - cornerSize, cropRect.top - cornerSize, cropRect.right + cornerSize, cropRect.top + cornerSize, paintCorner) // Góc trên phải
        canvas.drawRect(cropRect.left - cornerSize, cropRect.bottom - cornerSize, cropRect.left + cornerSize, cropRect.bottom + cornerSize, paintCorner) // Góc dưới trái
        canvas.drawRect(cropRect.right - cornerSize, cropRect.bottom - cornerSize, cropRect.right + cornerSize, cropRect.bottom + cornerSize, paintCorner) // Góc dưới phải
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = x
                lastY = y
                activeCorner = getTouchedCorner(x, y)
                // Nếu không chạm vào góc nào nhưng chạm vào bên trong khung -> Kéo cả khung
                if (activeCorner == -1 && cropRect.contains(x, y)) {
                    isDragging = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY

                // Lấy giới hạn tối đa của chiều rộng/chiều cao màn hình
                val maxWidth = width.toFloat()
                val maxHeight = height.toFloat()

                if (isDragging) {
                    cropRect.offset(dx, dy)

                    // 🛡️ BỨC TƯỜNG CHẶN KÉO CẢ KHUNG: Ép khung dội ngược lại nếu vượt mép
                    if (cropRect.left < 0f) cropRect.offset(-cropRect.left, 0f)
                    if (cropRect.top < 0f) cropRect.offset(0f, -cropRect.top)
                    if (cropRect.right > maxWidth) cropRect.offset(maxWidth - cropRect.right, 0f)
                    if (cropRect.bottom > maxHeight) cropRect.offset(0f, maxHeight - cropRect.bottom)

                } else if (activeCorner != -1) {

                    // 🛡️ BỨC TƯỜNG CHẶN KÉO GÓC: Dùng coerceIn ép tọa độ nằm trong khoảng an toàn
                    when (activeCorner) {
                        0 -> { // Kéo góc Top-Left
                            cropRect.left = (cropRect.left + dx).coerceIn(0f, cropRect.right - minSize)
                            cropRect.top = (cropRect.top + dy).coerceIn(0f, cropRect.bottom - minSize)
                        }
                        1 -> { // Kéo góc Top-Right
                            cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minSize, maxWidth)
                            cropRect.top = (cropRect.top + dy).coerceIn(0f, cropRect.bottom - minSize)
                        }
                        2 -> { // Kéo góc Bottom-Right
                            cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minSize, maxWidth)
                            cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minSize, maxHeight)
                        }
                        3 -> { // Kéo góc Bottom-Left
                            cropRect.left = (cropRect.left + dx).coerceIn(0f, cropRect.right - minSize)
                            cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minSize, maxHeight)
                        }
                    }
                }
                lastX = x
                lastY = y
                invalidate() // Báo Android vẽ lại khung mới
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeCorner = -1
                isDragging = false
            }
        }
        return true
    }

    private fun getTouchedCorner(x: Float, y: Float): Int {
        if (Math.abs(x - cropRect.left) < touchRadius && Math.abs(y - cropRect.top) < touchRadius) return 0
        if (Math.abs(x - cropRect.right) < touchRadius && Math.abs(y - cropRect.top) < touchRadius) return 1
        if (Math.abs(x - cropRect.right) < touchRadius && Math.abs(y - cropRect.bottom) < touchRadius) return 2
        if (Math.abs(x - cropRect.left) < touchRadius && Math.abs(y - cropRect.bottom) < touchRadius) return 3
        return -1
    }
}