package com.skul9x.doctruyen.ci

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Comprehensive File-Based Test for Phase 02:
 * GitHub Actions CI/CD Workflow Streamlining (FP32 Release)
 *
 * Responsibilities:
 * 1. Parse and validate the structure and content of `.github/workflows/android.yml`.
 * 2. Assert that no steps invoke Python setup (actions/setup-python), pip install, or tools/quantize_models.py.
 * 3. Assert that no steps reference backbone_int8 generation or directory verification.
 * 4. Assert that the release artifact preparation logic accurately targets the single release APK output from the Gradle build.
 * 5. Assert that keystore decoding, signing credentials, smart release versioning, and GitHub release upload commands remain intact and properly configured.
 */
class ReleaseWorkflowPipelineTest {

    private fun resolveWorkflowFile(): File {
        val paths = listOf(
            ".github/workflows/android.yml",
            "../.github/workflows/android.yml",
            "../../.github/workflows/android.yml"
        )
        for (path in paths) {
            val file = File(path)
            if (file.exists() && file.isFile) {
                return file
            }
        }
        fail("Workflow file not found in any probed relative path: $paths")
        throw IllegalStateException("Unreachable")
    }

    private fun readWorkflowContent(): String {
        val file = resolveWorkflowFile()
        assertTrue("Workflow file must exist and be readable", file.exists() && file.canRead())
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun testWorkflowStructureAndTriggers() {
        val content = readWorkflowContent()

        // 1. Workflow metadata and trigger verification
        assertTrue("Workflow name must be defined", content.contains("name: Android CI/CD Build & Release"))
        assertTrue("Workflow must trigger on push to main", content.contains("branches: [ \"main\" ]") || content.contains("branches: [ 'main' ]") || content.contains("branches:\n      - main"))
        assertTrue("Workflow must support manual workflow_dispatch", content.contains("workflow_dispatch:"))
        assertTrue("Job must require write permissions for release creation", content.contains("permissions:") && content.contains("contents: write"))
        assertTrue("Runner must target ubuntu-latest", content.contains("runs-on: ubuntu-latest"))

        // 2. Foundation environment steps
        assertTrue("Must use actions/checkout@v4", content.contains("actions/checkout@v4"))
        assertTrue("Must fetch full history (fetch-depth: 0) for smart versioning", content.contains("fetch-depth: 0"))
        assertTrue("Must use actions/setup-java@v4", content.contains("actions/setup-java@v4"))
        assertTrue("Must configure temurin JDK 17 with gradle cache",
            content.contains("distribution: 'temurin'") &&
            content.contains("java-version: '17'") &&
            content.contains("cache: 'gradle'")
        )
        assertTrue("Must grant execute permission for gradlew", content.contains("chmod +x gradlew"))
    }

    @Test
    fun testPythonSetupAndQuantizationStepsCompletelyEliminated() {
        val content = readWorkflowContent()

        // 1. Ensure actions/setup-python is completely absent
        assertFalse(
            "Workflow must NOT invoke actions/setup-python",
            content.contains("actions/setup-python")
        )

        // 2. Ensure pip install & requirements.txt are completely absent
        assertFalse(
            "Workflow must NOT install Python quantization dependencies",
            content.contains("pip install")
        )
        assertFalse(
            "Workflow must NOT reference tools/requirements.txt",
            content.contains("tools/requirements.txt") || content.contains("requirements.txt")
        )

        // 3. Ensure tools/quantize_models.py execution is eliminated
        assertFalse(
            "Workflow must NOT execute tools/quantize_models.py",
            content.contains("quantize_models.py")
        )

        // 4. Ensure backbone_int8 directory and asset references are eliminated
        assertFalse(
            "Workflow must NOT reference backbone_int8 model generation or verification",
            content.contains("backbone_int8")
        )
        assertFalse(
            "Workflow must NOT contain INT8 verification step",
            content.contains("Verify Quantized Assets")
        )
        assertFalse(
            "Workflow must NOT contain Quantize VieNeu ONNX Models step",
            content.contains("Quantize VieNeu ONNX Models")
        )
    }

    @Test
    fun testKeystoreDecodingAndSigningIntegrityIntact() {
        val content = readWorkflowContent()

        // 1. Keystore decoding step validation
        assertTrue("Must contain 'Decode Keystore' step", content.contains("name: Decode Keystore"))
        assertTrue("Must read KEYSTORE_BASE64 secret", content.contains("KEYSTORE_BASE64: \${{ secrets.KEYSTORE_BASE64 }}"))
        assertTrue("Must decode base64 keystore into app/skul9x.jks", content.contains("base64 --decode > app/skul9x.jks"))

        // 2. Signing credentials and assembleRelease execution
        assertTrue("Must contain 'Build & Sign Release APK' step", content.contains("name: Build & Sign Release APK"))
        assertTrue("Must pass STORE_FILE environment variable", content.contains("STORE_FILE: skul9x.jks"))
        assertTrue("Must pass STORE_PASSWORD with fallback", content.contains("secrets.STORE_PASSWORD") || content.contains("secrets.KEYSTORE_PASSWORD"))
        assertTrue("Must pass KEY_ALIAS secret", content.contains("KEY_ALIAS: \${{ secrets.KEY_ALIAS }}"))
        assertTrue("Must pass KEY_PASSWORD secret", content.contains("KEY_PASSWORD:"))
        assertTrue("Must execute assembleRelease task on :app module", content.contains("./gradlew :app:assembleRelease"))
    }

    @Test
    fun testSmartVersioningAndSlugificationRetained() {
        val content = readWorkflowContent()

        // 1. App name slugification
        assertTrue("Must contain Slugify App Name step", content.contains("name: Slugify App Name"))
        assertTrue("Must export APP_NAME output", content.contains("APP_NAME=\$SLUG_NAME") && content.contains("\$GITHUB_OUTPUT"))

        // 2. Determine Next Release Version
        assertTrue("Must contain Determine Next Release Version step", content.contains("name: Determine Next Release Version"))
        assertTrue("Must inspect git exact match tags", content.contains("git describe --tags --exact-match"))
        assertTrue("Must query GitHub CLI for latest release tag", content.contains("gh release view --json tagName"))
        assertTrue("Must inspect assets for existing APK", content.contains("gh release view \"\$LATEST_TAG\" --json assets"))
        assertTrue("Must export VERSION output", content.contains("VERSION=\$NEW_VERSION") && content.contains("\$GITHUB_OUTPUT"))
    }

    @Test
    fun testApkPreparationTargetsSingleUnifiedReleaseApkDirectly() {
        val content = readWorkflowContent()

        // 1. Prepare and Rename APK step existence
        assertTrue("Must contain Prepare and Rename APK step", content.contains("name: Prepare and Rename APK"))

        // 2. Verify deterministic single APK targeting
        assertTrue("Must reference release directory", content.contains("RELEASE_DIR=\"app/build/outputs/apk/release\""))
        assertTrue("Must directly target app-release.apk as source APK",
            content.contains("SOURCE_APK=\"\${RELEASE_DIR}/app-release.apk\"") ||
            content.contains("SOURCE_APK=\"\$RELEASE_DIR/app-release.apk\"")
        )
        assertTrue("Must target formatted output name with app name and version",
            content.contains("TARGET_APK=\"\${RELEASE_DIR}/\${APP_NAME}-\${VERSION}.apk\"")
        )

        // 3. Verify fail-fast validation when release APK is missing
        assertTrue("Must validate source APK existence with fail-fast check",
            content.contains("if [ ! -f \"\$SOURCE_APK\" ]; then")
        )
        assertTrue("Must emit error annotation if source APK is missing",
            content.contains("::error::Release APK not found at \$SOURCE_APK")
        )

        // 4. Assert absence of ambiguous find fallback for multi-split APKs
        assertFalse(
            "Must NOT use ambiguous wildcard find fallback (find \"\$RELEASE_DIR\" -name \"*.apk\")",
            content.contains("find \"\$RELEASE_DIR\" -name \"*.apk\"") ||
            content.contains("find \"\$RELEASE_DIR\" -name '*.apk'")
        )

        // 5. Verify APK_PATH output export
        assertTrue("Must export APK_PATH to GITHUB_OUTPUT",
            content.contains("APK_PATH=\$TARGET_APK") && content.contains("\$GITHUB_OUTPUT")
        )
    }

    @Test
    fun testGitHubReleaseCreationAndUploadPreserved() {
        val content = readWorkflowContent()

        // 1. Step existence
        assertTrue(
            "Must contain Create or Update GitHub Release & Upload APK step",
            content.contains("name: Create or Update GitHub Release & Upload APK")
        )

        // 2. Secrets and variables
        assertTrue("Must pass GH_TOKEN", content.contains("GH_TOKEN: \${{ github.token }}"))
        assertTrue("Must pass VERSION from version step", content.contains("VERSION: \${{ steps.version.outputs.VERSION }}"))
        assertTrue("Must pass APK_PATH from prepare_apk step", content.contains("APK_PATH: \${{ steps.prepare_apk.outputs.APK_PATH }}"))

        // 3. Idempotent upload command with --clobber
        assertTrue("Must check existing release using gh release view", content.contains("gh release view \"\$VERSION\""))
        assertTrue("Must upload APK with --clobber if release exists",
            content.contains("gh release upload \"\$VERSION\" \"\$APK_PATH\" --clobber")
        )

        // 4. Release creation command with --generate-notes
        assertTrue("Must create release if not exists",
            content.contains("gh release create \"\$VERSION\" \"\$APK_PATH\"")
        )
        assertTrue("Must generate release notes on create", content.contains("--generate-notes"))
    }
}
