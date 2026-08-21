package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DetectQuantizationPathFixTest {

    @Test
    fun testDetectQuantizationTypePathAndFilename() {
        // 1. Path contains backbone_int8 directory
        assertEquals(
            OnnxSessionManager.QuantizationType.INT8,
            OnnxSessionManager.detectQuantizationType("/data/vieneu/backbone_int8/vieneu_prefill.onnx")
        )

        // 2. Filename contains int8
        assertEquals(
            OnnxSessionManager.QuantizationType.INT8,
            OnnxSessionManager.detectQuantizationType("/data/vieneu/backbone/vieneu_prefill_int8.onnx")
        )

        // 3. Default FP32 when no hints
        assertEquals(
            OnnxSessionManager.QuantizationType.FP32,
            OnnxSessionManager.detectQuantizationType("/data/vieneu/backbone/vieneu_prefill.onnx")
        )

        // 4. Path contains backbone_fp16 directory
        assertEquals(
            OnnxSessionManager.QuantizationType.FP16,
            OnnxSessionManager.detectQuantizationType("/data/vieneu/backbone_fp16/vieneu_prefill.onnx")
        )

        // 5. Filename contains fp16
        assertEquals(
            OnnxSessionManager.QuantizationType.FP16,
            OnnxSessionManager.detectQuantizationType("/data/vieneu/backbone/vieneu_prefill_fp16.onnx")
        )

        // Windows path separator variations
        assertEquals(
            OnnxSessionManager.QuantizationType.INT8,
            OnnxSessionManager.detectQuantizationType("C:\\models\\backbone_int8\\vieneu_decode.onnx")
        )
        assertEquals(
            OnnxSessionManager.QuantizationType.FP16,
            OnnxSessionManager.detectQuantizationType("C:\\models\\backbone_fp16\\vieneu_decode.onnx")
        )
        assertEquals(
            OnnxSessionManager.QuantizationType.FP32,
            OnnxSessionManager.detectQuantizationType("C:\\models\\backbone\\vieneu_decode.onnx")
        )

        // Metadata byte scan preserved when file has embedded metadata
        val tempFile = File.createTempFile("model_test", ".onnx")
        try {
            // Write metadata containing 'quantization:int8'
            tempFile.writeBytes("some_header_data_quantization:int8_footer_data".toByteArray(Charsets.UTF_8))
            assertEquals(
                OnnxSessionManager.QuantizationType.INT8,
                OnnxSessionManager.detectQuantizationType(tempFile.absolutePath)
            )
        } finally {
            tempFile.delete()
        }
    }
}
