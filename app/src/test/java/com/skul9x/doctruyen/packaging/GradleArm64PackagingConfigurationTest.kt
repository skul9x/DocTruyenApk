package com.skul9x.doctruyen.packaging

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Comprehensive verification for Phase 01:
 * Gradle Architecture & Packaging Alignment (arm64-v8a only)
 *
 * Responsibilities:
 * 1. Validate that the Gradle build script specifies arm64-v8a as the sole targeted NDK ABI filter.
 * 2. Verify that legacy or redundant ABIs (armeabi-v7a, x86_64, x86) are excluded from native build targets.
 * 3. Verify that ABI split configurations are configured to output a single unified APK rather than generating multi-ABI splits.
 * 4. Verify that critical asset compression exclusions (noCompress) for .onnx, .data, .npz, and .json files are preserved.
 */
class GradleArm64PackagingConfigurationTest {

    private fun resolveFile(path: String): File {
        val f1 = File(path)
        if (f1.exists()) return f1
        val f2 = File("app", path)
        if (f2.exists()) return f2
        val parent = File("..", path)
        if (parent.exists()) return parent
        return if (path.startsWith("app/")) File(path.removePrefix("app/")) else f1
    }

    private fun readGradleScript(): String {
        val gradleFile = resolveFile("app/build.gradle.kts")
        assertTrue("build.gradle.kts must exist at path: ${gradleFile.absolutePath}", gradleFile.exists())
        return gradleFile.readText(Charsets.UTF_8)
    }

    @Test
    fun testNdkAbiFiltersSolelyTargetArm64V8a() {
        val content = readGradleScript()

        // 1. Extract ndk { ... } block
        val ndkRegex = Regex("""ndk\s*\{([^}]+)\}""")
        val ndkMatch = ndkRegex.find(content)
        assertNotNull("build.gradle.kts must contain an 'ndk { ... }' configuration block", ndkMatch)

        val ndkBlock = ndkMatch!!.groupValues[1]

        // 2. Verify arm64-v8a is targeted
        assertTrue("ndk configuration must specify arm64-v8a in abiFilters: $ndkBlock",
            ndkBlock.contains("\"arm64-v8a\"")
        )

        // 3. Verify no other ABIs are present in ndk block
        val forbiddenAbis = listOf("armeabi-v7a", "x86_64", "x86", "mips", "armeabi")
        for (abi in forbiddenAbis) {
            assertFalse(
                "ndk configuration must not include non-arm64 ABI '$abi': $ndkBlock",
                ndkBlock.contains("\"$abi\"")
            )
        }

        // 4. Verify abiFilters syntax explicitly
        assertTrue("abiFilters must assign or append listOf(\"arm64-v8a\")",
            ndkBlock.contains("abiFilters += listOf(\"arm64-v8a\")") ||
            ndkBlock.contains("abiFilters.addAll(listOf(\"arm64-v8a\"))") ||
            ndkBlock.contains("abiFilters = setOf(\"arm64-v8a\")")
        )
    }

    @Test
    fun testLegacyAndRedundantAbisExcludedFromBuildTargets() {
        val content = readGradleScript()

        // Redundant ABIs that must be purged from build targets
        val legacyAbis = listOf("armeabi-v7a", "x86_64", "x86")

        for (abi in legacyAbis) {
            assertFalse(
                "Legacy ABI '$abi' must not appear in build targets within build.gradle.kts",
                content.contains("\"$abi\"")
            )
        }
    }

    @Test
    fun testAbiSplitsConfiguredForSingleUnifiedApkOutput() {
        val content = readGradleScript()

        // 1. Locate splits block
        val splitsRegex = Regex("""splits\s*\{([^}]+(\{[^}]+\})*[^}]*)\}""")
        val splitsMatch = splitsRegex.find(content)
        assertNotNull("build.gradle.kts must contain a 'splits' configuration block", splitsMatch)

        val splitsBlock = splitsMatch!!.groupValues[0]

        // 2. Verify ABI split multi-APK generation is explicitly disabled
        assertTrue(
            "ABI splits must have isEnable = false to avoid multi-APK fragmentation: $splitsBlock",
            splitsBlock.contains("isEnable = false")
        )

        // 3. Verify isUniversalApk is false to avoid redundant universal APK generation
        assertTrue(
            "ABI splits must have isUniversalApk = false to avoid universal APK bloat: $splitsBlock",
            splitsBlock.contains("isUniversalApk = false")
        )

        // 4. Verify splits block does not enable multi-APK splits
        assertFalse(
            "splits block must not enable ABI splits (isEnable = true)",
            splitsBlock.contains("isEnable = true")
        )
        assertFalse(
            "splits block must not enable universal APK (isUniversalApk = true)",
            splitsBlock.contains("isUniversalApk = true")
        )
    }

    @Test
    fun testCriticalAssetNoCompressExclusionsPreserved() {
        val content = readGradleScript()

        // 1. Locate androidResources block
        val resourcesRegex = Regex("""androidResources\s*\{([^}]+)\}""")
        val resourcesMatch = resourcesRegex.find(content)
        assertNotNull("build.gradle.kts must contain an 'androidResources' block", resourcesMatch)

        val resourcesBlock = resourcesMatch!!.groupValues[1]

        // 2. Verify required uncompressed zero-copy mmap extensions
        val requiredNoCompressExtensions = listOf("onnx", "data", "npz", "json")
        for (ext in requiredNoCompressExtensions) {
            assertTrue(
                "androidResources.noCompress must retain extension '$ext' for zero-copy memory mapping: $resourcesBlock",
                resourcesBlock.contains("\"$ext\"")
            )
        }
    }

    @Test
    fun testPackagingAndBuildTypeIntegrity() {
        val content = readGradleScript()

        // 1. Minification must remain enabled for release
        assertTrue("Release build must retain isMinifyEnabled = true",
            content.contains("isMinifyEnabled = true")
        )

        // 2. Packaging block must keep jniLibs and resource exclusions
        assertTrue("Packaging block must retain useLegacyPackaging = false",
            content.contains("useLegacyPackaging = false")
        )
        assertTrue("Resource excludes must be retained",
            content.contains("META-INF/INDEX.LIST")
        )

        // 3. Signing configs must be referenced
        assertTrue("Release build type must retain signingConfig reference",
            content.contains("signingConfig = signingConfigs.getByName(\"release\")")
        )
    }
}
