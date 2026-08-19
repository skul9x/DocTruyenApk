package com.skul9x.doctruyen.tts

import android.content.Context
import com.skul9x.doctruyen.tts.vieneu.VieNeuTTSManager
import com.skul9x.doctruyen.utils.DebugLogger
import com.skul9x.doctruyen.utils.LogType
import com.skul9x.doctruyen.utils.UserConfig

/**
 * Factory and orchestrator to instantiate appropriate ITTSManager engine based on configuration.
 */
object TTSManagerFactory {

    private const val TAG = "TTSManagerFactory"

    fun create(
        context: Context,
        onStateChange: (ITTSManager.TTSState) -> Unit,
        onProgressChange: ((Int) -> Unit)? = null,
        forceEngineType: ITTSManager.EngineType? = null
    ): ITTSManager {
        val engineType = forceEngineType ?: UserConfig.getTtsEngine(context)
        DebugLogger.log(TAG, "Creating ITTSManager with engine type: $engineType", LogType.INFO)

        return when (engineType) {
            ITTSManager.EngineType.VIENEU -> {
                VieNeuTTSManager(
                    context = context,
                    onStateChange = onStateChange,
                    onProgressChange = onProgressChange
                )
            }
            ITTSManager.EngineType.GOOGLE_TTS -> {
                GoogleTTSManager(
                    context = context,
                    onStateChange = onStateChange,
                    onProgressChange = onProgressChange
                )
            }
        }
    }
}
