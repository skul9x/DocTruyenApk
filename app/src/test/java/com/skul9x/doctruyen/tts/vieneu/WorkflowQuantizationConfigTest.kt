package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkflowQuantizationConfigTest {

    private fun findWorkflowFile(): File {
        val candidates = listOf(
            File(".github/workflows/android.yml"),
            File("../.github/workflows/android.yml"),
            File("../../.github/workflows/android.yml")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("android.yml workflow file not found in candidates: $candidates")
        return file
    }

    @Test
    fun testWorkflowQuantizationConfiguration() {
        val workflowFile = findWorkflowFile()
        val content = workflowFile.readText()

        // 1. Verifies .github/workflows/android.yml contains Python setup step before Gradle assemble step
        assertTrue("Workflow must use actions/setup-python@v5", content.contains("actions/setup-python@v5"))
        assertTrue("Workflow must configure python-version 3.10", content.contains("python-version: '3.10'") || content.contains("python-version: \"3.10\""))
        assertTrue("Workflow must configure pip cache", content.contains("cache: 'pip'") || content.contains("cache: \"pip\""))

        val pythonSetupIndex = content.indexOf("actions/setup-python")
        val assembleIndex = content.indexOf("assembleRelease")
        assertTrue("Python setup step must appear before assembleRelease", pythonSetupIndex in 0 until assembleIndex)

        // 2. Verifies .github/workflows/android.yml references tools/requirements.txt and tools/quantize_models.py
        assertTrue("Workflow must reference tools/requirements.txt", content.contains("tools/requirements.txt"))
        assertTrue("Workflow must reference tools/quantize_models.py", content.contains("tools/quantize_models.py"))

        val quantizeIndex = content.indexOf("tools/quantize_models.py")
        assertTrue("Quantize step must appear before assembleRelease", quantizeIndex in 0 until assembleIndex)

        // 3. Verifies workflow specifies correct output target directory app/src/main/assets/vieneu/backbone_int8
        assertTrue(
            "Workflow must specify input directory app/src/main/assets/vieneu/backbone",
            content.contains("--input-dir app/src/main/assets/vieneu/backbone")
        )
        assertTrue(
            "Workflow must specify output directory app/src/main/assets/vieneu/backbone_int8",
            content.contains("--output-dir app/src/main/assets/vieneu/backbone_int8")
        )

        // 4. Verifies workflow specifies --format int8 target
        assertTrue(
            "Workflow must specify --format int8",
            content.contains("--format int8")
        )
    }
}
