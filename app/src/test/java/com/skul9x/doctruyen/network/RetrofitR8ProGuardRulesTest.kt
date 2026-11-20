package com.skul9x.doctruyen.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.WildcardType
import kotlin.coroutines.Continuation

/**
 * Comprehensive verification for Phase 01:
 * 1. Signature and reflection attributes retention in proguard-rules.pro
 * 2. Kotlin Continuation keep rule for coroutines suspend functions
 * 3. Retrofit Call and Response generic signatures preservation
 * 4. Retrofit HTTP annotation interface keep rules for R8 optimization
 * 5. Gson TypeToken retention for generic deserialization
 * 6. Suspend function reflection signature integrity (emulating Retrofit HttpServiceMethod reflection)
 * 7. Gradle R8 compatibility configuration
 */
class RetrofitR8ProGuardRulesTest {

    private fun resolveFile(path: String): File {
        val f1 = File(path)
        if (f1.exists()) return f1
        val f2 = File("app", path)
        if (f2.exists()) return f2
        val parent = File("..", path)
        if (parent.exists()) return parent
        return if (path.startsWith("app/")) File(path.removePrefix("app/")) else f1
    }

    private fun loadProGuardContent(): String {
        val file = resolveFile("proguard-rules.pro")
        assertTrue("proguard-rules.pro must exist: ${file.absolutePath}", file.exists())
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun testSignatureAndReflectionAttributesRetained() {
        val content = loadProGuardContent()

        assertTrue("Must keep Signature attribute", content.contains("Signature"))
        assertTrue("Must keep InnerClasses attribute", content.contains("InnerClasses"))
        assertTrue("Must keep EnclosingMethod attribute", content.contains("EnclosingMethod"))
        assertTrue("Must keep AnnotationDefault attribute", content.contains("AnnotationDefault"))
        assertTrue(
            "Must keep RuntimeVisibleAnnotations",
            content.contains("RuntimeVisibleAnnotations")
        )
        assertTrue(
            "Must keep RuntimeVisibleParameterAnnotations",
            content.contains("RuntimeVisibleParameterAnnotations")
        )
    }

    @Test
    fun testKotlinContinuationRulePresent() {
        val content = loadProGuardContent()

        val hasContinuation = content.contains("class kotlin.coroutines.Continuation")
        assertTrue("Must retain kotlin.coroutines.Continuation", hasContinuation)

        val hasOptimizationFlags = content.contains("-keep,allowoptimization,allowshrinking,allowobfuscation class kotlin.coroutines.Continuation") ||
                (content.contains("allowobfuscation") && content.contains("kotlin.coroutines.Continuation"))
        assertTrue("Continuation rule must configure appropriate shrinking/obfuscation flags", hasOptimizationFlags)
    }

    @Test
    fun testRetrofitCallAndResponseSignaturesKept() {
        val content = loadProGuardContent()

        assertTrue("Must keep retrofit2.Call interface", content.contains("interface retrofit2.Call"))
        assertTrue("Must keep retrofit2.Response class", content.contains("class retrofit2.Response"))
    }

    @Test
    fun testRetrofitHttpAnnotationInterfaceRulesKept() {
        val content = loadProGuardContent()

        assertTrue(
            "Must keep methods annotated with @retrofit2.http.*",
            content.contains("@retrofit2.http.* <methods>;")
        )
        assertTrue(
            "Must contain conditional rule for interfaces with @retrofit2.http.*",
            content.contains("-if interface * {") && content.contains("-keep,allowobfuscation interface <1>")
        )
        assertTrue(
            "Must contain conditional rule for return types of @retrofit2.http.* methods",
            content.contains("-keep,allowoptimization,allowshrinking,allowobfuscation class <3>")
        )
    }

    @Test
    fun testGsonTypeTokenRetention() {
        val content = loadProGuardContent()

        assertTrue(
            "Must keep com.google.gson.reflect.TypeToken",
            content.contains("class com.google.gson.reflect.TypeToken")
        )
    }

    @Test
    fun testSuspendFunctionReflectionSignatureIntegrity() {
        // Emulate how Retrofit's HttpServiceMethod extracts the continuation type argument at runtime
        val apiClass = ApiService::class.java

        val targetMethods = listOf(
            "getStories" to StoryListResponse::class.java,
            "getStoryDetail" to StoryDetailResponse::class.java,
            "getRandomStory" to RandomStoryResponse::class.java
        )

        for ((methodName, expectedResponseType) in targetMethods) {
            val method: Method = apiClass.methods.firstOrNull { it.name == methodName }
                ?: throw AssertionError("Method $methodName not found on ApiService")

            val genericParamTypes = method.genericParameterTypes
            assertTrue("Method $methodName must have at least one parameter", genericParamTypes.isNotEmpty())

            val lastParamType = genericParamTypes.last()

            // When R8 minification strips signatures, lastParamType is raw Class instead of ParameterizedType,
            // which causes ClassCastException: java.lang.Class cannot be cast to ParameterizedType
            assertTrue(
                "Last parameter of suspend fun $methodName must be a ParameterizedType (was: ${lastParamType::class.java.name})",
                lastParamType is ParameterizedType
            )

            val paramType = lastParamType as ParameterizedType
            assertEquals(
                "Raw type of last parameter must be Continuation",
                Continuation::class.java,
                paramType.rawType
            )

            val typeArgs = paramType.actualTypeArguments
            assertEquals("Continuation must have exactly 1 type argument", 1, typeArgs.size)

            val continuationTypeArg = typeArgs[0]
            val resolvedType: Class<*> = when (continuationTypeArg) {
                is WildcardType -> continuationTypeArg.lowerBounds.firstOrNull() as? Class<*>
                    ?: continuationTypeArg.upperBounds.firstOrNull() as? Class<*>
                    ?: (continuationTypeArg.upperBounds[0] as ParameterizedType).rawType as Class<*>
                is Class<*> -> continuationTypeArg
                is ParameterizedType -> continuationTypeArg.rawType as Class<*>
                else -> throw AssertionError("Unexpected type argument: $continuationTypeArg")
            }

            assertEquals(
                "Resolved response type for $methodName must match expected DTO class",
                expectedResponseType,
                resolvedType
            )
        }
    }

    @Test
    fun testGradlePropertiesSafetyFlag() {
        val gradlePropsFile = resolveFile("gradle.properties")
        if (gradlePropsFile.exists()) {
            val content = gradlePropsFile.readText(Charsets.UTF_8)
            assertTrue(
                "gradle.properties must specify android.enableR8.fullMode=false",
                content.contains("android.enableR8.fullMode=false")
            )
        }
    }
}

