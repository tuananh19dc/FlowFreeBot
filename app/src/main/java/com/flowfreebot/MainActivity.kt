package com.flowfreebot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID   = "flow_solver_channel"
        const val NOTIFICATION_CHANNEL_NAME = "Flow Free Bot"
        const val EXTRA_RESULT_CODE         = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA         = "EXTRA_RESULT_DATA"
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button

    // Cờ kiểm tra trạng thái bot đang chạy
    private var isBotRunning = false

    // ─── Launcher: quyền chụp màn hình ────────────────────────────────────────
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            launchService(result.resultCode, result.data!!)
        } else {
            setStatus("❌ Từ chối quyền chụp màn hình. Nhấn START lại.", "#FF4757")
        }
    }

    // ─── Launcher: quyền overlay ──────────────────────────────────────────────
    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            checkAccessibilityThenCapture()
        } else {
            setStatus("❌ Chưa cấp quyền Overlay. Nhấn START lại.", "#FF4757")
        }
    }

    // ─── Launcher: màn hình Trợ năng ──────────────────────────────────────────
    private val accessibilityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (isAccessibilityEnabled()) {
            requestScreenCapture()
        } else {
            setStatus("❌ Chưa bật Trợ năng. Nhấn START lại.", "#FF4757")
        }
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStart)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        createNotificationChannel()
        updateStatusFromState()

        btnStart.setOnClickListener { checkStep1Overlay() }
    }

    override fun onResume() {
        super.onResume()
        // Ngăn cập nhật lại trạng thái nếu bot đã chạy
        if (!isBotRunning) {
            updateStatusFromState()
        }
    }

    // ─── Cập nhật UI theo trạng thái hiện tại ──────────────────────────────────
    private fun updateStatusFromState() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasA11y   = isAccessibilityEnabled()

        val sb = StringBuilder()
        sb.appendLine(if (hasOverlay) "✅ Quyền Overlay: OK" else "⬜ Quyền Overlay: Chưa cấp")
        sb.appendLine(if (hasA11y)   "✅ Trợ năng: OK"      else "⬜ Trợ năng: Chưa bật")

        if (hasOverlay && hasA11y) {
            sb.append("👆 Nhấn START BOT để chụp màn hình & bắt đầu!")
            setStatus(sb.toString(), "#32ff7e")
        } else {
            sb.append("👆 Nhấn START BOT để cấp từng quyền.")
            setStatus(sb.toString(), "#fff200")
        }
    }

    // ─── Bước 1: Overlay ───────────────────────────────────────────────────────
    private fun checkStep1Overlay() {
        if (!Settings.canDrawOverlays(this)) {
            setStatus("⏳ Đang mở cài đặt Quyền Overlay...", "#fff200")
            overlayLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else {
            checkAccessibilityThenCapture()
        }
    }

    // ─── Bước 2: Trợ năng ──────────────────────────────────────────────────────
    private fun checkAccessibilityThenCapture() {
        if (!isAccessibilityEnabled()) {
            setStatus("⏳ Vui lòng BẬT 'Flow Free Bot' trong Trợ năng...", "#fff200")
            Toast.makeText(
                this,
                "Tìm 'Flow Free Bot' → bật lên → quay lại app!",
                Toast.LENGTH_LONG
            ).show()
            accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            requestScreenCapture()
        }
    }

    // ─── Bước 3: Chụp màn hình ─────────────────────────────────────────────────
    private fun requestScreenCapture() {
        setStatus("⏳ Đang xin quyền chụp màn hình...", "#fff200")
        projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    // ─── Bước 4: Khởi động Service ─────────────────────────────────────────────
    private fun launchService(resultCode: Int, data: Intent) {
        isBotRunning = true // Bật cờ xác nhận bot đang chạy

        val intent = Intent(this, AutoSolverService::class.java).apply {
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)

        setStatus(
            "✅ Dịch vụ đang chạy!\n" +
                    "Nút ▶ PLAY sẽ nổi trên màn hình.\n" +
                    "Mở game Flow Free rồi nhấn PLAY!",
            "#32ff7e"
        )
        btnStart.isEnabled = false
    }

    // ─── Kiểm tra Accessibility Service có bật chưa ────────────────────────────
    private fun isAccessibilityEnabled(): Boolean {
        val serviceName = "$packageName/${FlowBotAccessibilityService::class.java.canonicalName}"
        return try {
            val enabled = Settings.Secure.getInt(
                contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED, 0
            )
            if (enabled == 0) return false

            val services = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            TextUtils.SimpleStringSplitter(':').let { splitter ->
                splitter.setString(services)
                splitter.any { it.equals(serviceName, ignoreCase = true) }
            }
        } catch (e: Exception) {
            false
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────
    private fun setStatus(msg: String, hexColor: String) {
        tvStatus.text = msg
        try {
            tvStatus.setTextColor(android.graphics.Color.parseColor(hexColor))
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannelCompat.Builder(
            NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW
        ).setName(NOTIFICATION_CHANNEL_NAME).build()
        NotificationManagerCompat.from(this).createNotificationChannel(ch)
    }
}