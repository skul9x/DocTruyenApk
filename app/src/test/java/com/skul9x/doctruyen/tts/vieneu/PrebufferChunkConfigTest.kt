package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import android.content.SharedPreferences
import com.skul9x.doctruyen.utils.UserConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

class PrebufferChunkConfigTest {

    private class FakeSharedPreferences : SharedPreferences {
        private val storage = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(storage)

        override fun getString(key: String?, defValue: String?): String? {
            return (storage[key] as? String) ?: defValue
        }

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return (storage[key] as? MutableSet<String>) ?: defValues
        }

        override fun getInt(key: String?, defValue: Int): Int {
            return (storage[key] as? Int) ?: defValue
        }

        override fun getLong(key: String?, defValue: Long): Long {
            return (storage[key] as? Long) ?: defValue
        }

        override fun getFloat(key: String?, defValue: Float): Float {
            return (storage[key] as? Float) ?: defValue
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return (storage[key] as? Boolean) ?: defValue
        }

        override fun contains(key: String?): Boolean = storage.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(storage)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class FakeEditor(private val storage: MutableMap<String, Any>) : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) {
                    if (value != null) temp[key] = value else temp[key] = null
                }
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) {
                    if (values != null) temp[key] = values else temp[key] = null
                }
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
            }
        }
    }

    private lateinit var fakePrefs: FakeSharedPreferences
    private val dummyContext: Context = object : android.content.ContextWrapper(null) {}

    @Before
    fun setup() {
        fakePrefs = FakeSharedPreferences()
        UserConfig.customPrefsProvider = { fakePrefs }
    }

    @After
    fun tearDown() {
        UserConfig.customPrefsProvider = null
    }

    @Test
    fun testUserConfigPrebufferChunksPersistenceAndClamping() {
        // 1. Default value when nothing stored
        assertEquals(2, UserConfig.getPrebufferChunks(dummyContext))

        // 2. Valid values: 1, 2, 3
        UserConfig.setPrebufferChunks(dummyContext, 1)
        assertEquals(1, UserConfig.getPrebufferChunks(dummyContext))

        UserConfig.setPrebufferChunks(dummyContext, 2)
        assertEquals(2, UserConfig.getPrebufferChunks(dummyContext))

        UserConfig.setPrebufferChunks(dummyContext, 3)
        assertEquals(3, UserConfig.getPrebufferChunks(dummyContext))

        // 3. Clamping underflow (< 1)
        UserConfig.setPrebufferChunks(dummyContext, 0)
        assertEquals(1, UserConfig.getPrebufferChunks(dummyContext))

        UserConfig.setPrebufferChunks(dummyContext, -5)
        assertEquals(1, UserConfig.getPrebufferChunks(dummyContext))

        // 4. Clamping overflow (> 3)
        UserConfig.setPrebufferChunks(dummyContext, 4)
        assertEquals(3, UserConfig.getPrebufferChunks(dummyContext))

        UserConfig.setPrebufferChunks(dummyContext, 99)
        assertEquals(3, UserConfig.getPrebufferChunks(dummyContext))
    }

    @Test
    fun testComputeAdaptiveTargetDurationMs_for1Chunk() {
        // 1 Chunk configuration: Ultra fast startup -> target 2000ms
        val durationHighCore = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 8,
            remainingChunks = 4,
            startIndex = 0,
            targetPreBufferChunks = 1
        )
        assertEquals(2000L, durationHighCore)

        val durationFp16 = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.FP16,
            cpuCores = 6,
            remainingChunks = 4,
            startIndex = 0,
            targetPreBufferChunks = 1
        )
        assertEquals(2000L, durationFp16)

        // Resuming/seeking mid-story -> 1500ms
        val midStory = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 8,
            remainingChunks = 4,
            startIndex = 1,
            targetPreBufferChunks = 1
        )
        assertEquals(1500L, midStory)

        val singleRemaining = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 8,
            remainingChunks = 1,
            startIndex = 0,
            targetPreBufferChunks = 1
        )
        assertEquals(1500L, singleRemaining)

        // Constrained device / FP32 safe fallback
        val fp32Fallback = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.FP32,
            cpuCores = 8,
            remainingChunks = 4,
            startIndex = 0,
            safeFallbackDurationMs = 7500L,
            targetPreBufferChunks = 1
        )
        assertEquals(7500L, fp32Fallback)
    }

    @Test
    fun testComputeAdaptiveTargetDurationMs_for2Chunks() {
        // 2 Chunks configuration (Default): Balanced startup
        // High core INT8 -> 2000ms
        val int8HighCore = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 8,
            remainingChunks = 4,
            startIndex = 0,
            targetPreBufferChunks = 2
        )
        assertEquals(2000L, int8HighCore)

        // Mid core INT8 -> 2500ms
        val int8MidCore = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 4,
            remainingChunks = 4,
            startIndex = 0,
            targetPreBufferChunks = 2
        )
        assertEquals(2500L, int8MidCore)

        // Standard/other -> 4500ms
        val standardOther = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.FP16,
            cpuCores = 4,
            remainingChunks = 4,
            startIndex = 0,
            targetPreBufferChunks = 2
        )
        assertEquals(4500L, standardOther)

        // Low core / FP32 fallback -> 7500ms
        val lowCoreFallback = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 2,
            remainingChunks = 4,
            startIndex = 0,
            targetPreBufferChunks = 2
        )
        assertEquals(7500L, lowCoreFallback)
    }

    @Test
    fun testComputeAdaptiveTargetDurationMs_for3Chunks() {
        // 3 Chunks configuration: Deep buffer / Max stability -> target 8000ms
        val durationHighCore = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 8,
            remainingChunks = 4,
            startIndex = 0,
            targetPreBufferChunks = 3
        )
        assertEquals(8000L, durationHighCore)

        val durationFp16 = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.FP16,
            cpuCores = 6,
            remainingChunks = 4,
            startIndex = 0,
            targetPreBufferChunks = 3
        )
        assertEquals(8000L, durationFp16)

        // Mid story -> 1500ms
        val midStory = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 8,
            remainingChunks = 4,
            startIndex = 2,
            targetPreBufferChunks = 3
        )
        assertEquals(1500L, midStory)
    }

    @Test
    fun testVieNeuTTSManagerPrebufferChunkForwarding() {
        val baseAssetsDir = File("src/main/assets/vieneu")
        val backboneDir = OnnxSessionManager.getBackboneDir(baseAssetsDir, "INT8")
        val codecDir = File(baseAssetsDir, "codec")
        val voicesFile = File(baseAssetsDir, "voices_v3_turbo.json")

        val sessionManager = OnnxSessionManager.createFromFiles(
            backboneDir = backboneDir,
            codecDir = codecDir,
            threads = 1,
            precision = "INT8"
        )

        val configFile = File(backboneDir, "config.json")
        val config = VieNeuConfig.fromJson(configFile.readText(Charsets.UTF_8))

        val tokenizerFile = File(backboneDir, "tokenizer.json")
        val tokenizer = VieNeuTokenizer.fromFile(tokenizerFile)

        val headsFile = File(backboneDir, "vieneu_v3_heads.npz")
        val headsReader = NpzReader.read(headsFile)

        if (voicesFile.exists()) {
            VoicePresets.loadFromJson(voicesFile.readText(Charsets.UTF_8))
        }

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
            preBufferChunkCount = 2
        )
        assertEquals(2, pipeline.preBufferChunkCount)
        assertEquals(2, pipeline.targetPreBufferChunks)

        val manager = VieNeuTTSManager(
            context = dummyContext,
            onStateChange = {},
            customEngine = engine,
            customPipeline = pipeline
        )

        // Setting chunks via manager
        manager.setPrebufferChunks(3)
        assertEquals(3, manager.getPrebufferChunks())
        assertEquals(3, pipeline.preBufferChunkCount)
        assertEquals(3, UserConfig.getPrebufferChunks(dummyContext))

        // Clamping check via manager
        manager.setPrebufferChunks(10)
        assertEquals(3, manager.getPrebufferChunks())
        assertEquals(3, pipeline.preBufferChunkCount)

        manager.setPrebufferChunks(0)
        assertEquals(1, manager.getPrebufferChunks())
        assertEquals(1, pipeline.preBufferChunkCount)

        manager.shutdown()
    }
}
