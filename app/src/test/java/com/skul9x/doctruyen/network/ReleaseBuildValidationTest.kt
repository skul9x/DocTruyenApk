package com.skul9x.doctruyen.network

import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File

/**
 * Phase 04 Comprehensive Release Build Verification Test:
 * 1. ProGuard and Gradle Build Integrity (isMinifyEnabled, ABI splits, proguard-rules.pro).
 * 2. Elimination of dangerous ProGuard wildcards (-dontshrink, blanket keep rules).
 * 3. Cohesion of all network architecture components (ApiService, RetrofitClient, NetworkErrorResolver, DTOs).
 */
class ReleaseBuildValidationTest {

    private fun resolveFile(path: String): File {
        val f1 = File(path)
        if (f1.exists()) return f1
        val f2 = File("app", path)
        if (f2.exists()) return f2
        val parent = File("..", path)
        if (parent.exists()) return parent
        return if (path.startsWith("app/")) File(path.removePrefix("app/")) else f1
    }

    @Test
    fun testProGuardAndGradleBuildIntegrity() {
        val gradleFile = resolveFile("app/build.gradle.kts")
        assertTrue("build.gradle.kts must exist: ${gradleFile.absolutePath}", gradleFile.exists())
        val gradleContent = gradleFile.readText(Charsets.UTF_8)

        // 1. Minification & R8 Optimization enabled
        assertTrue("Release build must enable minification (isMinifyEnabled = true)",
            gradleContent.contains("isMinifyEnabled = true"))
        assertTrue("Release build must reference proguard-android-optimize.txt",
            gradleContent.contains("getDefaultProguardFile(\"proguard-android-optimize.txt\")"))
        assertTrue("Release build must reference proguard-rules.pro",
            gradleContent.contains("\"proguard-rules.pro\""))

        // 2. ABI Architecture & Packaging Integrity (arm64-v8a unified)
        assertTrue("ABI target must include arm64-v8a", gradleContent.contains("\"arm64-v8a\""))
        assertFalse("Legacy armeabi-v7a must be excluded from build targets",
            gradleContent.contains("\"armeabi-v7a\""))
        assertFalse("Legacy x86_64 must be excluded from build targets",
            gradleContent.contains("\"x86_64\""))
        assertTrue("ABI splits multi-APK generation must be disabled for single unified APK",
            gradleContent.contains("isEnable = false"))

        // 3. Check gradle.properties for R8 safety flag
        val propertiesFile = resolveFile("gradle.properties")
        if (propertiesFile.exists()) {
            val propertiesContent = propertiesFile.readText(Charsets.UTF_8)
            assertTrue("gradle.properties must specify android.enableR8.fullMode=false for compatibility",
                propertiesContent.contains("android.enableR8.fullMode=false"))
        }
    }

    @Test
    fun testNoDangerousProGuardWildcards() {
        val proguardFile = resolveFile("app/proguard-rules.pro")
        assertTrue("proguard-rules.pro must exist: ${proguardFile.absolutePath}", proguardFile.exists())
        val lines = proguardFile.readLines(Charsets.UTF_8)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        val fullContent = lines.joinToString("\n")

        // 1. Ensure R8 shrinking & obfuscation are NOT disabled globally
        assertFalse("ProGuard rules must not disable shrinking with -dontshrink",
            lines.any { it.startsWith("-dontshrink") })
        assertFalse("ProGuard rules must not disable obfuscation with -dontobfuscate",
            lines.any { it.startsWith("-dontobfuscate") })

        // 2. Ensure no reckless blanket keep rules exist
        assertFalse("ProGuard rules must not contain blanket '-keep class * { *; }'",
            lines.any { it.contains("-keep class * { *; }") })
        assertFalse("ProGuard rules must not contain blanket '-keep class ** { *; }'",
            lines.any { it.contains("-keep class ** { *; }") })

        // 3. Verify specific required surgical rules are strictly present
        assertTrue("Must keep kotlin.coroutines.Continuation",
            fullContent.contains("class kotlin.coroutines.Continuation"))
        assertTrue("Must keep retrofit2.Call",
            fullContent.contains("interface retrofit2.Call"))
        assertTrue("Must keep retrofit2.Response",
            fullContent.contains("class retrofit2.Response"))
        assertTrue("Must keep AnnotationDefault attribute",
            fullContent.contains("-keepattributes AnnotationDefault"))
        assertTrue("Must keep Signature attribute",
            fullContent.contains("Signature"))
        assertTrue("Must keep Gson TypeToken",
            fullContent.contains("class com.google.gson.reflect.TypeToken"))
    }

    @Test
    fun testAllNetworkComponentsCohesive() {
        // 1. Verify ApiService declaration & interface methods
        val apiClass = ApiService::class.java
        assertTrue("ApiService must be an interface", apiClass.isInterface)

        val methods = apiClass.declaredMethods
        val methodNames = methods.map { it.name }
        assertTrue("ApiService must have getStories", methodNames.contains("getStories"))
        assertTrue("ApiService must have getStoryDetail", methodNames.contains("getStoryDetail"))
        assertTrue("ApiService must have getRandomStory", methodNames.contains("getRandomStory"))

        // 2. Verify all DTO classes are loadable and instantiate safely with defaults
        val story = Story(
            id = 1,
            title = "Test Story",
            image = null,
            content = null,
            contentText = null
        )
        assertEquals("Test Story", story.title)
        assertNull(story.image)

        val storyList = StoryListResponse(status = "success", data = listOf(story), totalPages = 1)
        assertEquals(1, storyList.data.size)
        assertEquals(1, storyList.totalPages)

        val storyDetail = StoryDetailResponse(status = "success", data = story)
        assertEquals("Test Story", storyDetail.data.title)

        val randomStory = RandomStoryResponse(status = "success", data = story)
        assertEquals(1, randomStory.data.id)

        // 3. Verify NetworkErrorResolver translates ClassCastException defensively
        val cce = ClassCastException("java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType")
        val resolved = NetworkErrorResolver.resolve(cce)
        assertNotNull(resolved)
        assertTrue("Resolved message must be polite Vietnamese", resolved.userMessage.isNotEmpty())
        assertFalse("Resolved message must NEVER contain ParameterizedType",
            resolved.userMessage.contains("ParameterizedType"))
        assertFalse("Resolved message must NEVER contain ClassCastException",
            resolved.userMessage.contains("ClassCastException"))
        assertFalse("Resolved message must NEVER contain java.lang",
            resolved.userMessage.contains("java.lang"))
        assertTrue("Resolved error for reflection crash must allow retry", resolved.canRetry)

        // 4. Verify RetrofitClient cohesion and dynamic ApiService proxy creation
        assertNotNull("RetrofitClient cookie property must be accessible", RetrofitClient.cookie)
        assertTrue("RetrofitClient BASE_URL must be valid URL", RetrofitClient.BASE_URL.startsWith("http"))

        val dummyRetrofit = Retrofit.Builder()
            .baseUrl("https://example.com/api/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val dummyApi = dummyRetrofit.create(ApiService::class.java)
        assertNotNull("Retrofit must successfully generate dynamic proxy for ApiService", dummyApi)
    }
}
