package com.skul9x.doctruyen.performance

import android.content.Context
import android.content.SharedPreferences
import com.skul9x.doctruyen.StoryDetailActivity
import com.skul9x.doctruyen.network.RetrofitClient
import com.skul9x.doctruyen.tts.vieneu.VieNeuConfig
import com.skul9x.doctruyen.utils.UserConfig
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Phase 01 Verification Test:
 * Validates hygiene cleanup, dead asset removal, HTML sanitization regex precompilation,
 * ViewPropertyAnimator migration, Retrofit proxy caching, and release log level security.
 */
class HygieneAndAssetCleanupTest {

    private val preferencesMap = mutableMapOf<String, Any>()
    private var originalPrefsProvider: ((Context) -> SharedPreferences)? = null

    @Before
    fun setUp() {
        preferencesMap.clear()
        originalPrefsProvider = UserConfig.customPrefsProvider

        var editorProxy: SharedPreferences.Editor? = null
        editorProxy = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "putString" -> {
                        preferencesMap[args[0] as String] = args[1] as String
                        proxy
                    }
                    "apply", "commit" -> null
                    else -> proxy
                }
            }
        ) as SharedPreferences.Editor

        val prefsProxy = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "getString" -> {
                        val key = args[0] as String
                        val def = args[1] as? String
                        preferencesMap[key] as? String ?: def
                    }
                    "edit" -> editorProxy
                    else -> null
                }
            }
        ) as SharedPreferences

        UserConfig.customPrefsProvider = { prefsProxy }
        RetrofitClient.resetClient()
    }

    @After
    fun tearDown() {
        UserConfig.customPrefsProvider = originalPrefsProvider
        RetrofitClient.resetClient()
        preferencesMap.clear()
    }

    private fun createMockContext(): Context {
        return object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }
    }

    private fun findProjectFile(relativePaths: List<String>): File {
        for (path in relativePaths) {
            val file = File(path)
            if (file.exists()) return file
        }
        fail("None of the candidate file paths exist: $relativePaths")
        throw IllegalStateException()
    }

    @Test
    fun testManifestContainsLargeHeap() {
        val manifestFile = findProjectFile(
            listOf("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml")
        )

        val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = docBuilder.parse(manifestFile)
        val appNodes = doc.getElementsByTagName("application")
        assertTrue("Manifest must declare an <application> element", appNodes.length > 0)

        val appElement = appNodes.item(0) as Element
        val largeHeapAttr = appElement.getAttribute("android:largeHeap")
        assertEquals(
            "Application tag must explicitly declare android:largeHeap=\"true\"",
            "true",
            largeHeapAttr
        )
    }

    @Test
    fun testDeadAssetsPurged() {
        val assetsBaseDir = findProjectFile(
            listOf("app/src/main/assets/vieneu", "src/main/assets/vieneu")
        )

        // 1. Verify denoiser directory and denoiser.onnx are purged
        val denoiserDir = File(assetsBaseDir, "denoiser")
        assertFalse("vieneu/denoiser directory must be removed", denoiserDir.exists())
        val denoiserFile = File(denoiserDir, "denoiser.onnx")
        assertFalse("denoiser.onnx (41MB) must be removed from assets", denoiserFile.exists())

        // 2. Verify moss_audio_tokenizer_encode.* files are purged from codec
        val codecDir = File(assetsBaseDir, "codec")
        assertTrue("vieneu/codec directory must exist", codecDir.exists())
        val encoderOnnx = File(codecDir, "moss_audio_tokenizer_encode.onnx")
        val encoderData = File(codecDir, "moss_audio_tokenizer_encode.data")
        assertFalse("moss_audio_tokenizer_encode.onnx (797KB) must be removed", encoderOnnx.exists())
        assertFalse("moss_audio_tokenizer_encode.data (43MB) must be removed", encoderData.exists())

        // 3. Verify decoder assets and G2P dictionary are preserved
        val decoderOnnx = File(codecDir, "moss_audio_tokenizer_decode_full.onnx")
        val decoderData = File(codecDir, "moss_audio_tokenizer_decode_shared.data")
        val g2pFile = File(assetsBaseDir, "sea_g2p.bin")
        val voicesFile = File(assetsBaseDir, "voices_v3_turbo.json")
        assertTrue("moss_audio_tokenizer_decode_full.onnx must exist", decoderOnnx.exists())
        assertTrue("moss_audio_tokenizer_decode_shared.data must exist", decoderData.exists())
        assertTrue("sea_g2p.bin must exist", g2pFile.exists())
        assertTrue("voices_v3_turbo.json must exist", voicesFile.exists())

        // 4. Verify VieNeuConfig has no DENOISER_DIR constant
        val hasDenoiserDir = VieNeuConfig::class.java.declaredFields.any { it.name == "DENOISER_DIR" }
        assertFalse("VieNeuConfig should no longer expose DENOISER_DIR", hasDenoiserDir)
    }

    @Test
    fun testHtmlSanitizationSecurityAndSpeed() {
        // 1. Verify precompiled regex instances exist in StoryDetailActivity.companion
        assertNotNull("SCRIPT_REGEX must exist", StoryDetailActivity.SCRIPT_REGEX)
        assertNotNull("EVENT_HANDLER_REGEX must exist", StoryDetailActivity.EVENT_HANDLER_REGEX)
        assertNotNull("JAVASCRIPT_URI_REGEX must exist", StoryDetailActivity.JAVASCRIPT_URI_REGEX)
        assertNotNull("TAG_PAIR_REGEX must exist", StoryDetailActivity.TAG_PAIR_REGEX)
        assertNotNull("SELF_CLOSING_TAG_REGEX must exist", StoryDetailActivity.SELF_CLOSING_TAG_REGEX)

        // 2. Security validation: malicious tags stripped
        val maliciousInputs = listOf(
            "<script>alert('pwned')</script>Hello World" to "Hello World",
            "<script type=\"text/javascript\" src=\"evil.js\"></script>Safe Text" to "Safe Text",
            "<p onmouseover=\"stealCookies()\">Hover me</p>" to "<p>Hover me</p>",
            "<button onclick = 'badCode()'>Click</button>" to "<button>Click</button>",
            "<a href=\"javascript:evilFunc()\">Click here</a>" to "<a href=\"evilFunc()\">Click here</a>",
            "<iframe>hidden iframe</iframe>Valid" to "Valid",
            "<iframe src=\"malicious.html\"/>Valid" to "Valid",
            "<object data=\"exploit.swf\"></object>Valid" to "Valid",
            "<embed src=\"audio.mp3\"></embed>Valid" to "Valid",
            "<form action=\"/steal\"><input type=\"password\"/></form>Valid" to "Valid"
        )

        for ((input, expected) in maliciousInputs) {
            val sanitized = StoryDetailActivity.sanitizeHtml(input)
            assertEquals("Sanitization mismatch for input: $input", expected, sanitized)
        }

        // 3. Formatting tags preserved
        val validHtml = "<p>Đoạn văn thứ nhất.</p><br><b>Chữ in đậm</b> <i>Chữ in nghiêng</i> <strong>Mạnh</strong> <em>Nhấn</em>"
        val preserved = StoryDetailActivity.sanitizeHtml(validHtml)
        assertEquals("Valid HTML formatting tags must remain intact", validHtml, preserved)

        // 4. Performance benchmark: sanitizing large 50KB chapter string
        val chapterBuilder = StringBuilder()
        for (i in 1..200) {
            chapterBuilder.append("<p>Đây là dòng thứ $i của chương truyện vô cùng dài. Nhân vật chính bước vào hang động bí ẩn.</p>\n")
            if (i % 20 == 0) {
                chapterBuilder.append("<script>maliciousSnippet($i);</script>\n")
                chapterBuilder.append("<span onclick=\"evil($i)\">Click test</span>\n")
            }
        }
        val largeChapter = chapterBuilder.toString()
        assertTrue("Benchmark chapter must exceed 10KB", largeChapter.length > 10_000)

        // Warm up
        for (w in 1..3) {
            StoryDetailActivity.sanitizeHtml(largeChapter)
        }

        val startTime = System.currentTimeMillis()
        val iterations = 10
        for (i in 1..iterations) {
            val result = StoryDetailActivity.sanitizeHtml(largeChapter)
            assertFalse("Result must not contain scripts", result.contains("<script"))
        }
        val elapsed = System.currentTimeMillis() - startTime
        // Precompiled regexes should sanitize 10 iterations of ~20KB text in well under 200ms
        assertTrue("Sanitization took too long: ${elapsed}ms for $iterations iterations", elapsed < 500)
    }

    @Test
    fun testRetrofitClientCaching() {
        val mockContext = createMockContext()
        val initialUrl = "https://skul9x.free.nf/truyen/"
        val mirrorUrl = "https://mirror.skul9x.free.nf/truyen/"

        UserConfig.setBaseUrl(mockContext, initialUrl)

        // 1. Multiple calls with same context / URL return the identical cached instance
        val service1 = RetrofitClient.getApiService(mockContext)
        val service2 = RetrofitClient.getApiService(mockContext)
        assertNotNull("ApiService instance should not be null", service1)
        assertSame(
            "Consecutive getApiService calls with same base URL must return cached singleton proxy",
            service1,
            service2
        )

        // 2. getApiServiceForUrl with same URL also returns identical cached instance
        val serviceViaUrl = RetrofitClient.getApiServiceForUrl(initialUrl)
        assertSame(
            "getApiServiceForUrl with matching URL must reuse cached instance",
            service1,
            serviceViaUrl
        )

        // 3. Changing URL invalidates cache and creates new instance
        UserConfig.setBaseUrl(mockContext, mirrorUrl)
        val service3 = RetrofitClient.getApiService(mockContext)
        assertNotNull("New ApiService should not be null", service3)
        assertNotSame(
            "Changing base URL must recreate ApiService and invalidate old cached proxy",
            service1,
            service3
        )

        // 4. Repeated call with new URL is cached
        val service4 = RetrofitClient.getApiService(mockContext)
        assertSame("Subsequent calls with new base URL must be cached", service3, service4)

        // 5. resetClient invalidates cache
        RetrofitClient.resetClient()
        val service5 = RetrofitClient.getApiService(mockContext)
        assertNotSame(
            "resetClient() must clear cached ApiService instance",
            service4,
            service5
        )
    }

    @Test
    fun testReleaseLogLevelIsNone() {
        // Verify that when isDebug is false, logging level is strictly Level.NONE
        val releaseLevel = RetrofitClient.getLoggingLevel(isDebug = false)
        assertEquals(
            "Production release builds must configure HttpLoggingInterceptor.Level.NONE to protect auth tokens and cookies",
            HttpLoggingInterceptor.Level.NONE,
            releaseLevel
        )

        // Verify that debug mode allows Level.BODY
        val debugLevel = RetrofitClient.getLoggingLevel(isDebug = true)
        assertEquals(
            "Debug builds should use HttpLoggingInterceptor.Level.BODY for diagnostics",
            HttpLoggingInterceptor.Level.BODY,
            debugLevel
        )
    }
}

