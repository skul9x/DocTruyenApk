package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.LinkedHashMap

object SeaG2P {
    private const val MAX_CACHE_SIZE = 512

    // Thread-safe JVM-compatible LRU cache (bounded LinkedHashMap)
    private val phonemeCache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(MAX_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > MAX_CACHE_SIZE
            }
        }
    )

    @Volatile
    private var warmedUp = false

    init {
        try {
            System.loadLibrary("sea_g2p_android")
        } catch (e: UnsatisfiedLinkError) {
            val fallbackPaths = listOf(
                "sea-g2p-android/target/release/libsea_g2p_android.so",
                "../sea-g2p-android/target/release/libsea_g2p_android.so",
                "/home/skul9x/Desktop/Test_code/DocTruyenApk-main/sea-g2p-android/target/release/libsea_g2p_android.so",
                "src/main/jniLibs/x86_64/libsea_g2p_android.so",
                "app/src/main/jniLibs/x86_64/libsea_g2p_android.so",
                "../app/src/main/jniLibs/x86_64/libsea_g2p_android.so",
                "/home/skul9x/Desktop/Test_code/DocTruyenApk-main/app/src/main/jniLibs/x86_64/libsea_g2p_android.so"
            )
            var loaded = false
            for (path in fallbackPaths) {
                val file = java.io.File(path)
                if (file.exists()) {
                    try {
                        System.load(file.absolutePath)
                        loaded = true
                        break
                    } catch (t: Throwable) {
                        // Try next fallback path
                    }
                }
            }
            if (!loaded) {
                // If native library cannot be loaded, native calls will fail when invoked
            }
        }
    }

    private external fun nativeInit(dictPath: String): Boolean
    private external fun nativePhonemize(text: String): String

    fun isWarmedUp(): Boolean = warmedUp

    fun clearCache() {
        phonemeCache.clear()
    }

    fun getCacheSize(): Int = phonemeCache.size

    fun init(context: Context? = null) {
        warmUp(context)
    }

    /**
     * Early warmup routine for G2P dictionary and normalizer regexes.
     * Ensures sea_g2p.bin is extracted to internal storage if context is provided,
     * calls nativeInit with dictionary path, and executes a warmup phonemization.
     */
    @Synchronized
    fun warmUp(context: Context? = null) {
        if (warmedUp) return

        var dictPath: String? = null

        if (context != null) {
            try {
                val targetDir = File(context.filesDir, "vieneu")
                val dictFile = File(targetDir, "sea_g2p.bin")
                if (!dictFile.exists() || dictFile.length() == 0L) {
                    targetDir.mkdirs()
                    context.assets.open("vieneu/sea_g2p.bin").use { input ->
                        FileOutputStream(dictFile).use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                        }
                    }
                }
                if (dictFile.exists()) {
                    dictPath = dictFile.absolutePath
                }
            } catch (e: Throwable) {
                // Asset extraction fallback or file access failure
            }
        }

        // Candidate fallback paths if dictPath not established via Context
        if (dictPath == null || !File(dictPath).exists()) {
            val candidatePaths = listOf(
                "src/main/assets/vieneu/sea_g2p.bin",
                "app/src/main/assets/vieneu/sea_g2p.bin",
                "../app/src/main/assets/vieneu/sea_g2p.bin",
                "vieneu/sea_g2p.bin",
                "sea_g2p.bin",
                "/home/skul9x/Desktop/Test_code/DocTruyenApk-main/app/src/main/assets/vieneu/sea_g2p.bin"
            )
            for (candidate in candidatePaths) {
                val file = File(candidate)
                if (file.exists()) {
                    dictPath = file.absolutePath
                    break
                }
            }
        }

        if (dictPath != null) {
            try {
                nativeInit(dictPath)
            } catch (e: Throwable) {
                // Native init error handling
            }
        }

        // Pre-compile normalizer regexes and warm CPU caches
        try {
            phonemize("Khởi động")
        } catch (e: Throwable) {
            // Warmup phonemize error handling
        }

        warmedUp = true
    }

    /**
     * Convert Vietnamese text to phoneme sequence.
     * Checks LRU cache for cached results before invoking native phonemization.
     * Example: "Xin chào" → "s in1 ch aw2"
     */
    fun phonemize(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return ""
        }

        phonemeCache[trimmed]?.let { return it }

        val phonemes = try {
            nativePhonemize(trimmed)
        } catch (e: UnsatisfiedLinkError) {
            trimmed
        } catch (e: Throwable) {
            trimmed
        }

        if (phonemes.isNotEmpty()) {
            phonemeCache[trimmed] = phonemes
        }

        return phonemes
    }
}

