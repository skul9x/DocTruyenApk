package com.skul9x.doctruyen.tts.vieneu

object SeaG2P {
    init {
        try {
            System.loadLibrary("sea_g2p_android")
        } catch (e: UnsatisfiedLinkError) {
            val fallbackPaths = listOf(
                "sea-g2p-android/target/release/libsea_g2p_android.so",
                "../sea-g2p-android/target/release/libsea_g2p_android.so",
                "/home/skul9x/Desktop/Test_code/DocTruyenApk-main/sea-g2p-android/target/release/libsea_g2p_android.so"
            )
            var loaded = false
            for (path in fallbackPaths) {
                val file = java.io.File(path)
                if (file.exists()) {
                    System.load(file.absolutePath)
                    loaded = true
                    break
                }
            }
            if (!loaded) {
                throw e
            }
        }
    }

    fun init(context: android.content.Context? = null) {
        // Initialization handled in init block
    }

    private external fun nativePhonemize(text: String): String

    /**
     * Convert Vietnamese text to phoneme sequence.
     * Example: "Xin chào" → "s in1 ch aw2"
     */
    fun phonemize(text: String): String {
        return nativePhonemize(text)
    }
}
