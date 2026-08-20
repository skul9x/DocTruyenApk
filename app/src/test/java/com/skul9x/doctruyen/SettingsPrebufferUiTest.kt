package com.skul9x.doctruyen

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.View
import com.skul9x.doctruyen.service.ReadingService
import com.skul9x.doctruyen.tts.ITTSManager
import com.skul9x.doctruyen.tts.vieneu.NpzReader
import com.skul9x.doctruyen.tts.vieneu.OnnxSessionManager
import com.skul9x.doctruyen.tts.vieneu.TTSAudioPipeline
import com.skul9x.doctruyen.tts.vieneu.VieNeuConfig
import com.skul9x.doctruyen.tts.vieneu.VieNeuOnnxEngine
import com.skul9x.doctruyen.tts.vieneu.VieNeuTTSManager
import com.skul9x.doctruyen.tts.vieneu.VieNeuTokenizer
import com.skul9x.doctruyen.utils.UserConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Comprehensive Unit and Integration Test for Phase 03: Settings UI Pre-buffering Chunk Selector.
 *
 * Verifies:
 * 1. Initial UI selection reflects UserConfig.getPrebufferChunks (1, 2, 3).
 * 2. Toggle interaction updates UserConfig, badge descriptions, and emits ACTION_SWITCH_PREBUFFER_CHUNKS intent.
 * 3. Switching TTS engines between VieNeu and Google TTS toggles cardPrebuffering visibility (VISIBLE / GONE).
 * 4. ReadingService prebuffer chunks handling and propagation to VieNeuTTSManager & TTSAudioPipeline preBufferChunkCount.
 */
class SettingsPrebufferUiTest {

    private class FakeSharedPreferences : SharedPreferences {
        private val storage = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(storage)

        override fun getString(key: String?, defValue: String?): String? =
            (storage[key] as? String) ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return (storage[key] as? MutableSet<String>) ?: defValues
        }

        override fun getInt(key: String?, defValue: Int): Int = (storage[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (storage[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (storage[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (storage[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = storage.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(storage)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class FakeEditor(private val storage: MutableMap<String, Any>) : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) temp[key] = values
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) temp[key] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearFlag = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearFlag) storage.clear()
                temp.forEach { (k, v) ->
                    if (v == null) storage.remove(k) else storage[k] = v
                }
                temp.clear()
                clearFlag = false
            }
        }
    }

    data class CapturedIntent(
        val action: String?,
        val extras: Map<String, Any?> = emptyMap()
    )

    private lateinit var fakePrefs: FakeSharedPreferences
    private val startedIntents = mutableListOf<CapturedIntent>()

    private val dummyContext: Context = object : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.skul9x.doctruyen"
        override fun startService(service: Intent?): android.content.ComponentName? {
            return null
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun setupNativeLibrary() {
            val possiblePaths = listOf(
                "sea-g2p-android/target/release/libsea_g2p_android.so",
                "../sea-g2p-android/target/release/libsea_g2p_android.so",
                "app/src/main/jniLibs/x86_64/libsea_g2p_android.so",
                "/home/skul9x/Desktop/Test_code/DocTruyenApk-main/sea-g2p-android/target/release/libsea_g2p_android.so"
            )
            for (path in possiblePaths) {
                val file = File(path)
                if (file.exists()) {
                    try {
                        System.load(file.absolutePath)
                        break
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    @Before
    fun setup() {
        fakePrefs = FakeSharedPreferences()
        UserConfig.customPrefsProvider = { fakePrefs }
        startedIntents.clear()
    }

    @After
    fun teardown() {
        UserConfig.customPrefsProvider = null
        startedIntents.clear()
    }

    /**
     * Test controller simulating SettingsActivity prebuffer and engine UI logic.
     */
    private class SettingsPrebufferUiController(
        val context: Context,
        val onStartServiceCaptured: ((CapturedIntent) -> Unit)? = null,
        var cardPrebufferVisibility: Int = View.VISIBLE,
        var cardPrecisionVisibility: Int = View.VISIBLE,
        var checkedEngineId: Int = R.id.btnEngineVieNeu,
        var checkedPrebufferId: Int = R.id.btnPrebuffer2Chunks,
        var badgeText: String = ""
    ) {
        fun init() {
            // Engine setup
            val currentEngine = UserConfig.getTtsEngine(context)
            if (currentEngine == ITTSManager.EngineType.GOOGLE_TTS) {
                checkedEngineId = R.id.btnEngineGoogle
                cardPrecisionVisibility = View.GONE
                cardPrebufferVisibility = View.GONE
            } else {
                checkedEngineId = R.id.btnEngineVieNeu
                cardPrecisionVisibility = View.VISIBLE
                cardPrebufferVisibility = View.VISIBLE
            }

            // Prebuffer setup
            val currentChunks = UserConfig.getPrebufferChunks(context)
            when (currentChunks) {
                1 -> {
                    checkedPrebufferId = R.id.btnPrebuffer1Chunk
                    badgeText = "🚀 1 Chunk (Siêu nhanh / TTFT ~1.8s): Phát âm thanh tức thì, thích hợp máy cấu hình tốt."
                }
                3 -> {
                    checkedPrebufferId = R.id.btnPrebuffer3Chunks
                    badgeText = "🛡️ 3 Chunks (Ổn định tối đa / Chống ngắt): Phát sau ~8.0s, đệm dày tối đa chống gián đoạn khi nghe lâu."
                }
                else -> {
                    checkedPrebufferId = R.id.btnPrebuffer2Chunks
                    badgeText = "⚖️ 2 Chunks (Cân bằng / Khuyên dùng): Phát sau ~3.5s, mượt mà và không lo ngắt quãng."
                }
            }
        }

        fun onEngineSelected(engineId: Int) {
            checkedEngineId = engineId
            val newEngine = when (engineId) {
                R.id.btnEngineGoogle -> ITTSManager.EngineType.GOOGLE_TTS
                else -> ITTSManager.EngineType.VIENEU
            }
            UserConfig.setTtsEngine(context, newEngine)

            if (newEngine == ITTSManager.EngineType.GOOGLE_TTS) {
                cardPrecisionVisibility = View.GONE
                cardPrebufferVisibility = View.GONE
            } else {
                cardPrecisionVisibility = View.VISIBLE
                cardPrebufferVisibility = View.VISIBLE
            }

            onStartServiceCaptured?.invoke(
                CapturedIntent(
                    action = ReadingService.ACTION_SWITCH_ENGINE,
                    extras = mapOf(ReadingService.EXTRA_ENGINE_TYPE to newEngine.name)
                )
            )
        }

        fun onPrebufferSelected(prebufferId: Int) {
            checkedPrebufferId = prebufferId
            val newChunks = when (prebufferId) {
                R.id.btnPrebuffer1Chunk -> 1
                R.id.btnPrebuffer3Chunks -> 3
                else -> 2
            }
            UserConfig.setPrebufferChunks(context, newChunks)

            when (newChunks) {
                1 -> badgeText = "🚀 1 Chunk (Siêu nhanh / TTFT ~1.8s): Phát âm thanh tức thì, thích hợp máy cấu hình tốt."
                3 -> badgeText = "🛡️ 3 Chunks (Ổn định tối đa / Chống ngắt): Phát sau ~8.0s, đệm dày tối đa chống gián đoạn khi nghe lâu."
                else -> badgeText = "⚖️ 2 Chunks (Cân bằng / Khuyên dùng): Phát sau ~3.5s, mượt mà và không lo ngắt quãng."
            }

            onStartServiceCaptured?.invoke(
                CapturedIntent(
                    action = ReadingService.ACTION_SWITCH_PREBUFFER_CHUNKS,
                    extras = mapOf(ReadingService.EXTRA_PREBUFFER_CHUNKS to newChunks)
                )
            )
        }
    }

    @Test
    fun testSettingsPrebufferUi_initialStateAndSelectionUpdates() {
        // 1. Initial configuration: 2 Chunks (default)
        UserConfig.setTtsEngine(dummyContext, ITTSManager.EngineType.VIENEU)
        UserConfig.setPrebufferChunks(dummyContext, 2)

        val controller = SettingsPrebufferUiController(
            context = dummyContext,
            onStartServiceCaptured = { startedIntents.add(it) }
        )
        controller.init()

        assertEquals(View.VISIBLE, controller.cardPrebufferVisibility)
        assertEquals(R.id.btnPrebuffer2Chunks, controller.checkedPrebufferId)
        assertTrue(controller.badgeText.contains("2 Chunks"))
        assertTrue(controller.badgeText.contains("Cân bằng"))

        // 2. Select 1 Chunk
        controller.onPrebufferSelected(R.id.btnPrebuffer1Chunk)
        assertEquals(1, UserConfig.getPrebufferChunks(dummyContext))
        assertEquals(R.id.btnPrebuffer1Chunk, controller.checkedPrebufferId)
        assertTrue(controller.badgeText.contains("1 Chunk"))
        assertTrue(controller.badgeText.contains("Siêu nhanh"))

        assertEquals(1, startedIntents.size)
        val intent1 = startedIntents.last()
        assertEquals(ReadingService.ACTION_SWITCH_PREBUFFER_CHUNKS, intent1.action)
        assertEquals(1, intent1.extras[ReadingService.EXTRA_PREBUFFER_CHUNKS])

        // 3. Select 3 Chunks
        controller.onPrebufferSelected(R.id.btnPrebuffer3Chunks)
        assertEquals(3, UserConfig.getPrebufferChunks(dummyContext))
        assertEquals(R.id.btnPrebuffer3Chunks, controller.checkedPrebufferId)
        assertTrue(controller.badgeText.contains("3 Chunks"))
        assertTrue(controller.badgeText.contains("Ổn định"))

        assertEquals(2, startedIntents.size)
        val intent2 = startedIntents.last()
        assertEquals(ReadingService.ACTION_SWITCH_PREBUFFER_CHUNKS, intent2.action)
        assertEquals(3, intent2.extras[ReadingService.EXTRA_PREBUFFER_CHUNKS])

        // 4. Select 2 Chunks again
        controller.onPrebufferSelected(R.id.btnPrebuffer2Chunks)
        assertEquals(2, UserConfig.getPrebufferChunks(dummyContext))
        assertEquals(R.id.btnPrebuffer2Chunks, controller.checkedPrebufferId)
        assertTrue(controller.badgeText.contains("2 Chunks"))

        assertEquals(3, startedIntents.size)
        val intent3 = startedIntents.last()
        assertEquals(ReadingService.ACTION_SWITCH_PREBUFFER_CHUNKS, intent3.action)
        assertEquals(2, intent3.extras[ReadingService.EXTRA_PREBUFFER_CHUNKS])
    }

    @Test
    fun testSettingsPrebufferUi_engineSwitchingTogglesVisibility() {
        // Initially VieNeu TTS -> card is VISIBLE
        UserConfig.setTtsEngine(dummyContext, ITTSManager.EngineType.VIENEU)
        val controller = SettingsPrebufferUiController(
            context = dummyContext,
            onStartServiceCaptured = { startedIntents.add(it) }
        )
        controller.init()
        assertEquals(View.VISIBLE, controller.cardPrebufferVisibility)

        // Switch to Google TTS -> card must be GONE
        controller.onEngineSelected(R.id.btnEngineGoogle)
        assertEquals(View.GONE, controller.cardPrebufferVisibility)
        assertEquals(ITTSManager.EngineType.GOOGLE_TTS, UserConfig.getTtsEngine(dummyContext))

        // Switch back to VieNeu TTS -> card must be VISIBLE
        controller.onEngineSelected(R.id.btnEngineVieNeu)
        assertEquals(View.VISIBLE, controller.cardPrebufferVisibility)
        assertEquals(ITTSManager.EngineType.VIENEU, UserConfig.getTtsEngine(dummyContext))
    }

    @Test
    fun testReadingService_prebufferChunksDelegation() {
        val baseAssetsDir = File("src/main/assets/vieneu")
        val backboneDir = OnnxSessionManager.getBackboneDir(baseAssetsDir, "INT8")
        val codecDir = File(baseAssetsDir, "codec")

        val sessionManager = OnnxSessionManager.createFromFiles(
            backboneDir = backboneDir,
            codecDir = codecDir,
            threads = 2,
            precision = "INT8"
        )
        val configFile = File(backboneDir, "config.json")
        val config = VieNeuConfig.fromJson(configFile.readText(Charsets.UTF_8))
        val tokenizerFile = File(backboneDir, "tokenizer.json")
        val tokenizer = VieNeuTokenizer.fromFile(tokenizerFile)
        val headsFile = File(backboneDir, "vieneu_v3_heads.npz")
        val headsReader = NpzReader.read(headsFile)

        val engine = VieNeuOnnxEngine(
            sessionManager = sessionManager,
            config = config,
            tokenizer = tokenizer,
            textEmbeddings = headsReader.get2D("text_emb")!!,
            audioEmbeddings = headsReader.get3D("audio_emb")!!,
            xvecW = headsReader.get2D("xvec_w"),
            xvecB = headsReader.getFloatArray("xvec_b"),
            xvecLnW = headsReader.getFloatArray("xvec_ln_w"),
            xvecLnB = headsReader.getFloatArray("xvec_ln_b"),
            xvecLnEps = headsReader.getScalarFloat("xvec_ln_eps") ?: 1e-5f
        )

        val pipeline = TTSAudioPipeline(
            engine = engine,
            voiceName = "Trúc Ly",
            preBufferChunkCount = 2
        )
        val ttsManager = VieNeuTTSManager(
            context = null,
            onStateChange = {},
            customEngine = engine,
            customPipeline = pipeline
        )

        assertEquals(2, pipeline.preBufferChunkCount)
        assertEquals(2, ttsManager.getPrebufferChunks())

        val service = ReadingService()
        service.ttsManager = ttsManager

        // 1. Switch prebuffer chunks to 1
        service.switchPrebufferChunks(1)
        assertEquals(1, UserConfig.getPrebufferChunks(dummyContext))
        assertEquals(1, pipeline.preBufferChunkCount)
        assertEquals(1, ttsManager.getPrebufferChunks())

        // 2. Switch prebuffer chunks to 3
        service.switchPrebufferChunks(3)
        assertEquals(3, UserConfig.getPrebufferChunks(dummyContext))
        assertEquals(3, pipeline.preBufferChunkCount)
        assertEquals(3, ttsManager.getPrebufferChunks())

        // 3. Out of bounds clamping check (e.g. 10 -> clamped to 3, -2 -> clamped to 1)
        service.switchPrebufferChunks(10)
        assertEquals(3, UserConfig.getPrebufferChunks(dummyContext))
        assertEquals(3, pipeline.preBufferChunkCount)
        assertEquals(3, ttsManager.getPrebufferChunks())

        service.switchPrebufferChunks(-2)
        assertEquals(1, UserConfig.getPrebufferChunks(dummyContext))
        assertEquals(1, pipeline.preBufferChunkCount)
        assertEquals(1, ttsManager.getPrebufferChunks())

        // 4. Test setPrebufferChunks alias
        service.setPrebufferChunks(2)
        assertEquals(2, UserConfig.getPrebufferChunks(dummyContext))
        assertEquals(2, pipeline.preBufferChunkCount)
        assertEquals(2, ttsManager.getPrebufferChunks())

        ttsManager.shutdown()
    }
}
