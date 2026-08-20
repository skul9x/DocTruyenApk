package com.skul9x.doctruyen

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.skul9x.doctruyen.network.HostingVerifier
import com.skul9x.doctruyen.network.RetrofitClient
import com.skul9x.doctruyen.tts.GoogleTTSManager
import com.skul9x.doctruyen.tts.ITTSManager
import com.skul9x.doctruyen.tts.vieneu.VoicePresets
import com.skul9x.doctruyen.utils.UserConfig

/**
 * Manages lazy lifecycle and on-demand binding of GoogleTTSManager for SettingsActivity.
 */
class SettingsTtsHolder(
    private val contextProvider: () -> Context,
    private val onVoiceDisplayRefresh: () -> Unit,
    internal var googleTtsFactory: ((Context, (ITTSManager.TTSState) -> Unit) -> GoogleTTSManager) = { ctx, callback ->
        GoogleTTSManager(ctx, onStateChange = callback)
    }
) {
    internal var tempGoogleTts: GoogleTTSManager? = null
        private set

    private var isInitializing = false

    @Synchronized
    fun getOrCreateGoogleTts(): GoogleTTSManager {
        tempGoogleTts?.let { return it }
        if (isInitializing) {
            tempGoogleTts?.let { return it }
        }
        isInitializing = true
        try {
            val instance = googleTtsFactory(contextProvider()) { state ->
                if (state == ITTSManager.TTSState.READY && !isInitializing) {
                    onVoiceDisplayRefresh()
                }
            }
            tempGoogleTts = instance
            if (instance.currentState == ITTSManager.TTSState.READY) {
                onVoiceDisplayRefresh()
            }
            return instance
        } finally {
            isInitializing = false
        }
    }

    fun onEngineChanged(engine: ITTSManager.EngineType) {
        if (engine == ITTSManager.EngineType.GOOGLE_TTS) {
            getOrCreateGoogleTts()
        }
    }

    fun onDestroy() {
        tempGoogleTts?.shutdown()
        tempGoogleTts = null
    }
}

open class SettingsActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var etBaseUrl: TextInputEditText
    private lateinit var btnSave: MaterialButton
    private lateinit var toggleEngineGroup: MaterialButtonToggleGroup
    private lateinit var btnEngineVieNeu: MaterialButton
    private lateinit var btnEngineGoogle: MaterialButton
    private lateinit var tvEngineBadge: TextView

    private lateinit var cardModelPrecision: com.google.android.material.card.MaterialCardView
    private lateinit var togglePrecisionGroup: MaterialButtonToggleGroup
    private lateinit var btnPrecisionInt8: MaterialButton
    private lateinit var btnPrecisionFp32: MaterialButton
    private lateinit var tvPrecisionBadge: TextView

    private lateinit var cardPrebuffering: com.google.android.material.card.MaterialCardView
    private lateinit var togglePrebufferGroup: MaterialButtonToggleGroup
    private lateinit var btnPrebuffer1Chunk: MaterialButton
    private lateinit var btnPrebuffer2Chunks: MaterialButton
    private lateinit var btnPrebuffer3Chunks: MaterialButton
    private lateinit var tvPrebufferBadge: TextView

    private lateinit var tvCurrentVoice: TextView
    private lateinit var tvVoiceSubInfo: TextView
    private lateinit var btnSelectVoice: MaterialButton

    private lateinit var sliderPitch: Slider
    private lateinit var sliderSpeed: Slider
    private lateinit var tvPitchValue: TextView
    private lateinit var tvSpeedValue: TextView
    private lateinit var btnResetPitchSpeed: MaterialButton

    private lateinit var sliderPauseSentence: Slider
    private lateinit var sliderPauseParagraph: Slider
    private lateinit var sliderPauseClause: Slider
    private lateinit var tvPauseSentenceValue: TextView
    private lateinit var tvPauseParagraphValue: TextView
    private lateinit var tvPauseClauseValue: TextView

    private lateinit var btnPresetStandard: MaterialButton
    private lateinit var btnPresetFast: MaterialButton
    private lateinit var btnPresetEmotional: MaterialButton

    private lateinit var switchDebugLogging: SwitchMaterial
    private lateinit var btnViewTimingLogs: MaterialButton
    private lateinit var btnViewLogs: MaterialButton

    // Temporary GoogleTTS instance for querying system/Google voices if selected (lazy initialized)
    internal val ttsHolder = SettingsTtsHolder(
        contextProvider = { this },
        onVoiceDisplayRefresh = { runOnUiThread { updateActiveVoiceDisplay() } }
    )

    internal val tempGoogleTts: GoogleTTSManager?
        get() = ttsHolder.tempGoogleTts

    internal fun getOrCreateGoogleTts(): GoogleTTSManager = ttsHolder.getOrCreateGoogleTts()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        bindViews()
        setupHeaderAndConnection()
        setupEngineSelector()
        setupPrecisionSelector()
        setupPrebufferSelector()
        setupVoiceSelection()
        setupVoiceModulation()
        setupCadenceAndPauses()
        setupDiagnostics()
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        btnSave = findViewById(R.id.btnSave)

        toggleEngineGroup = findViewById(R.id.toggleEngineGroup)
        btnEngineVieNeu = findViewById(R.id.btnEngineVieNeu)
        btnEngineGoogle = findViewById(R.id.btnEngineGoogle)
        tvEngineBadge = findViewById(R.id.tvEngineBadge)

        cardModelPrecision = findViewById(R.id.cardModelPrecision)
        togglePrecisionGroup = findViewById(R.id.togglePrecisionGroup)
        btnPrecisionInt8 = findViewById(R.id.btnPrecisionInt8)
        btnPrecisionFp32 = findViewById(R.id.btnPrecisionFp32)
        tvPrecisionBadge = findViewById(R.id.tvPrecisionBadge)

        cardPrebuffering = findViewById(R.id.cardPrebuffering)
        togglePrebufferGroup = findViewById(R.id.togglePrebufferGroup)
        btnPrebuffer1Chunk = findViewById(R.id.btnPrebuffer1Chunk)
        btnPrebuffer2Chunks = findViewById(R.id.btnPrebuffer2Chunks)
        btnPrebuffer3Chunks = findViewById(R.id.btnPrebuffer3Chunks)
        tvPrebufferBadge = findViewById(R.id.tvPrebufferBadge)

        tvCurrentVoice = findViewById(R.id.tvCurrentVoice)
        tvVoiceSubInfo = findViewById(R.id.tvVoiceSubInfo)
        btnSelectVoice = findViewById(R.id.btnSelectVoice)

        sliderPitch = findViewById(R.id.sliderPitch)
        sliderSpeed = findViewById(R.id.sliderSpeed)
        tvPitchValue = findViewById(R.id.tvPitchValue)
        tvSpeedValue = findViewById(R.id.tvSpeedValue)
        btnResetPitchSpeed = findViewById(R.id.btnResetPitchSpeed)

        sliderPauseSentence = findViewById(R.id.sliderPauseSentence)
        sliderPauseParagraph = findViewById(R.id.sliderPauseParagraph)
        sliderPauseClause = findViewById(R.id.sliderPauseClause)
        tvPauseSentenceValue = findViewById(R.id.tvPauseSentenceValue)
        tvPauseParagraphValue = findViewById(R.id.tvPauseParagraphValue)
        tvPauseClauseValue = findViewById(R.id.tvPauseClauseValue)

        btnPresetStandard = findViewById(R.id.btnPresetStandard)
        btnPresetFast = findViewById(R.id.btnPresetFast)
        btnPresetEmotional = findViewById(R.id.btnPresetEmotional)

        switchDebugLogging = findViewById(R.id.switchDebugLogging)
        btnViewTimingLogs = findViewById(R.id.btnViewTimingLogs)
        btnViewLogs = findViewById(R.id.btnViewLogs)
    }

    private fun setupHeaderAndConnection() {
        btnBack.setOnClickListener {
            finish()
        }

        val currentUrl = UserConfig.getBaseUrl(this)
        etBaseUrl.setText(currentUrl)

        btnSave.setOnClickListener {
            val newUrl = etBaseUrl.text.toString().trim()
            if (newUrl.isEmpty()) {
                Toast.makeText(this, getString(R.string.msg_enter_url), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            UserConfig.setBaseUrl(this, newUrl)
            val formattedUrl = UserConfig.getBaseUrl(this)

            btnSave.isEnabled = false
            btnSave.text = getString(R.string.settings_checking)

            HostingVerifier.verify(this, formattedUrl) { success ->
                runOnUiThread {
                    btnSave.isEnabled = true
                    btnSave.text = getString(R.string.settings_save_btn)

                    if (success) {
                        RetrofitClient.resetClient()
                        Toast.makeText(this, getString(R.string.settings_success), Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        Toast.makeText(this, getString(R.string.settings_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun setupEngineSelector() {
        val currentEngine = UserConfig.getTtsEngine(this)
        if (currentEngine == ITTSManager.EngineType.GOOGLE_TTS) {
            toggleEngineGroup.check(R.id.btnEngineGoogle)
            tvEngineBadge.text = getString(R.string.settings_engine_google_badge)
            cardModelPrecision.visibility = View.GONE
            cardPrebuffering.visibility = View.GONE
        } else {
            toggleEngineGroup.check(R.id.btnEngineVieNeu)
            tvEngineBadge.text = getString(R.string.settings_engine_vieneu_badge)
            cardModelPrecision.visibility = View.VISIBLE
            cardPrebuffering.visibility = View.VISIBLE
        }

        toggleEngineGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newEngine = when (checkedId) {
                    R.id.btnEngineGoogle -> ITTSManager.EngineType.GOOGLE_TTS
                    else -> ITTSManager.EngineType.VIENEU
                }
                UserConfig.setTtsEngine(this, newEngine)

                // Notify ReadingService to switch engine immediately
                try {
                    val serviceIntent = android.content.Intent(this, com.skul9x.doctruyen.service.ReadingService::class.java).apply {
                        action = com.skul9x.doctruyen.service.ReadingService.ACTION_SWITCH_ENGINE
                        putExtra(com.skul9x.doctruyen.service.ReadingService.EXTRA_ENGINE_TYPE, newEngine.name)
                    }
                    startService(serviceIntent)
                } catch (e: Throwable) {
                    // Ignore if service cannot be started directly
                }

                if (newEngine == ITTSManager.EngineType.GOOGLE_TTS) {
                    tvEngineBadge.text = getString(R.string.settings_engine_google_badge)
                    cardModelPrecision.visibility = View.GONE
                    cardPrebuffering.visibility = View.GONE
                    Toast.makeText(this, getString(R.string.settings_engine_switched, getString(R.string.settings_engine_google)), Toast.LENGTH_SHORT).show()
                } else {
                    tvEngineBadge.text = getString(R.string.settings_engine_vieneu_badge)
                    cardModelPrecision.visibility = View.VISIBLE
                    cardPrebuffering.visibility = View.VISIBLE
                    Toast.makeText(this, getString(R.string.settings_engine_switched, getString(R.string.settings_engine_vieneu)), Toast.LENGTH_SHORT).show()
                }

                updateActiveVoiceDisplay()
            }
        }
    }

    private fun setupPrecisionSelector() {
        val currentPrecision = UserConfig.getTtsPrecision(this)
        if (currentPrecision == "FP32") {
            togglePrecisionGroup.check(R.id.btnPrecisionFp32)
            tvPrecisionBadge.text = getString(R.string.settings_precision_fp32_badge)
        } else {
            togglePrecisionGroup.check(R.id.btnPrecisionInt8)
            tvPrecisionBadge.text = getString(R.string.settings_precision_int8_badge)
        }

        togglePrecisionGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newPrecision = when (checkedId) {
                    R.id.btnPrecisionFp32 -> "FP32"
                    else -> "INT8"
                }
                UserConfig.setTtsPrecision(this, newPrecision)

                if (newPrecision == "FP32") {
                    tvPrecisionBadge.text = getString(R.string.settings_precision_fp32_badge)
                    Toast.makeText(this, getString(R.string.settings_precision_switched, "FP32"), Toast.LENGTH_SHORT).show()
                } else {
                    tvPrecisionBadge.text = getString(R.string.settings_precision_int8_badge)
                    Toast.makeText(this, getString(R.string.settings_precision_switched, "INT8"), Toast.LENGTH_SHORT).show()
                }

                // Propagate to ReadingService
                try {
                    val serviceIntent = android.content.Intent(this, com.skul9x.doctruyen.service.ReadingService::class.java).apply {
                        action = com.skul9x.doctruyen.service.ReadingService.ACTION_SWITCH_PRECISION
                        putExtra(com.skul9x.doctruyen.service.ReadingService.EXTRA_PRECISION_TYPE, newPrecision)
                    }
                    startService(serviceIntent)
                } catch (e: Throwable) {
                    // Ignore if service cannot be started directly
                }
            }
        }
    }

    private fun setupPrebufferSelector() {
        val currentChunks = UserConfig.getPrebufferChunks(this)
        when (currentChunks) {
            1 -> {
                togglePrebufferGroup.check(R.id.btnPrebuffer1Chunk)
                tvPrebufferBadge.text = getString(R.string.settings_prebuffer_1chunk_badge)
            }
            3 -> {
                togglePrebufferGroup.check(R.id.btnPrebuffer3Chunks)
                tvPrebufferBadge.text = getString(R.string.settings_prebuffer_3chunks_badge)
            }
            else -> {
                togglePrebufferGroup.check(R.id.btnPrebuffer2Chunks)
                tvPrebufferBadge.text = getString(R.string.settings_prebuffer_2chunks_badge)
            }
        }

        togglePrebufferGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newChunks = when (checkedId) {
                    R.id.btnPrebuffer1Chunk -> 1
                    R.id.btnPrebuffer3Chunks -> 3
                    else -> 2
                }
                UserConfig.setPrebufferChunks(this, newChunks)

                when (newChunks) {
                    1 -> tvPrebufferBadge.text = getString(R.string.settings_prebuffer_1chunk_badge)
                    3 -> tvPrebufferBadge.text = getString(R.string.settings_prebuffer_3chunks_badge)
                    else -> tvPrebufferBadge.text = getString(R.string.settings_prebuffer_2chunks_badge)
                }
                Toast.makeText(this, getString(R.string.settings_prebuffer_switched, newChunks), Toast.LENGTH_SHORT).show()

                // Propagate to ReadingService
                try {
                    val serviceIntent = android.content.Intent(this, com.skul9x.doctruyen.service.ReadingService::class.java).apply {
                        action = com.skul9x.doctruyen.service.ReadingService.ACTION_SWITCH_PREBUFFER_CHUNKS
                        putExtra(com.skul9x.doctruyen.service.ReadingService.EXTRA_PREBUFFER_CHUNKS, newChunks)
                    }
                    startService(serviceIntent)
                } catch (e: Throwable) {
                    // Ignore if service cannot be started directly
                }
            }
        }
    }

    private fun setupVoiceSelection() {
        VoicePresets.loadFromContext(this)
        updateActiveVoiceDisplay()

        btnSelectVoice.setOnClickListener {
            val engine = UserConfig.getTtsEngine(this)
            if (engine == ITTSManager.EngineType.GOOGLE_TTS) {
                showGoogleVoicePickerDialog()
            } else {
                showVieNeuVoicePickerDialog()
            }
        }
    }

    internal fun updateActiveVoiceDisplay() {
        val engine = UserConfig.getTtsEngine(this)
        if (engine == ITTSManager.EngineType.GOOGLE_TTS) {
            val savedGoogleVoice = UserConfig.getGoogleTtsVoice(this)
            val googleTts = getOrCreateGoogleTts()
            val available = googleTts.getAvailableVoices()
            val matched = if (!savedGoogleVoice.isNullOrEmpty()) {
                available.find { it.id == savedGoogleVoice }
            } else {
                available.firstOrNull()
            }
            val displayVoice = matched?.displayName ?: savedGoogleVoice ?: "Google Tiếng Việt (Mặc định)"
            if (::tvCurrentVoice.isInitialized) {
                tvCurrentVoice.text = getString(R.string.settings_voice_active, displayVoice)
            }
            if (::tvVoiceSubInfo.isInitialized) {
                tvVoiceSubInfo.text = matched?.description ?: "Google TTS • Hệ thống Android • Chuẩn ngôn ngữ Tiếng Việt"
            }
        } else {
            val activeVoice = UserConfig.getTtsVoice(this) ?: VoicePresets.defaultVoiceName
            val preset = VoicePresets.getVoice(activeVoice)
            if (::tvCurrentVoice.isInitialized) {
                tvCurrentVoice.text = getString(R.string.settings_voice_active, activeVoice)
            }
            if (::tvVoiceSubInfo.isInitialized) {
                if (preset != null) {
                    val formattedDesc = preset.getFormattedDescription()
                    tvVoiceSubInfo.text = if (formattedDesc.isNotEmpty()) formattedDesc else "Neural TTS • Giọng đọc AI tự nhiên"
                } else {
                    tvVoiceSubInfo.text = "Neural TTS • Giọng đọc AI tự nhiên"
                }
            }
        }
    }

    data class VoiceOption(
        val id: String,
        val title: String,
        val subtitle: String
    )

    private fun showCustomVoicePickerDialog(
        title: String,
        options: List<VoiceOption>,
        selectedId: String,
        onSelected: (VoiceOption) -> Unit
    ) {
        val selectedIndex = options.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        var dialog: AlertDialog? = null

        val adapter = object : ArrayAdapter<VoiceOption>(this, R.layout.dialog_item_voice, options) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.dialog_item_voice, parent, false)
                val item = getItem(position) ?: return view

                val layoutItem = view.findViewById<View>(R.id.layoutVoiceItem)
                val rbSelected = view.findViewById<RadioButton>(R.id.rbVoiceSelected)
                val tvTitle = view.findViewById<TextView>(R.id.tvVoiceItemTitle)
                val tvSubtitle = view.findViewById<TextView>(R.id.tvVoiceItemSubtitle)

                tvTitle.text = item.title
                if (item.subtitle.isNotEmpty()) {
                    tvSubtitle.text = item.subtitle
                    tvSubtitle.visibility = View.VISIBLE
                } else {
                    tvSubtitle.visibility = View.GONE
                }

                val isSelected = (position == selectedIndex)
                rbSelected.isChecked = isSelected
                layoutItem.isSelected = isSelected
                layoutItem.isActivated = isSelected

                return view
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setAdapter(adapter) { _, which ->
                val selected = options[which]
                onSelected(selected)
                dialog?.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.show()
        dialog.listView?.divider = null
        dialog.listView?.dividerHeight = 0
    }

    private fun showVieNeuVoicePickerDialog() {
        val available = VoicePresets.availableVoices
        val voiceList = if (available.isNotEmpty()) available else listOf("Trúc Ly", "Minh Đức", "Phạm Tuyên", "Thái Sơn", "Xuân Vĩnh", "Thanh Bình", "Ngọc Linh", "Đoan Trang")

        val options = voiceList.map { name ->
            val preset = VoicePresets.getVoice(name)
            val desc = preset?.getFormattedDescription() ?: ""
            VoiceOption(
                id = name,
                title = name,
                subtitle = desc
            )
        }

        val currentVoice = UserConfig.getTtsVoice(this) ?: VoicePresets.defaultVoiceName

        showCustomVoicePickerDialog(
            title = getString(R.string.settings_select_voice_dialog),
            options = options,
            selectedId = currentVoice
        ) { selected ->
            UserConfig.setTtsVoice(this, selected.id)
            updateActiveVoiceDisplay()
            Toast.makeText(this, getString(R.string.settings_voice_current, selected.title), Toast.LENGTH_SHORT).show()
        }
    }

    internal fun showGoogleVoicePickerDialog() {
        val googleTts = getOrCreateGoogleTts()

        val availableVoices = googleTts.getAvailableVoices()
        if (availableVoices.isEmpty()) {
            Toast.makeText(this, getString(R.string.settings_voice_none), Toast.LENGTH_SHORT).show()
            return
        }

        val options = availableVoices.map { voice ->
            val sub = if (voice.description.isNotEmpty()) "${voice.gender} • ${voice.description}" else voice.gender
            VoiceOption(
                id = voice.id,
                title = voice.displayName,
                subtitle = sub
            )
        }

        val currentVoice = UserConfig.getGoogleTtsVoice(this) ?: availableVoices.firstOrNull()?.id ?: ""

        showCustomVoicePickerDialog(
            title = getString(R.string.settings_select_voice_dialog),
            options = options,
            selectedId = currentVoice
        ) { selected ->
            UserConfig.setGoogleTtsVoice(this, selected.id)
            updateActiveVoiceDisplay()
            Toast.makeText(this, getString(R.string.settings_voice_current, selected.title), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupVoiceModulation() {
        fun formatSliderValue(value: Float): String = String.format("%.1fx", value)

        sliderPitch.value = UserConfig.getTtsPitch(this)
        sliderSpeed.value = UserConfig.getTtsSpeed(this)

        tvPitchValue.text = formatSliderValue(sliderPitch.value)
        tvSpeedValue.text = formatSliderValue(sliderSpeed.value)

        sliderPitch.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                UserConfig.setTtsPitch(this, value)
                tvPitchValue.text = formatSliderValue(value)
            }
        }

        sliderSpeed.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                UserConfig.setTtsSpeed(this, value)
                tvSpeedValue.text = formatSliderValue(value)
            }
        }

        btnResetPitchSpeed.setOnClickListener {
            UserConfig.setTtsPitch(this, 1.0f)
            UserConfig.setTtsSpeed(this, 1.0f)
            sliderPitch.value = 1.0f
            sliderSpeed.value = 1.0f
            tvPitchValue.text = "1.0x"
            tvSpeedValue.text = "1.0x"
            Toast.makeText(this, getString(R.string.settings_reset_pitch_speed_done), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupCadenceAndPauses() {
        fun formatMs(ms: Int): String = "$ms ms"

        fun updatePauseUIFromConfig() {
            val sentence = UserConfig.getPauseSentence(this)
            val paragraph = UserConfig.getPauseParagraph(this)
            val clause = UserConfig.getPauseClause(this)

            sliderPauseSentence.value = sentence.toFloat().coerceIn(sliderPauseSentence.valueFrom, sliderPauseSentence.valueTo)
            sliderPauseParagraph.value = paragraph.toFloat().coerceIn(sliderPauseParagraph.valueFrom, sliderPauseParagraph.valueTo)
            sliderPauseClause.value = clause.toFloat().coerceIn(sliderPauseClause.valueFrom, sliderPauseClause.valueTo)

            tvPauseSentenceValue.text = formatMs(sentence)
            tvPauseParagraphValue.text = formatMs(paragraph)
            tvPauseClauseValue.text = formatMs(clause)
        }

        updatePauseUIFromConfig()

        sliderPauseSentence.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val ms = value.toInt()
                UserConfig.setPauseSentence(this, ms)
                tvPauseSentenceValue.text = formatMs(ms)
            }
        }

        sliderPauseParagraph.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val ms = value.toInt()
                UserConfig.setPauseParagraph(this, ms)
                tvPauseParagraphValue.text = formatMs(ms)
            }
        }

        sliderPauseClause.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val ms = value.toInt()
                UserConfig.setPauseClause(this, ms)
                tvPauseClauseValue.text = formatMs(ms)
            }
        }

        btnPresetStandard.setOnClickListener {
            UserConfig.applyPausePreset(this, "STANDARD")
            updatePauseUIFromConfig()
            Toast.makeText(this, getString(R.string.preset_standard), Toast.LENGTH_SHORT).show()
        }

        btnPresetFast.setOnClickListener {
            UserConfig.applyPausePreset(this, "FAST")
            updatePauseUIFromConfig()
            Toast.makeText(this, getString(R.string.preset_fast), Toast.LENGTH_SHORT).show()
        }

        btnPresetEmotional.setOnClickListener {
            UserConfig.applyPausePreset(this, "EMOTIONAL")
            updatePauseUIFromConfig()
            Toast.makeText(this, getString(R.string.preset_emotional), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupDiagnostics() {
        switchDebugLogging.isChecked = UserConfig.isDebugLoggingEnabled(this)
        switchDebugLogging.setOnCheckedChangeListener { _, isChecked ->
            UserConfig.setDebugLoggingEnabled(this, isChecked)
        }

        btnViewTimingLogs.setOnClickListener {
            startActivity(android.content.Intent(this, DebugLogActivity::class.java))
        }

        btnViewLogs.setOnClickListener {
            startActivity(android.content.Intent(this, DebugLogActivity::class.java))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsHolder.onDestroy()
    }
}
