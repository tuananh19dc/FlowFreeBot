package com.flowfreebot
import kotlinx.coroutines.withTimeoutOrNull
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.coroutines.resume

class AutoSolverService : Service() {

    companion object {
        private const val TAG      = "AutoSolverService"
        private const val NOTIF_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingRoot:  View
    private lateinit var tvPlayBtn:     TextView
    private lateinit var tvStatusLbl:   TextView

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay:  VirtualDisplay?  = null
    private var imageReader:     ImageReader?      = null

    private var screenWidth  = 0
    private var screenHeight = 0
    private var screenDpi    = 0

    private val mainHandler  = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var isSolving    = false

    // Vùng lưới game & Số ô do người dùng chọn thủ công
    private var selectedCropRect: Rect? = null
    private var selectedGridSize: Int = 6

    // BIẾN LƯU KHOẢNG CÁCH BÙ TRỪ THANH TRẠNG THÁI
    private var screenOffsetX = 0
    private var screenOffsetY = 0

    // ════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ════════════════════════════════════════════════════════════════════════

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        getScreenMetrics()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(MainActivity.EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(MainActivity.EXTRA_RESULT_DATA)

        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
            stopSelf(); return START_NOT_STICKY
        }

        setupMediaProjection(resultCode, resultData)
        mainHandler.postDelayed({ showFloatingButton() }, 500)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupProjection()
        if (::floatingRoot.isInitialized) {
            try { windowManager.removeView(floatingRoot) } catch (_: Exception) {}
        }
    }

    private fun getScreenMetrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b        = windowManager.currentWindowMetrics.bounds
            screenWidth  = b.width()
            screenHeight = b.height()
            screenDpi    = resources.displayMetrics.densityDpi
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(m)
            screenWidth  = m.widthPixels
            screenHeight = m.heightPixels
            screenDpi    = m.densityDpi
        }
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val mpMgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpMgr.getMediaProjection(resultCode, data)
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "FlowCapture", screenWidth, screenHeight, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, null
        )
    }

    private fun acquireLatestBitmap(): Bitmap? {
        var image: Image? = null
        return try {
            image = imageReader?.acquireLatestImage() ?: return null
            val plane      = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val rowPadding = plane.rowStride - plane.pixelStride * screenWidth
            val bmp = Bitmap.createBitmap(screenWidth + rowPadding / plane.pixelStride, screenHeight, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
        } catch (e: Exception) { null } finally { image?.close() }
    }

    private fun cleanupProjection() {
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  UI: FLOATING BUTTON & CROP OVERLAY
    // ════════════════════════════════════════════════════════════════════════

    private fun showFloatingButton() {
        val ctx = this
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setBackgroundColor(Color.parseColor("#DD1e2732"))
        }

        val tvDrag = TextView(ctx).apply {
            text = "· · ·"; textSize = 11f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#a4b0be"))
            setPadding(dp(20), dp(2), dp(20), dp(2))
        }

        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }

        val tvCropBtn = TextView(ctx).apply {
            text = "🔲 VÙNG"; textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#FF9F43"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { showCropUI() }
        }

        tvPlayBtn = TextView(ctx).apply {
            text = "▶ PLAY"; textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#6c5ce7"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { if (!isSolving) onPlayClicked() }
        }

        val marginParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) }
        btnRow.addView(tvCropBtn); btnRow.addView(tvPlayBtn, marginParams)

        tvStatusLbl = TextView(ctx).apply {
            text = "Flow Bot 🤖"; textSize = 10f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#a4b0be"))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        container.addView(tvDrag); container.addView(btnRow); container.addView(tvStatusLbl)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = dp(16); y = dp(200) }

        floatingRoot = container
        windowManager.addView(floatingRoot, params)

        var initX = 0; var initY = 0; var initTX = 0f; var initTY = 0f; var dragging = false
        tvDrag.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { initX = params.x; initY = params.y; initTX = event.rawX; initTY = event.rawY; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initTX).toInt(); val dy = (event.rawY - initTY).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) dragging = true
                    if (dragging) { params.x = initX - dx; params.y = initY + dy; windowManager.updateViewLayout(floatingRoot, params) }
                    true
                }
                else -> false
            }
        }
    }

    private fun showCropUI() {
        val ctx = this
        val cropContainer = FrameLayout(ctx)
        val cropView = CropOverlayView(ctx)
        selectedCropRect?.let { cropView.cropRect = RectF(it) }
        cropContainer.addView(cropView)

        val bottomBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#EE1e2732"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val btnCancel = Button(ctx).apply {
            text = "✖ Hủy"; setBackgroundColor(Color.parseColor("#FF4757")); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
            setOnClickListener { windowManager.removeView(cropContainer) }
        }

        // --- CỤM CHỌN SỐ Ô (GRID SIZE) ---
        var tempGrid = selectedGridSize
        val gridSelector = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
        }
        val btnMinus = Button(ctx).apply { text = "-"; setTextColor(Color.WHITE); setBackgroundColor(Color.DKGRAY); setPadding(0,0,0,0) }
        val tvGrid = TextView(ctx).apply { text = "${tempGrid}x${tempGrid}"; setTextColor(Color.WHITE); textSize = 16f; setPadding(dp(8),0,dp(8),0) }
        val btnPlus = Button(ctx).apply { text = "+"; setTextColor(Color.WHITE); setBackgroundColor(Color.DKGRAY); setPadding(0,0,0,0) }

        btnMinus.setOnClickListener { if(tempGrid > 4) { tempGrid--; tvGrid.text = "${tempGrid}x${tempGrid}" } }
        btnPlus.setOnClickListener { if(tempGrid < 15) { tempGrid++; tvGrid.text = "${tempGrid}x${tempGrid}" } }
        gridSelector.addView(btnMinus); gridSelector.addView(tvGrid); gridSelector.addView(btnPlus)

        val btnConfirm = Button(ctx).apply {
            text = "✔ Xong"
            setBackgroundColor(Color.parseColor("#32ff7e"))
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
            setOnClickListener {
                // ĐO KHOẢNG CÁCH LỆCH CỦA THANH TRẠNG THÁI
                val loc = IntArray(2)
                cropContainer.getLocationOnScreen(loc)
                screenOffsetX = loc[0]
                screenOffsetY = loc[1]

                // KHÔNG CỘNG OFFSET VÀO ĐÂY ĐỂ KHUNG KHÔNG BỊ RỚT!
                selectedCropRect = Rect(
                    cropView.cropRect.left.toInt(),
                    cropView.cropRect.top.toInt(),
                    cropView.cropRect.right.toInt(),
                    cropView.cropRect.bottom.toInt()
                )
                selectedGridSize = tempGrid
                windowManager.removeView(cropContainer)
                Toast.makeText(ctx, "Đã lưu lưới ${tempGrid}x${tempGrid}!", Toast.LENGTH_SHORT).show()
            }
        }

        bottomBar.addView(btnCancel); bottomBar.addView(gridSelector); bottomBar.addView(btnConfirm)
        val barParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.BOTTOM }
        cropContainer.addView(bottomBar, barParams)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            // ĐÃ BỎ CỜ FLAG_LAYOUT_NO_LIMITS Ở ĐÂY
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(cropContainer, params)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PIPELINE
    // ════════════════════════════════════════════════════════════════════════

    private fun onPlayClicked() {
        if (selectedCropRect == null) {
            Toast.makeText(this, "Vui lòng nhấn [🔲 VÙNG] để chọn lưới game trước!", Toast.LENGTH_LONG).show()
            return
        }

        isSolving = true
        mainHandler.post { tvPlayBtn.text = "⏳"; tvStatusLbl.text = "Đang xử lý..." }

        serviceScope.launch {
            var fullBitmap: Bitmap? = null
            var croppedBitmap: Bitmap? = null
            try {
                setFloatStatus("📸 Chụp màn hình...")
                delay(300)

                fullBitmap = acquireLatestBitmap() ?: throw Exception("Không lấy được ảnh màn hình!")

                // BÙ TRỪ TỌA ĐỘ VÀO PHÚT CHÓT ĐỂ CẮT ẢNH & VUỐT
                val realRect = Rect(
                    selectedCropRect!!.left + screenOffsetX,
                    selectedCropRect!!.top + screenOffsetY,
                    selectedCropRect!!.right + screenOffsetX,
                    selectedCropRect!!.bottom + screenOffsetY
                )

                val safeLeft = realRect.left.coerceAtLeast(0)
                val safeTop = realRect.top.coerceAtLeast(0)
                val safeRight = realRect.right.coerceAtMost(fullBitmap.width)
                val safeBottom = realRect.bottom.coerceAtMost(fullBitmap.height)

                croppedBitmap = Bitmap.createBitmap(fullBitmap, safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop)

                val rows = selectedGridSize
                val cols = selectedGridSize

                setFloatStatus("🎨 Quét màu (${rows}×${cols})...")
                val board = FlowFreeSolver.scanBitmapToBoard(croppedBitmap, rows, cols)

                // ---------------------------------------------------------
                // TẠO CHUỖI MA TRẬN X-QUANG ĐỂ XEM BOT ĐANG BỊ "MÙ" THẾ NÀO
                // ---------------------------------------------------------
                val matrixStr = java.lang.StringBuilder()
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        matrixStr.append(board[r][c]).append(" ")
                    }
                    matrixStr.append("\n")
                }
                Log.d(TAG, "MA TRẬN BOT THẤY:\n$matrixStr")

                setFloatStatus("🧠 Tìm lời giải DFS...")
                val paths: Map<String, List<Cell>> = FlowFreeSolver.solveDFS(board, rows, cols)
                    ?: throw Exception("MA TRẬN BOT NHÌN THẤY LÀ:\n$matrixStr\n(Hãy chụp màn hình lỗi này gửi tôi!)")

                setFloatStatus("✍️ Đang vuốt (${paths.size} màu)...")

                // TRUYỀN realRect VÀO HÀM ĐỂ TỌA ĐỘ VUỐT KHỚP VỚI MÀN HÌNH CHỤP
                executeGestures(paths, rows, cols, realRect)

                setFloatStatus("✅ Hoàn thành!")

            } catch (e: Exception) {
                setFloatStatus("❌ Lỗi")
                // Hiển thị ma trận lỗi bằng Toast dài
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AutoSolverService, e.message, Toast.LENGTH_LONG).show()
                }
            } finally {
                fullBitmap?.recycle(); croppedBitmap?.recycle()
                withContext(Dispatchers.Main) { tvPlayBtn.text = "▶ PLAY"; isSolving = false }
            }
        }
    }

    private suspend fun executeGestures(paths: Map<String, List<Cell>>, numRows: Int, numCols: Int, cropRect: Rect?) {
        val a11y = FlowBotAccessibilityService.instance ?: return
        for ((color, cellPath) in paths) {
            if (cellPath.size < 2) continue

            val pixelPath: List<Pair<Int, Int>> = cellPath.map { cell: Cell ->
                FlowFreeSolver.cellToScreenCoord(cell, numRows, numCols, screenWidth, screenHeight, cropRect)
            }

            // Tính toán thời gian dự kiến (200ms/ô)
            val expectedDuration = (pixelPath.size * 200L).coerceAtLeast(400L)

            // LẮP RƠ-LE: Cho phép vuốt tối đa (Thời gian dự kiến + dư ra 3 giây). Quá hạn là tự ngắt!
            withTimeoutOrNull(expectedDuration + 3000L) {
                suspendCancellableCoroutine<Unit> { cont ->
                    mainHandler.post {
                        a11y.performDrag(pixelPath) { if (cont.isActive) cont.resume(Unit) }
                    }
                }
            }

            // Nghỉ 1 giây cho mượt mà trước khi vẽ màu tiếp theo
            delay(1000)
        }
    }

    private suspend fun setFloatStatus(msg: String) = withContext(Dispatchers.Main) {
        if (::tvStatusLbl.isInitialized) tvStatusLbl.text = msg
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(MainActivity.NOTIFICATION_CHANNEL_ID, MainActivity.NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, MainActivity.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Flow Free Bot 🤖").setContentText("Đang chạy nền").setSmallIcon(android.R.drawable.ic_media_play).build()
    }
}