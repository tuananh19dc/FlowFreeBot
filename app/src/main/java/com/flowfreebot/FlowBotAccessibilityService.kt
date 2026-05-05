package com.flowfreebot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * FlowBotAccessibilityService
 *
 * ▸ KHÔNG CẦN ROOT
 * ▸ Dùng API dispatchGesture() có sẵn trong Android >= 7.0
 * ▸ Chỉ cần người dùng BẬT service trong Cài đặt → Trợ năng
 *
 * Cách hoạt động:
 *   AutoSolverService tính toán đường đi → gọi instance.performDrag()
 *   → service dùng GestureDescription vẽ đường vuốt lên màn hình thật.
 */
class FlowBotAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FlowBotA11y"

        /** Singleton instance – AutoSolverService sẽ lấy từ đây để gọi gesture */
        var instance: FlowBotAccessibilityService? = null
            private set

        /** Kiểm tra service có đang chạy không */
        fun isRunning() = instance != null
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────────
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "✅ AccessibilityService đã kết nối!")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "AccessibilityService đã dừng.")
    }

    // Bắt buộc override nhưng bot không cần lắng nghe event
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ──────────────────────────────────────────────────────────────────────────
    //  GESTURE: KÉO MỘT ĐƯỜNG LIÊN TỤC QUA NHIỀU ĐIỂM
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Thực hiện một thao tác drag (nhấn và kéo) qua toàn bộ [pixelPath].
     *
     * @param pixelPath  danh sách (x, y) pixel trên màn hình thật
     * @param onDone     callback khi gesture hoàn tất (có thể null)
     *
     * Thuật toán:
     *  - Tạo một Path Android chạy qua tất cả điểm.
     *  - Bọc trong StrokeDescription với thời gian đủ dài để game nhận được.
     *  - dispatchGesture() tự lo press-down, move, release.
     */
    fun performDrag(
        pixelPath: List<Pair<Int, Int>>,
        onDone: (() -> Unit)? = null
    ) {
        if (pixelPath.size < 2) {
            onDone?.invoke()
            return
        }

        // Dùng 1 Path duy nhất để Android không bao giờ đánh rơi lệnh
        val path = android.graphics.Path()
        val (startX, startY) = pixelPath[0]
        path.moveTo(startX.toFloat(), startY.toFloat())

        for (i in 1 until pixelPath.size) {
            val (x, y) = pixelPath[i]
            path.lineTo(x.toFloat(), y.toFloat())

            // KỸ THUẬT ĐÓNG CỌC: Thêm 1 điểm lệch đúng 1 pixel ngay tại tâm ô.
            // Ép Android vẽ góc vuông tuyệt đối, triệt tiêu 100% bệnh "bo góc" lẹm màu khác!
            path.lineTo(x.toFloat() + 1f, y.toFloat() + 1f)
        }

        // Tốc độ: 150ms/ô - Cực kỳ đầm tay, game nhận diện chuẩn xác
        val durationMs = (pixelPath.size * 150L).coerceAtLeast(300L)

        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()

        // Bắn lệnh vuốt
        val success = dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription) {
                onDone?.invoke()
            }
            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription) {
                onDone?.invoke()
            }
        }, null)

        // CHỐNG TREO BOT: Nếu Android quá tải từ chối lệnh, báo Done luôn để Bot vuốt màu tiếp theo
        if (!success) {
            android.util.Log.e("FlowBot", "Hệ điều hành từ chối vuốt màu này!")
            onDone?.invoke()
        }
    }
}
