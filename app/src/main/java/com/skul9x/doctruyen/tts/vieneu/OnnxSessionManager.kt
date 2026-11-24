package com.skul9x.doctruyen.tts.vieneu

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.util.Log
import com.skul9x.doctruyen.utils.UserConfig
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.EnumSet

/**
 * Manages ONNX Runtime sessions for the 4 VieNeu-TTS models.
 */
class OnnxSessionManager(
    val prefillSession: OrtSession,
    val decodeSession: OrtSession,
    val acousticSession: OrtSession,
    val codecSession: OrtSession,
    val env: OrtEnvironment,
    val quantizationType: QuantizationType = QuantizationType.FP32,
    val executionProvider: String = "XNNPACK",
    val sessionOptions: OrtSession.SessionOptions? = null
) : AutoCloseable {

    enum class QuantizationType {
        FP32,
        INT8,
        FP16
    }

    data class SessionOptionsResult(
        val sessionOptions: OrtSession.SessionOptions,
        val executionProvider: String,
        val intraOpThreads: Int = 1,
        val interOpThreads: Int = 1
    )

    private var closed = false
    val isClosed: Boolean
        get() = closed

    private var internalPrefillSession: OrtSession? = prefillSession
    private var internalDecodeSession: OrtSession? = decodeSession
    private var internalAcousticSession: OrtSession? = acousticSession
    private var internalCodecSession: OrtSession? = codecSession
    private var internalSessionOptions: OrtSession.SessionOptions? = sessionOptions

    override fun close() {
        if (closed) return
        closed = true
        try { internalPrefillSession?.close() } catch (_: Exception) {}
        try { internalDecodeSession?.close() } catch (_: Exception) {}
        try { internalAcousticSession?.close() } catch (_: Exception) {}
        try { internalCodecSession?.close() } catch (_: Exception) {}
        try { internalSessionOptions?.close() } catch (_: Exception) {}
        internalPrefillSession = null
        internalDecodeSession = null
        internalAcousticSession = null
        internalCodecSession = null
        internalSessionOptions = null
    }

    companion object {
        private const val TAG = "OnnxSessionManager"

        private val QUANTIZATION_KEY = "quantization".toByteArray(Charsets.UTF_8)
        private val OP_MATMUL_INTEGER = "MatMulInteger".toByteArray(Charsets.UTF_8)
        private val OP_DYNAMIC_QUANTIZE = "DynamicQuantizeLinear".toByteArray(Charsets.UTF_8)
        private val OP_QLINEAR_MATMUL = "QLinearMatMul".toByteArray(Charsets.UTF_8)

        private fun containsPattern(bytes: ByteArray, pattern: ByteArray): Boolean {
            if (bytes.size < pattern.size) return false
            val maxScan = bytes.size - pattern.size
            for (i in 0..maxScan) {
                var matched = true
                for (j in pattern.indices) {
                    if (bytes[i + j] != pattern[j]) {
                        matched = false
                        break
                    }
                }
                if (matched) return true
            }
            return false
        }

        /**
         * Detect quantization type of an ONNX model.
         * Primary: Read ONNX metadata_props for key "quantization" ("int8", "fp16", "fp32").
         * Directory convention:
         *   - Folder `backbone_int8` or containing `int8` -> QuantizationType.INT8.
         *   - Folder `backbone_fp16` or containing `fp16` -> QuantizationType.FP16.
         *   - Folder `backbone` (canonical unquantized FP32 asset directory) -> QuantizationType.FP32.
         * Deep scan: Search for quantized ONNX operators (MatMulInteger, DynamicQuantizeLinear, QLinearMatMul) on standalone models.
         * Fallback: Parse filename convention (*_int8.onnx, *_fp16.onnx, directory conventions, default = FP32).
         */
        fun detectQuantizationType(modelPath: String): QuantizationType {
            val file = File(modelPath)
            val lowerPath = modelPath.lowercase().replace('\\', '/')
            val fileName = file.name.lowercase()

            // 1. Explicit filename naming conventions take highest priority
            if (fileName.contains("_int8.") || fileName.contains(".int8.") || fileName.endsWith("_int8.onnx")) {
                return QuantizationType.INT8
            }
            if (fileName.contains("_fp16.") || fileName.contains(".fp16.") || fileName.endsWith("_fp16.onnx")) {
                return QuantizationType.FP16
            }
            if (fileName.contains("_fp32.") || fileName.contains(".fp32.") || fileName.endsWith("_fp32.onnx")) {
                return QuantizationType.FP32
            }

            // 2. Scan for explicit metadata key "quantization"
            try {
                if (file.exists() && file.length() > 0) {
                    val scanSize = minOf(file.length(), 2 * 1024 * 1024L).toInt()
                    val buffer = ByteArray(scanSize)
                    file.inputStream().use { input ->
                        var totalRead = 0
                        while (totalRead < scanSize) {
                            val r = input.read(buffer, totalRead, scanSize - totalRead)
                            if (r <= 0) break
                            totalRead += r
                        }
                    }
                    val metaDetected = extractMetadataQuantization(buffer)
                    if (metaDetected != null) {
                        return metaDetected
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed scanning ONNX metadata in $modelPath: ${e.message}")
            }

            // 3. Parent directory convention check
            val parent = file.parentFile
            if (parent != null) {
                val parentName = parent.name.lowercase()
                if (parentName.contains("backbone_int8") || parentName.endsWith("_int8")) {
                    return QuantizationType.INT8
                }
                if (parentName.contains("backbone_fp16") || parentName.endsWith("_fp16")) {
                    return QuantizationType.FP16
                }
                if (parentName == "backbone" || parentName == "backbone_fp32") {
                    return QuantizationType.FP32
                }
            }

            // Path segment directory convention checks (for relative or mocked paths)
            if (lowerPath.contains("/backbone_int8/") || lowerPath.contains("backbone_int8")) {
                return QuantizationType.INT8
            }
            if (lowerPath.contains("/backbone_fp16/") || lowerPath.contains("backbone_fp16")) {
                return QuantizationType.FP16
            }
            if (lowerPath.contains("/backbone/") && !lowerPath.contains("int8") && !lowerPath.contains("fp16")) {
                return QuantizationType.FP32
            }

            // 4. Secondary: Deep byte pattern detection for quantized ONNX operators on standalone models
            try {
                if (file.exists() && file.length() > 0) {
                    val scanSize = minOf(file.length(), 2 * 1024 * 1024L).toInt()
                    val buffer = ByteArray(scanSize)
                    file.inputStream().use { input ->
                        var totalRead = 0
                        while (totalRead < scanSize) {
                            val r = input.read(buffer, totalRead, scanSize - totalRead)
                            if (r <= 0) break
                            totalRead += r
                        }
                    }
                    if (containsPattern(buffer, OP_MATMUL_INTEGER) ||
                        containsPattern(buffer, OP_DYNAMIC_QUANTIZE) ||
                        containsPattern(buffer, OP_QLINEAR_MATMUL)
                    ) {
                        return QuantizationType.INT8
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed scanning ONNX operators in $modelPath: ${e.message}")
            }

            // 5. Fallback path tokens
            if (lowerPath.contains("int8")) {
                return QuantizationType.INT8
            }
            if (lowerPath.contains("fp16")) {
                return QuantizationType.FP16
            }

            // 6. Companion model check within directory if companion prefill exists
            try {
                if (parent != null && parent.exists()) {
                    val companionPrefill = File(parent, "vieneu_prefill.onnx")
                    if (companionPrefill.exists() && companionPrefill.absolutePath != file.absolutePath) {
                        val companionQuant = detectQuantizationType(companionPrefill.absolutePath)
                        if (companionQuant == QuantizationType.INT8) {
                            return QuantizationType.INT8
                        }
                    }
                }
            } catch (_: Throwable) {}

            return QuantizationType.FP32
        }

        private fun extractMetadataQuantization(bytes: ByteArray): QuantizationType? {
            val maxScan = bytes.size - QUANTIZATION_KEY.size
            for (i in 0 until maxScan) {
                var matched = true
                for (j in QUANTIZATION_KEY.indices) {
                    if (bytes[i + j] != QUANTIZATION_KEY[j]) {
                        matched = false
                        break
                    }
                }
                if (matched) {
                    val endIdx = minOf(bytes.size, i + QUANTIZATION_KEY.size + 64)
                    val slice = String(bytes, i + QUANTIZATION_KEY.size, endIdx - (i + QUANTIZATION_KEY.size), Charsets.UTF_8).lowercase()
                    if (slice.contains("int8")) return QuantizationType.INT8
                    if (slice.contains("fp16")) return QuantizationType.FP16
                    if (slice.contains("fp32")) return QuantizationType.FP32
                }
            }
            return null
        }

        private fun extractQuantizationFromBytes(bytes: ByteArray): QuantizationType? {
            val meta = extractMetadataQuantization(bytes)
            if (meta != null) return meta

            if (containsPattern(bytes, OP_MATMUL_INTEGER) ||
                containsPattern(bytes, OP_DYNAMIC_QUANTIZE) ||
                containsPattern(bytes, OP_QLINEAR_MATMUL)
            ) {
                return QuantizationType.INT8
            }

            return null
        }

        @androidx.annotation.VisibleForTesting
        var lastConfiguredIntraOpThreads: Int = 0

        @androidx.annotation.VisibleForTesting
        var lastConfiguredInterOpThreads: Int = 0

        fun hasInt8Assets(context: Context): Boolean {
            val assets = listAssets(context, "vieneu/backbone_int8")
            if (!assets.isNullOrEmpty()) return true
            return try {
                val targetBase = File(context.filesDir, "vieneu")
                val int8Dir = File(targetBase, "backbone_int8")
                int8Dir.exists() && int8Dir.list()?.isNotEmpty() == true
            } catch (_: Throwable) {
                false
            }
        }

        fun isPrecisionAvailable(context: Context, precision: String): Boolean {
            val norm = when (precision.trim().uppercase()) {
                "INT8" -> "INT8"
                "FP32" -> "FP32"
                else -> return false
            }
            return if (norm == "INT8") {
                hasInt8Assets(context)
            } else {
                val assets = listAssets(context, "vieneu/backbone")
                if (!assets.isNullOrEmpty()) return true
                try {
                    val targetBase = File(context.filesDir, "vieneu")
                    val fp32Dir = File(targetBase, "backbone")
                    fp32Dir.exists() && fp32Dir.list()?.isNotEmpty() == true
                } catch (_: Throwable) {
                    false
                }
            }
        }

        fun getAvailablePrecisions(context: Context): List<String> {
            val list = mutableListOf<String>()
            if (isPrecisionAvailable(context, "FP32")) list.add("FP32")
            if (isPrecisionAvailable(context, "INT8")) list.add("INT8")
            return list
        }

        fun resolveEffectivePrecision(context: Context, precision: String): String {
            val norm = when (precision.trim().uppercase()) {
                "INT8" -> "INT8"
                "FP32" -> "FP32"
                else -> "FP32"
            }
            return if (norm == "INT8" && !hasInt8Assets(context)) {
                "FP32"
            } else {
                norm
            }
        }

        fun configureSessionOptions(
            quantizationType: QuantizationType,
            threads: Int = 0
        ): SessionOptionsResult {
            val sessionOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            }

            val bigCoreCount = DevicePerformanceHelper.getBigCoreCount().coerceIn(2, 4)
            val effectiveThreads = if (threads > 0) threads else bigCoreCount
            var epUsed = "CPU"

            // Set intra-op threads on SessionOptions before registering execution providers
            // to ensure CPU fallback operators also run with effectiveThreads instead of 1
            sessionOptions.setIntraOpNumThreads(effectiveThreads)
            sessionOptions.setInterOpNumThreads(1)
            sessionOptions.addConfigEntry("session.intra_op.allow_spinning", "0")
            lastConfiguredIntraOpThreads = effectiveThreads
            lastConfiguredInterOpThreads = 1

            when (quantizationType) {
                QuantizationType.FP16 -> {
                    var nnapiSuccess = false
                    try {
                        sessionOptions.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                        epUsed = "NNAPI"
                        nnapiSuccess = true
                        Log.i(TAG, "Configured NNAPI EP (USE_FP16) for FP16 model")
                    } catch (e: Throwable) {
                        Log.w(TAG, "NNAPI EP initialization failed for FP16 model: ${e.message}. Falling back to XNNPACK with CPU FP32 casting.")
                    }

                    if (!nnapiSuccess) {
                        try {
                            sessionOptions.addXnnpack(mapOf("intra_op_num_threads" to effectiveThreads.toString()))
                            epUsed = "XNNPACK (Fallback)"
                        } catch (_: Throwable) {
                            epUsed = "CPU (Fallback)"
                        }
                    }
                }
                QuantizationType.INT8, QuantizationType.FP32 -> {
                    try {
                        sessionOptions.addXnnpack(mapOf("intra_op_num_threads" to effectiveThreads.toString()))
                        epUsed = "XNNPACK"
                        Log.i(TAG, "Configured XNNPACK EP with $effectiveThreads threads for $quantizationType model")
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to add XNNPACK EP: ${e.message}. Falling back to standard CPU thread pool.")
                        epUsed = "CPU"
                    }
                }
            }

            return SessionOptionsResult(sessionOptions, epUsed, effectiveThreads, 1)
        }

        fun create(
            context: Context,
            threads: Int = 0,
            precision: String = "INT8"
        ): OnnxSessionManager {
            val env = OrtEnvironment.getEnvironment()
            val baseDir = ensureAssetsExtracted(context, precision)
            val backboneDir = getBackboneDir(baseDir, precision)
            val codecDir = File(baseDir, "codec")

            val prefillPath = File(backboneDir, "vieneu_prefill.onnx").absolutePath
            val decodePath = File(backboneDir, "vieneu_decode_step.onnx").absolutePath
            val acousticPath = File(backboneDir, "vieneu_acoustic_cached.onnx").absolutePath
            val codecPath = File(codecDir, "moss_audio_tokenizer_decode_full.onnx").absolutePath

            val effectivePrecision = resolveEffectivePrecision(context, precision)
            val detectedQuant = detectQuantizationType(prefillPath)
            if (effectivePrecision != detectedQuant.name) {
                Log.w(TAG, "⚠️ Precision mismatch: Effective precision is $effectivePrecision but detected quantization is $detectedQuant on $prefillPath.")
            }
            val (sessionOptions, epUsed) = configureSessionOptions(detectedQuant, threads)

            Log.i(TAG, "Creating ONNX sessions [Quantization: $detectedQuant, EP: $epUsed, Precision: $precision]...")
            val prefillSession = env.createSession(prefillPath, sessionOptions)
            val decodeSession = env.createSession(decodePath, sessionOptions)
            val acousticSession = env.createSession(acousticPath, sessionOptions)
            val codecSession = env.createSession(codecPath, sessionOptions)

            Log.i(TAG, "All 4 ONNX sessions initialized successfully [EP: $epUsed]")
            return OnnxSessionManager(
                prefillSession = prefillSession,
                decodeSession = decodeSession,
                acousticSession = acousticSession,
                codecSession = codecSession,
                env = env,
                quantizationType = detectedQuant,
                executionProvider = epUsed,
                sessionOptions = sessionOptions
            )
        }

        fun getBackboneDir(baseDir: File, precision: String): File {
            val normPrecision = when (precision.trim().uppercase()) {
                "INT8" -> "INT8"
                "FP32" -> "FP32"
                else -> "FP32"
            }
            val int8Dir = File(baseDir, "backbone_int8")
            return if (normPrecision == "INT8" && int8Dir.exists() && int8Dir.list()?.isNotEmpty() == true) {
                int8Dir
            } else {
                File(baseDir, "backbone")
            }
        }

        fun createFromFiles(
            backboneDir: File,
            codecDir: File,
            threads: Int = 0,
            quantizationTypeOverride: QuantizationType? = null,
            precision: String = "INT8"
        ): OnnxSessionManager {
            val env = OrtEnvironment.getEnvironment()
            val parent = backboneDir.parentFile
            val effectiveBackboneDir = if (parent != null && (backboneDir.name == "backbone" || backboneDir.name == "backbone_int8")) {
                getBackboneDir(parent, precision)
            } else {
                backboneDir
            }
            val prefillPath = File(effectiveBackboneDir, "vieneu_prefill.onnx").absolutePath
            val decodePath = File(effectiveBackboneDir, "vieneu_decode_step.onnx").absolutePath
            val acousticPath = File(effectiveBackboneDir, "vieneu_acoustic_cached.onnx").absolutePath
            val codecPath = File(codecDir, "moss_audio_tokenizer_decode_full.onnx").absolutePath

            val detectedQuant = quantizationTypeOverride ?: run {
                val detected = detectQuantizationType(prefillPath)
                if (precision.trim().equals("FP32", ignoreCase = true) && (effectiveBackboneDir.name == "backbone" || effectiveBackboneDir.name == "backbone_fp32")) {
                    QuantizationType.FP32
                } else if (precision.trim().equals("INT8", ignoreCase = true) && effectiveBackboneDir.name == "backbone_int8") {
                    QuantizationType.INT8
                } else {
                    detected
                }
            }
            val (sessionOptions, epUsed) = configureSessionOptions(detectedQuant, threads)

            val prefillSession = env.createSession(prefillPath, sessionOptions)
            val decodeSession = env.createSession(decodePath, sessionOptions)
            val acousticSession = env.createSession(acousticPath, sessionOptions)
            val codecSession = env.createSession(codecPath, sessionOptions)

            return OnnxSessionManager(
                prefillSession = prefillSession,
                decodeSession = decodeSession,
                acousticSession = acousticSession,
                codecSession = codecSession,
                env = env,
                quantizationType = detectedQuant,
                executionProvider = epUsed,
                sessionOptions = sessionOptions
            )
        }

        @androidx.annotation.VisibleForTesting
        var assetListProvider: ((Context, String) -> Array<String>?)? = null

        @androidx.annotation.VisibleForTesting
        var assetOpenProvider: ((Context, String) -> InputStream)? = null

        private fun listAssets(context: Context, path: String): Array<String>? {
            return assetListProvider?.invoke(context, path) ?: try {
                context.assets.list(path)
            } catch (_: Exception) {
                val candidateDirs = listOf(File("src/main/assets"), File("app/src/main/assets"))
                for (base in candidateDirs) {
                    val target = File(base, path)
                    if (target.exists()) {
                        return target.list() ?: emptyArray()
                    }
                }
                null
            }
        }

        private fun openAssetStream(context: Context, path: String): InputStream {
            return assetOpenProvider?.invoke(context, path) ?: context.assets.open(path)
        }

        fun ensureAssetsExtracted(context: Context, precision: String = "INT8"): File {
            val targetBase = File(context.filesDir, "vieneu")
            val normPrecision = when (precision.trim().uppercase()) {
                "INT8" -> "INT8"
                "FP32" -> "FP32"
                else -> "FP32"
            }

            val effectivePrecision = resolveEffectivePrecision(context, precision)
            val int8Assets = listAssets(context, "vieneu/backbone_int8")
            val assetFolder = if (effectivePrecision == "INT8") {
                Log.i(TAG, "Found INT8 assets in 'vieneu/backbone_int8' (${int8Assets?.size ?: 0} files). Selecting INT8 backbone.")
                "vieneu/backbone_int8"
            } else {
                if (normPrecision == "INT8") {
                    Log.i(TAG, "ℹ️ INT8 precision requested, but 'vieneu/backbone_int8' assets not bundled in this build. Selecting FP32 backbone.")
                }
                "vieneu/backbone"
            }
            val targetSubdir = if (assetFolder.endsWith("backbone_int8")) "backbone_int8" else "backbone"

            // Extract selected backbone folder
            copyAssetFolder(context, assetFolder, File(targetBase, targetSubdir))

            // Ensure other common folders and root files are extracted
            val children = listAssets(context, "vieneu") ?: emptyArray()
            for (child in children) {
                if (child != "backbone" && child != "backbone_int8") {
                    val childPath = "vieneu/$child"
                    val subTarget = File(targetBase, child)
                    val subChildren = listAssets(context, childPath)
                    if (subChildren != null && subChildren.isNotEmpty()) {
                        copyAssetFolder(context, childPath, subTarget)
                    } else {
                        copyAssetFile(context, childPath, subTarget)
                    }
                }
            }

            return targetBase
        }

        private fun copyAssetFolder(context: Context, assetPath: String, targetDir: File) {
            val children = listAssets(context, assetPath) ?: return

            if (children.isEmpty()) {
                // It's a file
                copyAssetFile(context, assetPath, targetDir)
            } else {
                // It's a directory
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                for (child in children) {
                    val subAssetPath = if (assetPath.isEmpty()) child else "$assetPath/$child"
                    val subTarget = File(targetDir, child)
                    val subChildren = listAssets(context, subAssetPath)
                    if (subChildren != null && subChildren.isNotEmpty()) {
                        copyAssetFolder(context, subAssetPath, subTarget)
                    } else {
                        copyAssetFile(context, subAssetPath, subTarget)
                    }
                }
            }
        }

        private fun copyAssetFile(context: Context, assetPath: String, targetFile: File) {
            if (targetFile.exists() && targetFile.length() > 0) {
                // Already extracted
                return
            }
            targetFile.parentFile?.mkdirs()
            openAssetStream(context, assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}
