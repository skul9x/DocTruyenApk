package com.skul9x.doctruyen.tts.vieneu

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Manages ONNX Runtime sessions for the 4 VieNeu-TTS models.
 */
class OnnxSessionManager(
    val prefillSession: OrtSession,
    val decodeSession: OrtSession,
    val acousticSession: OrtSession,
    val codecSession: OrtSession,
    val env: OrtEnvironment
) : AutoCloseable {

    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        try { prefillSession.close() } catch (_: Exception) {}
        try { decodeSession.close() } catch (_: Exception) {}
        try { acousticSession.close() } catch (_: Exception) {}
        try { codecSession.close() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "OnnxSessionManager"

        fun create(
            context: Context,
            threads: Int = 0
        ): OnnxSessionManager {
            val env = OrtEnvironment.getEnvironment()
            val intra = if (threads > 0) {
                threads
            } else {
                val cpus = Runtime.getRuntime().availableProcessors()
                (cpus / 2).coerceIn(1, 8)
            }

            val sessionOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(intra)
                setInterOpNumThreads(1)
                addConfigEntry("session.intra_op.allow_spinning", "0")
            }

            val baseDir = ensureAssetsExtracted(context)
            val backboneDir = File(baseDir, "backbone")
            val codecDir = File(baseDir, "codec")

            val prefillPath = File(backboneDir, "vieneu_prefill.onnx").absolutePath
            val decodePath = File(backboneDir, "vieneu_decode_step.onnx").absolutePath
            val acousticPath = File(backboneDir, "vieneu_acoustic_cached.onnx").absolutePath
            val codecPath = File(codecDir, "moss_audio_tokenizer_decode_full.onnx").absolutePath

            Log.i(TAG, "Creating ONNX sessions with $intra intra-op threads...")
            val prefillSession = env.createSession(prefillPath, sessionOptions)
            val decodeSession = env.createSession(decodePath, sessionOptions)
            val acousticSession = env.createSession(acousticPath, sessionOptions)
            val codecSession = env.createSession(codecPath, sessionOptions)

            Log.i(TAG, "All 4 ONNX sessions initialized successfully")
            return OnnxSessionManager(
                prefillSession = prefillSession,
                decodeSession = decodeSession,
                acousticSession = acousticSession,
                codecSession = codecSession,
                env = env
            )
        }

        fun createFromFiles(
            backboneDir: File,
            codecDir: File,
            threads: Int = 0
        ): OnnxSessionManager {
            val env = OrtEnvironment.getEnvironment()
            val intra = if (threads > 0) {
                threads
            } else {
                val cpus = Runtime.getRuntime().availableProcessors()
                (cpus / 2).coerceIn(1, 8)
            }

            val sessionOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(intra)
                setInterOpNumThreads(1)
                addConfigEntry("session.intra_op.allow_spinning", "0")
            }

            val prefillPath = File(backboneDir, "vieneu_prefill.onnx").absolutePath
            val decodePath = File(backboneDir, "vieneu_decode_step.onnx").absolutePath
            val acousticPath = File(backboneDir, "vieneu_acoustic_cached.onnx").absolutePath
            val codecPath = File(codecDir, "moss_audio_tokenizer_decode_full.onnx").absolutePath

            val prefillSession = env.createSession(prefillPath, sessionOptions)
            val decodeSession = env.createSession(decodePath, sessionOptions)
            val acousticSession = env.createSession(acousticPath, sessionOptions)
            val codecSession = env.createSession(codecPath, sessionOptions)

            return OnnxSessionManager(
                prefillSession = prefillSession,
                decodeSession = decodeSession,
                acousticSession = acousticSession,
                codecSession = codecSession,
                env = env
            )
        }

        private fun ensureAssetsExtracted(context: Context): File {
            val targetBase = File(context.filesDir, "vieneu")
            copyAssetFolder(context, "vieneu", targetBase)
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
