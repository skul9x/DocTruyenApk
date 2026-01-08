package com.skul9x.doctruyen

import android.os.Bundle
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.skul9x.doctruyen.network.HostingVerifier
import com.skul9x.doctruyen.network.RetrofitClient
import com.skul9x.doctruyen.utils.UserConfig

class SettingsActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var etBaseUrl: TextInputEditText
    private lateinit var btnSave: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        btnBack = findViewById(R.id.btnBack)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        btnSave = findViewById(R.id.btnSave)

        // Load current URL
        val currentUrl = UserConfig.getBaseUrl(this)
        etBaseUrl.setText(currentUrl)

        btnBack.setOnClickListener {
            finish()
        }
        
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnViewLogs).setOnClickListener {
                startActivity(android.content.Intent(this, DebugLogActivity::class.java))
        }

        // Initialize TTS Sliders
        val sliderPitch = findViewById<com.google.android.material.slider.Slider>(R.id.sliderPitch)
        val sliderSpeed = findViewById<com.google.android.material.slider.Slider>(R.id.sliderSpeed)
        val tvPitchValue = findViewById<android.widget.TextView>(R.id.tvPitchValue)
        val tvSpeedValue = findViewById<android.widget.TextView>(R.id.tvSpeedValue)
        
        // Helper to format slider value
        fun formatSliderValue(value: Float): String = String.format("%.1fx", value)
        
        sliderPitch.value = UserConfig.getTtsPitch(this)
        sliderSpeed.value = UserConfig.getTtsSpeed(this)
        
        // Set initial values
        tvPitchValue.text = formatSliderValue(sliderPitch.value)
        tvSpeedValue.text = formatSliderValue(sliderSpeed.value)
        
        sliderPitch.addOnChangeListener { _, value, _ ->
            UserConfig.setTtsPitch(this, value)
            tvPitchValue.text = formatSliderValue(value)
        }
        
        sliderSpeed.addOnChangeListener { _, value, _ ->
            UserConfig.setTtsSpeed(this, value)
            tvSpeedValue.text = formatSliderValue(value)
        }
        
        // Voice Selection Logic
        val tvCurrentVoice = findViewById<android.widget.TextView>(R.id.tvCurrentVoice)
        val btnSelectVoice = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSelectVoice)
        
        // Init temporary TTS to fetch voices
        var tempTts: android.speech.tts.TextToSpeech? = null
        tempTts = android.speech.tts.TextToSpeech(this) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                // Pre-check current voice name for UI
                val savedVoice = UserConfig.getTtsVoice(this)
                if (savedVoice != null) {
                    val allVoices = tempTts?.voices?.toList() ?: emptyList()
                    val viVoices = allVoices.filter { it.locale.language == "vi" }
                    val displayVoices = if (viVoices.isNotEmpty()) viVoices else allVoices
                    val sortedVoices = displayVoices.sortedBy { it.name }
                    
                    val index = sortedVoices.indexOfFirst { it.name == savedVoice }
                    if (index != -1) {
                         tvCurrentVoice.text = getString(R.string.settings_voice_current, getString(R.string.settings_voice_person) + " ${index + 1}")
                    } else {
                         tvCurrentVoice.text = getString(R.string.settings_voice_current, getString(R.string.settings_voice_custom))
                    }
                }
            }
        }
        
        btnSelectVoice.setOnClickListener {
            tempTts?.let { tts ->
                val allVoices = tts.voices.toList()
                // Prefer Vietnamese
                val viVoices = allVoices.filter { it.locale.language == "vi" }
                val displayVoices = if (viVoices.isNotEmpty()) viVoices else allVoices
                
                // Sort comfortably
                val sortedVoices = displayVoices.sortedBy { it.name }
                
                val voiceNames = sortedVoices.map { it.name }.toTypedArray()
                val friendlyNames = voiceNames.mapIndexed { index, _ -> getString(R.string.settings_voice_person) + " ${index + 1}" }.toTypedArray()
                
                if (voiceNames.isEmpty()) {
                    Toast.makeText(this, getString(R.string.settings_voice_none), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                val currentVoice = UserConfig.getTtsVoice(this)
                val currentIndex = if (currentVoice != null) {
                    voiceNames.indexOf(currentVoice).coerceAtLeast(0)
                } else 0
                
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.settings_select_voice_dialog))
                    .setSingleChoiceItems(friendlyNames, currentIndex) { dialog, which ->
                        val selectedName = voiceNames[which]
                        val friendlyName = friendlyNames[which]
                        
                        UserConfig.setTtsVoice(this, selectedName)
                        tvCurrentVoice.text = getString(R.string.settings_voice_current, friendlyName)
                        Toast.makeText(this, getString(R.string.settings_voice_current, friendlyName), Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        }

        btnSave.setOnClickListener {
            // ... existing save logic ...
            val newUrl = etBaseUrl.text.toString().trim()
            if (newUrl.isEmpty()) {
                Toast.makeText(this, getString(R.string.msg_enter_url), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Save new URL
            UserConfig.setBaseUrl(this, newUrl)
            
            // Re-verify connection
            // We pass the new URL here. HostingVerifier will handle the cookie update if successful
            val formattedUrl = UserConfig.getBaseUrl(this) // Get back the formatted one
            
            btnSave.isEnabled = false
            btnSave.text = getString(R.string.settings_checking)
            
            HostingVerifier.verify(this, formattedUrl) { success ->
                runOnUiThread {
                    btnSave.isEnabled = true
                    btnSave.text = getString(R.string.settings_save_btn)
                    
                    if (success) {
                        RetrofitClient.resetClient() // Force rebuild with new URL
                        Toast.makeText(this, getString(R.string.settings_success), Toast.LENGTH_LONG).show()
                        finish() // Return to Main to reload
                    } else {
                        Toast.makeText(this, getString(R.string.settings_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // No explicit cleanup for tempTts needed as it's just for list fetching, 
        // but good practice might be to shutdown if we stored it as a class property
    }
}
