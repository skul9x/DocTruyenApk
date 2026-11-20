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

            val lowerPath = modelPath.lowercase()
            return when {
                lowerPath.contains("int8") -> QuantizationType.INT8
                lowerPath.contains("fp16") -> QuantizationType.FP16
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
                            val intra = effectiveThreads
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
                        val intra = effectiveThreads
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
            val normPrecision = when (precision.trim().uppercase()) {
                "INT8" -> "INT8"
                "FP32" -> "FP32"
                else -> "FP32"
            }
            if (normPrecision != detectedQuant.name) {
                Log.w(TAG, "⚠️ Precision mismatch: Requested $precision ($normPrecision) but detected quantization is $detectedQuant on $prefillPath. INT8 model assets may be missing from APK.")
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

        const val PREFS_NAME = "vieneu_assets_prefs"
        const val KEY_LAST_EXTRACTED_VERSION = "last_extracted_version_code"
        const val ASSET_STREAM_BUFFER_SIZE = 64 * 1024

        @androidx.annotation.VisibleForTesting
        var assetListProvider: ((Context, String) -> Array<String>?)? = null

        @androidx.annotation.VisibleForTesting
        var assetOpenProvider: ((Context, String) -> InputStream)? = null

        @androidx.annotation.VisibleForTesting
        var currentVersionCodeProvider: ((Context) -> Long)? = null

        @androidx.annotation.VisibleForTesting
        var sharedPreferencesProvider: ((Context) -> android.content.SharedPreferences?)? = null

        fun getAppVersionCode(context: Context): Long {
            return currentVersionCodeProvider?.invoke(context) ?: try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    pInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode.toLong()
                }
            } catch (_: Throwable) {
                1L
            }
        }

        private fun listAssets(context: Context, path: String): Array<String>? {
            return assetListProvider?.invoke(context, path) ?: try {
                context.assets.list(path)
            } catch (_: Exception) {
                null
            }
        }

        private fun openAssetStream(context: Context, path: String): InputStream {
            return assetOpenProvider?.invoke(context, path) ?: context.assets.open(path)
        }

        fun ensureAssetsExtracted(
            context: Context,
            precision: String = "INT8",
            forceReExtraction: Boolean? = null
        ): File {
            val targetBase = File(context.filesDir, "vieneu")
            val prefs = sharedPreferencesProvider?.invoke(context) ?: try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            } catch (_: Throwable) {
                null
            }

            val currentVersion = getAppVersionCode(context)
            val lastExtracted = prefs?.getLong(KEY_LAST_EXTRACTED_VERSION, -1L) ?: -1L
            val forceOverwrite = forceReExtraction ?: (prefs?.let { lastExtracted < 0L || currentVersion > lastExtracted } ?: false)

            val normPrecision = when (precision.trim().uppercase()) {
                "INT8" -> "INT8"
                "FP32" -> "FP32"
                else -> "FP32"
            }

            val int8Assets = listAssets(context, "vieneu/backbone_int8")
            val assetFolder = if (normPrecision == "INT8") {
                if (!int8Assets.isNullOrEmpty()) {
                    Log.i(TAG, "Found INT8 assets in 'vieneu/backbone_int8' (${int8Assets.size} files). Selecting INT8 backbone.")
                    "vieneu/backbone_int8"
                } else {
                    Log.w(TAG, "⚠️ INT8 precision requested, but 'vieneu/backbone_int8' assets not found or empty. Falling back to FP32 backbone. INT8 model assets may be missing from APK.")
                    "vieneu/backbone"
                }
            } else {
                "vieneu/backbone"
            }
            val targetSubdir = if (assetFolder.endsWith("backbone_int8")) "backbone_int8" else "backbone"

            // Extract selected backbone folder
            copyAssetFolder(context, assetFolder, File(targetBase, targetSubdir), forceOverwrite)

            // Ensure other common folders and root files are extracted
            val children = listAssets(context, "vieneu") ?: emptyArray()
            for (child in children) {
                if (child != "backbone" && child != "backbone_int8") {
                    val childPath = "vieneu/$child"
                    val subTarget = File(targetBase, child)
                    val subChildren = listAssets(context, childPath)
                    if (subChildren != null && subChildren.isNotEmpty()) {
                        copyAssetFolder(context, childPath, subTarget, forceOverwrite)
                    } else {
                        copyAssetFile(context, childPath, subTarget, forceOverwrite)
                    }
                }
            }

            try {
                prefs?.edit()?.putLong(KEY_LAST_EXTRACTED_VERSION, currentVersion)?.apply()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to save last extracted version code: ${e.message}")
            }

            return targetBase
        }

        @androidx.annotation.VisibleForTesting
        fun copyAssetFolder(
            context: Context,
            assetPath: String,
            targetDir: File,
            force: Boolean = false
        ) {
            val children = listAssets(context, assetPath) ?: return

            if (children.isEmpty()) {
                // It's a file
                copyAssetFile(context, assetPath, targetDir, force)
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
                        copyAssetFolder(context, subAssetPath, subTarget, force)
                    } else {
                        copyAssetFile(context, subAssetPath, subTarget, force)
                    }
                }
            }
        }

        @androidx.annotation.VisibleForTesting
        fun copyAssetFile(
            context: Context,
            assetPath: String,
            targetFile: File,
            force: Boolean = false,
            bufferSize: Int = ASSET_STREAM_BUFFER_SIZE
        ) {
            if (!force && targetFile.exists() && targetFile.length() > 0) {
                // Already extracted
                return
            }
            targetFile.parentFile?.mkdirs()
            val buffer = ByteArray(bufferSize)
            openAssetStream(context, assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }
        }
    }
}
