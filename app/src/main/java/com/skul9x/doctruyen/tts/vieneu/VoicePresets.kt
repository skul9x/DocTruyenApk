package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Preset voice definitions and loader for VieNeu-TTS.
 */
data class VoicePreset(
    val name: String,
    val description: String = "",
    val gender: String = "unknown",
    val region: String = "Bắc",
    val style: String = "tu_nhien",
    val speakerEmb: FloatArray,
    val codes: Array<IntArray>? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VoicePreset
        if (name != other.name) return false
        if (!speakerEmb.contentEquals(other.speakerEmb)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + speakerEmb.contentHashCode()
        return result
    }
}

object VoicePresets {
    private val presetsMap = ConcurrentHashMap<String, VoicePreset>()
    var defaultVoiceName: String = "Trúc Ly"

    val availableVoices: List<String>
        get() = presetsMap.keys.toList()

    fun registerVoice(preset: VoicePreset) {
        presetsMap[preset.name] = preset
    }

    fun getVoice(name: String): VoicePreset? {
        return presetsMap[name] ?: presetsMap[defaultVoiceName]
    }

    fun getVoiceEmbedding(name: String): FloatArray? {
        return getVoice(name)?.speakerEmb
    }

    fun getRefCodes(name: String): Array<IntArray>? {
        return getVoice(name)?.codes
    }

    fun loadFromContext(context: Context, assetPath: String = "vieneu/voices_v3_turbo.json"): Boolean {
        return try {
            context.assets.open(assetPath).use { loadFromInputStream(it) }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun loadFromInputStream(inputStream: InputStream) {
        val jsonStr = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        loadFromJson(jsonStr)
    }

    fun loadFromJson(jsonStr: String) {
        val root = JSONObject(jsonStr)
        if (root.has("default_voice")) {
            defaultVoiceName = root.getString("default_voice")
        }
        val presetsObj = root.optJSONObject("presets") ?: return
        val keys = presetsObj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val vObj = presetsObj.getJSONObject(name)
            val desc = vObj.optString("description", "")
            val gender = vObj.optString("gender", "")
            val region = vObj.optString("region", "")
            val style = vObj.optString("style", "tu_nhien")

            val embArr = vObj.getJSONArray("speaker_emb")
            val emb = FloatArray(embArr.length()) { i -> embArr.getDouble(i).toFloat() }

            var codes: Array<IntArray>? = null
            if (vObj.has("codes")) {
                val codesArr = vObj.getJSONArray("codes")
                val nFrames = codesArr.length()
                if (nFrames > 0) {
                    codes = Array(nFrames) { f ->
                        val frameArr = codesArr.getJSONArray(f)
                        IntArray(frameArr.length()) { c -> frameArr.getInt(c) }
                    }
                }
            }

            registerVoice(
                VoicePreset(
                    name = name,
                    description = desc,
                    gender = gender,
                    region = region,
                    style = style,
                    speakerEmb = emb,
                    codes = codes
                )
            )
        }
    }
}
