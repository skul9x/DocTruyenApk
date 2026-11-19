package com.skul9x.doctruyen.performance

import android.content.Context
import android.content.SharedPreferences
import com.skul9x.doctruyen.tts.vieneu.OnnxSessionManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.Random

/**
 * Phase 06 Verification Test:
 * Validates 64KB high-throughput streaming buffer extraction, version-code-driven
 * model invalidation/re-extraction, ProGuard keep-rules, and release R8 minification.
 */
class AssetExtractionAndProguardRulesTest {

    private lateinit var tempDir: File
    private val prefsStore = mutableMapOf<String, Any>()

    private var originalAssetListProvider: ((Context, String) -> Array<String>?)? = null
    private var originalAssetOpenProvider: ((Context, String) -> InputStream)? = null
    private var originalVersionCodeProvider: ((Context) -> Long)? = null
    private var originalPrefsProvider: ((Context) -> SharedPreferences?)? = null

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "vieneu_test_${System.currentTimeMillis()}_${Random().nextInt(10000)}")
        tempDir.mkdirs()
        prefsStore.clear()

        originalAssetListProvider = OnnxSessionManager.assetListProvider
        originalAssetOpenProvider = OnnxSessionManager.assetOpenProvider
        originalVersionCodeProvider = OnnxSessionManager.currentVersionCodeProvider
        originalPrefsProvider = OnnxSessionManager.sharedPreferencesProvider

        val editorProxy = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "putLong" -> {
                        prefsStore[args[0] as String] = args[1] as Long
                        proxy
                    }
                    "putString" -> {
                        prefsStore[args[0] as String] = args[1] as String
                        proxy
                    }
                    "putBoolean" -> {
                        prefsStore[args[0] as String] = args[1] as Boolean
                        proxy
                    }
                    "apply", "commit" -> true
                    else -> proxy
                }
            }
        ) as SharedPreferences.Editor

        val prefsProxy = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "getLong" -> {
                        val key = args[0] as String
                        val def = args[1] as Long
                        (prefsStore[key] as? Long) ?: def
                    }
                    "getString" -> {
                        val key = args[0] as String
                        val def = args[1] as? String
                        (prefsStore[key] as? String) ?: def
                    }
                    "getBoolean" -> {
                        val key = args[0] as String
                        val def = args[1] as Boolean
                        (prefsStore[key] as? Boolean) ?: def
                    }
                    "contains" -> {
                        val key = args[0] as String
                        prefsStore.containsKey(key)
                    }
                    "edit" -> editorProxy
                    else -> null
                }
            }
        ) as SharedPreferences

        OnnxSessionManager.sharedPreferencesProvider = { prefsProxy }
    }

    @After
    fun tearDown() {
        OnnxSessionManager.assetListProvider = originalAssetListProvider
        OnnxSessionManager.assetOpenProvider = originalAssetOpenProvider
        OnnxSessionManager.currentVersionCodeProvider = originalVersionCodeProvider
        OnnxSessionManager.sharedPreferencesProvider = originalPrefsProvider

        tempDir.deleteRecursively()
        prefsStore.clear()
    }

    private fun createMockContext(filesDirOverride: File = tempDir): Context {
        return object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesDirOverride
            override fun getPackageName(): String = "com.skul9x.doctruyen"
        }
    }

    private fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun computeSha256(file: File): String {
        return computeSha256(file.readBytes())
    }

    private fun findProjectFile(relativePaths: List<String>): File {
        for (path in relativePaths) {
            val file = File(path)
            if (file.exists()) return file
        }
        fail("None of candidate files exist: $relativePaths")
        throw IllegalStateException()
    }

    /**
     * Scenario 1:
     * Extract synthetic asset using 64KB buffer and assert binary SHA-256 match with original across various sizes.
     */
    @Test
    fun test64KbBufferExtractionCorrectness() {
        val testSizes = listOf(
            0,                      // Empty file
            512,                    // Small sub-buffer file
            64 * 1024,              // Exactly 1 buffer size (64KB)
            64 * 1024 + 1,          // 64KB + 1 byte boundary
            256 * 1024 + 777,       // Multiple buffer chunks (~256KB)
            512 * 1024              // Half MB file
        )

        val context = createMockContext()

        for ((idx, size) in testSizes.withIndex()) {
            val random = Random(42L + idx)
            val syntheticData = ByteArray(size)
            random.nextBytes(syntheticData)
            val expectedHash = computeSha256(syntheticData)

            val assetPath = "test/model_$idx.bin"
            val targetFile = File(tempDir, "extracted/model_$idx.bin")

            OnnxSessionManager.assetOpenProvider = { _, _ ->
                ByteArrayInputStream(syntheticData)
            }

            OnnxSessionManager.copyAssetFile(
                context = context,
                assetPath = assetPath,
                targetFile = targetFile,
                force = true,
                bufferSize = OnnxSessionManager.ASSET_STREAM_BUFFER_SIZE
            )

            assertTrue("Extracted file should exist: ${targetFile.absolutePath}", targetFile.exists())
            assertEquals("Extracted file size must match original", size.toLong(), targetFile.length())
            val actualHash = computeSha256(targetFile)
            assertEquals("Binary SHA-256 must match exactly for size $size", expectedHash, actualHash)
        }
    }

    /**
     * Scenario 2:
     * Simulate version upgrade from version 1 to 2; verify target files are updated.
     */
    @Test
    fun testVersionCodeTriggerReExtraction() {
        val context = createMockContext()
        val mockFiles = mutableMapOf<String, ByteArray>()

        OnnxSessionManager.assetListProvider = { _, path ->
            when (path) {
                "vieneu" -> arrayOf("backbone", "voices_v3_turbo.json")
                "vieneu/backbone" -> arrayOf("vieneu_prefill.onnx")
                else -> emptyArray()
            }
        }

        OnnxSessionManager.assetOpenProvider = { _, path ->
            val data = mockFiles[path] ?: ByteArray(0)
            ByteArrayInputStream(data)
        }

        // --- Step A: App Version 1 Launch ---
        OnnxSessionManager.currentVersionCodeProvider = { 1L }
        mockFiles["vieneu/backbone/vieneu_prefill.onnx"] = "MODEL_WEIGHTS_VERSION_1".toByteArray(Charsets.UTF_8)
        mockFiles["vieneu/voices_v3_turbo.json"] = "{\"version\":1}".toByteArray(Charsets.UTF_8)

        val baseDir = OnnxSessionManager.ensureAssetsExtracted(context, precision = "FP32")
        val prefillFile = File(baseDir, "backbone/vieneu_prefill.onnx")

        assertTrue("Prefill file must exist after first extraction", prefillFile.exists())
        assertEquals("MODEL_WEIGHTS_VERSION_1", prefillFile.readText(Charsets.UTF_8))
        assertEquals(
            "KEY_LAST_EXTRACTED_VERSION should be stored as 1L",
            1L,
            prefsStore[OnnxSessionManager.KEY_LAST_EXTRACTED_VERSION]
        )

        // --- Step B: App Upgrade to Version 2 ---
        OnnxSessionManager.currentVersionCodeProvider = { 2L }
        mockFiles["vieneu/backbone/vieneu_prefill.onnx"] = "MODEL_WEIGHTS_VERSION_2_UPDATED".toByteArray(Charsets.UTF_8)
        mockFiles["vieneu/voices_v3_turbo.json"] = "{\"version\":2}".toByteArray(Charsets.UTF_8)

        OnnxSessionManager.ensureAssetsExtracted(context, precision = "FP32")

        assertEquals(
            "Model file must be re-extracted and reflect Version 2 updates",
            "MODEL_WEIGHTS_VERSION_2_UPDATED",
            prefillFile.readText(Charsets.UTF_8)
        )
        assertEquals(
            "KEY_LAST_EXTRACTED_VERSION must be updated to 2L",
            2L,
            prefsStore[OnnxSessionManager.KEY_LAST_EXTRACTED_VERSION]
        )
    }

    /**
     * Scenario 3:
     * Simulate second run with matching version; assert existing files are retained without re-copying.
     */
    @Test
    fun testNoReExtractionWhenVersionMatches() {
        val context = createMockContext()
        val mockFiles = mutableMapOf<String, ByteArray>()

        OnnxSessionManager.assetListProvider = { _, path ->
            when (path) {
                "vieneu" -> arrayOf("backbone")
                "vieneu/backbone" -> arrayOf("vieneu_prefill.onnx")
                else -> emptyArray()
            }
        }

        OnnxSessionManager.assetOpenProvider = { _, path ->
            val data = mockFiles[path] ?: ByteArray(0)
            ByteArrayInputStream(data)
        }

        // --- Step A: Initial extraction on version 2 ---
        OnnxSessionManager.currentVersionCodeProvider = { 2L }
        mockFiles["vieneu/backbone/vieneu_prefill.onnx"] = "INITIAL_V2_DATA".toByteArray(Charsets.UTF_8)

        val baseDir = OnnxSessionManager.ensureAssetsExtracted(context, precision = "FP32")
        val prefillFile = File(baseDir, "backbone/vieneu_prefill.onnx")

        assertEquals("INITIAL_V2_DATA", prefillFile.readText(Charsets.UTF_8))
        assertEquals(2L, prefsStore[OnnxSessionManager.KEY_LAST_EXTRACTED_VERSION])

        // --- Step B: Manually modify local file to detect if re-extraction happens ---
        prefillFile.writeText("CUSTOM_USER_MODIFIED_CONTENT", Charsets.UTF_8)

        // Change mock asset stream to something else
        mockFiles["vieneu/backbone/vieneu_prefill.onnx"] = "ASSET_STREAM_DATA".toByteArray(Charsets.UTF_8)

        // --- Step C: Subsequent run with same version (2L) ---
        OnnxSessionManager.ensureAssetsExtracted(context, precision = "FP32")

        // Should NOT overwrite because currentVersion == lastExtractedVersion (2L == 2L)
        assertEquals(
            "Existing on-disk file must NOT be re-extracted when version code matches",
            "CUSTOM_USER_MODIFIED_CONTENT",
            prefillFile.readText(Charsets.UTF_8)
        )
    }

    /**
     * Scenario 4:
     * Parse proguard-rules.pro and assert all required class names
     * (ONNX Runtime, SeaG2P, Retrofit, Gson, Glide, Media) are covered by -keep statements.
     */
    @Test
    fun testProguardRulesValidity() {
        val proguardFile = findProjectFile(
            listOf("app/proguard-rules.pro", "proguard-rules.pro")
        )

        assertTrue("proguard-rules.pro must exist", proguardFile.exists())
        val content = proguardFile.readText(Charsets.UTF_8)
        assertFalse("proguard-rules.pro must not be empty", content.isBlank())

        // 1. Native methods & JNI
        assertTrue(
            "Must preserve native method signatures",
            content.contains("native <methods>;")
        )

        // 2. ONNX Runtime
        assertTrue(
            "Must keep ai.onnxruntime.**",
            content.contains("ai.onnxruntime.**")
        )

        // 3. SeaG2P Rust JNI
        assertTrue(
            "Must keep com.skul9x.doctruyen.tts.vieneu.SeaG2P",
            content.contains("com.skul9x.doctruyen.tts.vieneu.SeaG2P")
        )

        // 4. Data Transfer Objects / Gson
        assertTrue(
            "Must keep @SerializedName fields",
            content.contains("@com.google.gson.annotations.SerializedName <fields>;")
        )

        // 5. Retrofit & OkHttp
        assertTrue(
            "Must keep retrofit2.**",
            content.contains("retrofit2.**")
        )
        assertTrue(
            "Must keep com.skul9x.doctruyen.network.**",
            content.contains("com.skul9x.doctruyen.network.**")
        )

        // 6. Glide
        assertTrue(
            "Must keep com.bumptech.glide.**",
            content.contains("com.bumptech.glide.**")
        )

        // 7. AndroidX Media
        assertTrue(
            "Must keep androidx.media.**",
            content.contains("androidx.media.**")
        )
    }

    /**
     * Scenario 5:
     * Verify app/build.gradle.kts has isMinifyEnabled = true and isShrinkResources = true.
     */
    @Test
    fun testBuildGradleMinifyConfiguration() {
        val buildGradleFile = findProjectFile(
            listOf("app/build.gradle.kts", "build.gradle.kts")
        )

        val content = buildGradleFile.readText(Charsets.UTF_8)
        assertTrue(
            "build.gradle.kts must set isMinifyEnabled = true in release buildType",
            content.contains("isMinifyEnabled = true")
        )
        assertTrue(
            "build.gradle.kts must set isShrinkResources = true in release buildType",
            content.contains("isShrinkResources = true")
        )
    }
}

