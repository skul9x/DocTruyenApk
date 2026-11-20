package com.skul9x.doctruyen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.skul9x.doctruyen.utils.DebugLogger
import com.skul9x.doctruyen.utils.LogType

class DebugLogActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILTER = "EXTRA_FILTER"
    }

    private lateinit var tvLogs: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnClear: ImageButton
    private lateinit var btnCopy: MaterialButton
    private lateinit var btnShare: MaterialButton
    private lateinit var chipGroupFilter: ChipGroup
    private var currentFilter: LogType? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_debug_log)
        
        initViews()
        parseInitialFilter()
        setupListeners()
        observeLogs()
    }
    
    private fun initViews() {
        tvLogs = findViewById(R.id.tvLogs)
        btnBack = findViewById(R.id.btnBack)
        btnClear = findViewById(R.id.btnClear)
        btnCopy = findViewById(R.id.btnCopy)
        btnShare = findViewById(R.id.btnShare)
        chipGroupFilter = findViewById(R.id.chipGroupFilter)
    }

    private fun parseInitialFilter() {
        val filterName = intent.getStringExtra(EXTRA_FILTER)
        when (filterName) {
            LogType.TTS_TIMING.name -> {
                currentFilter = LogType.TTS_TIMING
                findViewById<Chip>(R.id.chipTtsTiming)?.isChecked = true
            }
            LogType.ERROR.name -> {
                currentFilter = LogType.ERROR
                findViewById<Chip>(R.id.chipErrors)?.isChecked = true
            }
            else -> {
                currentFilter = null
                findViewById<Chip>(R.id.chipAll)?.isChecked = true
            }
        }
    }
    
    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }
        
        btnClear.setOnClickListener {
            DebugLogger.clear()
            updateLogsDisplay()
            Toast.makeText(this, "Đã xóa nhật ký", Toast.LENGTH_SHORT).show()
        }
        
        btnCopy.setOnClickListener {
            val logs = DebugLogger.getFormattedLogs(currentFilter)
            if (logs.isEmpty()) {
                Toast.makeText(this, "Không có nhật ký để sao chép", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("DocTruyen Logs", logs)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Đã sao chép vào bộ nhớ tạm", Toast.LENGTH_SHORT).show()
        }
        
        btnShare.setOnClickListener {
            val logs = DebugLogger.getFormattedLogs(currentFilter)
            if (logs.isEmpty()) {
                Toast.makeText(this, "Không có nhật ký để chia sẻ", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "DocTruyen Debug Logs")
                putExtra(Intent.EXTRA_TEXT, logs)
            }
            startActivity(Intent.createChooser(intent, "Chia sẻ log lỗi"))
        }

        chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when {
                checkedIds.contains(R.id.chipTtsTiming) -> LogType.TTS_TIMING
                checkedIds.contains(R.id.chipErrors) -> LogType.ERROR
                else -> null
            }
            updateLogsDisplay()
        }
    }
    
    private fun updateLogsDisplay() {
        val rawLogs = DebugLogger.getFormattedLogs(currentFilter)
        if (rawLogs.isNotEmpty()) {
            tvLogs.text = rawLogs
            tvLogs.setTextColor(0xFF333333.toInt())
            tvLogs.post {
                val layout = tvLogs.layout ?: return@post
                val scrollAmount = layout.getLineTop(tvLogs.lineCount) - tvLogs.height
                if (scrollAmount > 0) {
                    tvLogs.scrollTo(0, scrollAmount)
                }
            }
        } else {
            val emptyMessage = if (!DebugLogger.isDebugEnabled) {
                "⚠️ Tính năng ghi nhật ký đang TẮT.\n\nĐể ghi lại nhật ký lỗi và đo đạc, vui lòng vào Cài đặt -> '🛠️ Nhật ký & Chẩn đoán' và BẬT 'Bật ghi nhật ký đo đạc (Debug Logs)'."
            } else {
                when (currentFilter) {
                    LogType.ERROR -> "✅ Tuyệt vời! Hệ thống không ghi nhận lỗi nào phát sinh."
                    LogType.TTS_TIMING -> "⏱️ Chưa có dữ liệu đo đạc thời gian đọc TTS.\n\nHãy vào đọc truyện và bấm 'Nghe truyện' để thu thập thông số hiệu năng."
                    else -> "✨ Hiện chưa có bản ghi nhật ký nào.\n\nCác sự kiện hệ thống và thông số hoạt động sẽ tự động xuất hiện tại đây."
                }
            }
            tvLogs.text = emptyMessage
            tvLogs.setTextColor(0xFF757575.toInt())
            tvLogs.scrollTo(0, 0)
        }
    }

    private fun observeLogs() {
        updateLogsDisplay()
        DebugLogger.logs.observe(this) { 
            updateLogsDisplay()
        }
    }
}
