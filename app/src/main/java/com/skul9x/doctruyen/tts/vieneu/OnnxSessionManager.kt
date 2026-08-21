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

        /**
         * Detect quantization type of an ONNX model.
         * Primary: Read ONNX metadata_props for key "quantization" ("int8", "fp16", "fp32").
         * Fallback: Parse filename convention (*_int8.onnx, *_fp16.onnx, default = FP32).
         */
        fun detectQuantizationType(modelPath: String): QuantizationType {
            try {
                val file = File(modelPath)
                if (file.exists() && file.length() > 0) {
                    val scanSize = minOf(file.length(), 2 * 1024 * 1024L).toInt()
                    val buffer = ByteArray(scanSize)
                    file.inputStream().use { input ->
                        input.read(buffer)
                    }
                    val detected = extractQuantizationFromBytes(buffer)
                    if (detected != null) {
                        return detected
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed scanning ONNX metadata in $modelPath: ${e.message}")
            }

            val lowerName = File(modelPath).name.lowercase()
            return when {
                lowerName.contains("int8") -> QuantizationType.INT8
                lowerName.contains("fp16") -> QuantizationType.FP16
                else -> QuantizationType.FP32
            }
        }

        private fun extractQuantizationFromBytes(bytes: ByteArray): QuantizationType? {
            val searchKey = "quantization".toByteArray(Charsets.UTF_8)
            val maxScan = bytes.size - searchKey.size
            for (i in 0 until maxScan) {
                var matched = true
                for (j in searchKey.indices) {
                    if (bytes[i + j] != searchKey[j]) {
                        matched = false
                        break
                    }
                }
                if (matched) {
                    val endIdx = minOf(bytes.size, i + searchKey.size + 64)
                    val slice = String(bytes, i + searchKey.size, endIdx - (i + searchKey.size), Charsets.UTF_8).lowercase()
                    if (slice.contains("int8")) return QuantizationType.INT8
                    if (slice.contains("fp16")) return QuantizationType.FP16
                    if (slice.contains("fp32")) return QuantizationType.FP32
                }
            }
            return null
        }

        fun configureSessionOptions(
            quantizationType: QuantizationType,
            threads: Int = 0
        ): Pair<OrtSession.SessionOptions, String> {
            val sessionOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            }

            val bigCoreCount = DevicePerformanceHelper.getBigCoreCount().coerceIn(2, 4)
            val effectiveThreads = if (threads > 0) threads else bigCoreCount
            var epUsed = "CPU"

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
                        sessionOptions.setIntraOpNumThreads(1)
                        sessionOptions.setInterOpNumThreads(1)
                        sessionOptions.addConfigEntry("session.intra_op.allow_spinning", "0")
                        try {
                            sessionOptions.addXnnpack(mapOf("intra_op_num_threads" to effectiveThreads.toString()))
                            epUsed = "XNNPACK (Fallback)"
                        } catch (_: Throwable) {
                            val intra = if (threads > 0) threads else (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 8)
                            sessionOptions.setIntraOpNumThreads(intra)
                            epUsed = "CPU (Fallback)"
                        }
                    }
                }
                QuantizationType.INT8, QuantizationType.FP32 -> {
                    sessionOptions.setIntraOpNumThreads(1)
                    sessionOptions.setInterOpNumThreads(1)
                    sessionOptions.addConfigEntry("session.intra_op.allow_spinning", "0")
                    try {
                        sessionOptions.addXnnpack(mapOf("intra_op_num_threads" to effectiveThreads.toString()))
                        epUsed = "XNNPACK"
                        Log.i(TAG, "Configured XNNPACK EP with $effectiveThreads threads for $quantizationType model")
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to add XNNPACK EP: ${e.message}. Falling back to standard CPU thread pool.")
                        val intra = if (threads > 0) threads else (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 8)
                        sessionOptions.setIntraOpNumThreads(intra)
                        epUsed = "CPU"
                    }
                }
            }

            return Pair(sessionOptions, epUsed)
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

            val detectedQuant = detectQuantizationType(prefillPath)
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

            val detectedQuant = quantizationTypeOverride ?: detectQuantizationType(prefillPath)
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

        fun ensureAssetsExtracted(context: Context, precision: String = "INT8"): File {
            val targetBase = File(context.filesDir, "vieneu")
            val normPrecision = when (precision.trim().uppercase()) {
                "INT8" -> "INT8"
                "FP32" -> "FP32"
                else -> "FP32"
            }

            val int8Assets = try { context.assets.list("vieneu/backbone_int8") } catch (_: Exception) { null }
            val assetFolder = if (normPrecision == "INT8" && !int8Assets.isNullOrEmpty()) {
                "vieneu/backbone_int8"
            } else {
                "vieneu/backbone"
            }
            val targetSubdir = if (assetFolder.endsWith("backbone_int8")) "backbone_int8" else "backbone"

            // Extract selected backbone folder
            copyAssetFolder(context, assetFolder, File(targetBase, targetSubdir))

            // Ensure other common folders and root files are extracted
            val children = try { context.assets.list("vieneu") } catch (_: Exception) { null } ?: emptyArray()
            for (child in children) {
                if (child != "backbone" && child != "backbone_int8") {
                    val childPath = "vieneu/$child"
                    val subTarget = File(targetBase, child)
                    val subChildren = try { context.assets.list(childPath) } catch (_: Exception) { null }
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
            val assetManager = context.assets
            val children = assetManager.list(assetPath) ?: return

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
                    val subChildren = assetManager.list(subAssetPath)
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
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}
