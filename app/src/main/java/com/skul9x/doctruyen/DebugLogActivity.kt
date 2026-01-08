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
import com.skul9x.doctruyen.utils.DebugLogger

class DebugLogActivity : AppCompatActivity() {

    private lateinit var tvLogs: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnClear: ImageButton
    private lateinit var btnCopy: MaterialButton
    private lateinit var btnShare: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_debug_log)
        
        initViews()
        setupListeners()
        observeLogs()
    }
    
    private fun initViews() {
        tvLogs = findViewById(R.id.tvLogs)
        btnBack = findViewById(R.id.btnBack)
        btnClear = findViewById(R.id.btnClear)
        btnCopy = findViewById(R.id.btnCopy)
        btnShare = findViewById(R.id.btnShare)
    }
    
    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }
        
        btnClear.setOnClickListener {
            DebugLogger.clear()
            Toast.makeText(this, "Đã xóa nhật ký", Toast.LENGTH_SHORT).show()
        }
        
        btnCopy.setOnClickListener {
            val logs = DebugLogger.getFormattedLogs()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("DocTruyen Logs", logs)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Đã sao chép vào bộ nhớ tạm", Toast.LENGTH_SHORT).show()
        }
        
        btnShare.setOnClickListener {
            val logs = DebugLogger.getFormattedLogs()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "DocTruyen Debug Logs")
                putExtra(Intent.EXTRA_TEXT, logs)
            }
            startActivity(Intent.createChooser(intent, "Chia sẻ log lỗi"))
        }
    }
    
    private fun observeLogs() {
        DebugLogger.logs.observe(this) { 
            tvLogs.text = DebugLogger.getFormattedLogs()
            
            // Auto scroll to bottom
            tvLogs.post {
                val scrollAmount = tvLogs.layout.getLineTop(tvLogs.lineCount) - tvLogs.height
                if (scrollAmount > 0) {
                    tvLogs.scrollTo(0, scrollAmount)
                }
            }
        }
    }
}
