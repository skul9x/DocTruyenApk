package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import com.skul9x.doctruyen.tts.VoiceFilterCriteria
import com.skul9x.doctruyen.tts.VoiceFilterEngine
import com.skul9x.doctruyen.tts.VoiceGender
import com.skul9x.doctruyen.tts.VoiceInfo
import com.skul9x.doctruyen.tts.VoiceRegion
import com.skul9x.doctruyen.tts.VoiceStyle
import com.skul9x.doctruyen.tts.removeVietnameseDiacritics
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
    val genderDisplay: String
        get() = when (gender.trim().lowercase()) {
            "female", "nữ", "nu" -> "Nữ"
            "male", "nam" -> "Nam"
            else -> {
                if (description.contains("Nữ", ignoreCase = true)) "Nữ"
                else if (description.contains("Nam", ignoreCase = true) && !description.contains("Miền Nam", ignoreCase = true) && !description.contains("· Nam", ignoreCase = true) && !description.contains("- Nam", ignoreCase = true)) "Nam"
                else if (gender.isNotBlank() && gender != "unknown") gender
                else ""
            }
        }

    val regionDisplay: String
        get() {
            val r = region.trim()
            val effectiveRegion = when {
                r.isNotEmpty() && !r.equals("unknown", ignoreCase = true) -> r
                description.contains("Miền Bắc", ignoreCase = true) || description.contains("Bắc", ignoreCase = true) -> "Bắc"
                description.contains("Miền Nam", ignoreCase = true) || description.contains("Nam", ignoreCase = true) -> "Nam"
                description.contains("Miền Trung", ignoreCase = true) || description.contains("Trung", ignoreCase = true) -> "Trung"
                else -> ""
            }

            return when {
                effectiveRegion.isEmpty() -> ""
                effectiveRegion.startsWith("Giọng ", ignoreCase = true) -> effectiveRegion
                effectiveRegion.startsWith("Miền ", ignoreCase = true) -> "Giọng $effectiveRegion"
                effectiveRegion.equals("Bắc", ignoreCase = true) -> "Giọng Miền Bắc"
                effectiveRegion.equals("Nam", ignoreCase = true) -> "Giọng Miền Nam"
                effectiveRegion.equals("Trung", ignoreCase = true) -> "Giọng Miền Trung"
                else -> "Giọng $effectiveRegion"
            }
        }

    val styleDisplay: String
        get() {
            // First check if style or description contains specific style phrase
            val descLower = description.lowercase()
            if (descLower.contains("phong cách tin tức") || descLower.contains("tin tức")) {
                return "Phong cách tin tức"
            }
            if (descLower.contains("phong cách kể chuyện") || descLower.contains("kể chuyện")) {
                return "Phong cách kể chuyện"
            }
            if (descLower.contains("phong cách đọc truyện") || descLower.contains("đọc truyện")) {
                return "Phong cách đọc truyện"
            }
            if (descLower.contains("giọng đọc tự nhiên")) {
                return "Giọng đọc tự nhiên"
            }
            if (descLower.contains("phong cách tự nhiên") || descLower.contains("tự nhiên")) {
                return "Phong cách tự nhiên"
            }
            if (descLower.contains("kiếm hiệp")) {
                return "Phong cách kiếm hiệp"
            }

            val s = style.trim().lowercase()
            return when {
                s in listOf("tin_tuc", "tintuc", "news") -> "Phong cách tin tức"
                s in listOf("doc_truyen", "doctruyen", "story", "kể chuyện", "ke_chuyen") -> "Phong cách kể chuyện"
                s in listOf("tu_nhien", "tunhien", "natural") -> "Phong cách tự nhiên"
                s in listOf("kiem_hiep", "kiemhiep") -> "Phong cách kiếm hiệp"
                s.isNotEmpty() && s != "unknown" -> "Phong cách $s"
                else -> "Phong cách tự nhiên"
            }
        }

    fun getFormattedDescription(): String {
        val parts = mutableListOf<String>()
        if (genderDisplay.isNotEmpty()) parts.add(genderDisplay)
        if (regionDisplay.isNotEmpty()) parts.add(regionDisplay)
        if (styleDisplay.isNotEmpty()) parts.add(styleDisplay)
        return parts.joinToString(" - ")
    }

    fun getFormattedDisplayName(): String {
        val desc = getFormattedDescription()
        return if (desc.isNotEmpty()) {
            "$name - $desc"
        } else {
            name
        }
    }

    val canonicalGender: VoiceGender
        get() {
            val g = VoiceGender.from(gender)
            if (g != VoiceGender.ALL) return g
            val gd = VoiceGender.from(genderDisplay)
            if (gd != VoiceGender.ALL) return gd
            return VoiceGender.from(description)
        }

    val canonicalRegion: VoiceRegion
        get() {
            val r = VoiceRegion.from(region)
            if (r != VoiceRegion.ALL) return r
            val rd = VoiceRegion.from(regionDisplay)
            if (rd != VoiceRegion.ALL) return rd
            return VoiceRegion.from(description)
        }

    val canonicalStyle: VoiceStyle
        get() {
            val s = VoiceStyle.from(style)
            if (s != VoiceStyle.ALL) return s
            val sd = VoiceStyle.from(styleDisplay)
            if (sd != VoiceStyle.ALL) return sd
            return VoiceStyle.from(description)
        }

    fun getBadges(bracketed: Boolean = true): List<String> {
        val list = mutableListOf<String>()
        if (canonicalGender != VoiceGender.ALL) {
            list.add(if (bracketed) "[${canonicalGender.displayName}]" else canonicalGender.displayName)
        }
        if (canonicalRegion != VoiceRegion.ALL) {
            list.add(if (bracketed) "[${canonicalRegion.displayName}]" else canonicalRegion.displayName)
        }
        if (canonicalStyle != VoiceStyle.ALL) {
            list.add(if (bracketed) "[${canonicalStyle.displayName}]" else canonicalStyle.displayName)
        }
        return list
    }

    fun getFormattedBadges(bracketed: Boolean = true): String =
        getBadges(bracketed).joinToString(" ")

    fun matches(criteria: VoiceFilterCriteria): Boolean {
        if (criteria.gender != VoiceGender.ALL && canonicalGender != criteria.gender) return false
        if (criteria.region != VoiceRegion.ALL && canonicalRegion != criteria.region) return false
        if (criteria.style != VoiceStyle.ALL && canonicalStyle != criteria.style) return false
        if (criteria.searchQuery.isNotBlank()) {
            val q = criteria.searchQuery.trim().lowercase().removeVietnameseDiacritics()
            val nameNorm = name.lowercase().removeVietnameseDiacritics()
            val descNorm = description.lowercase().removeVietnameseDiacritics()
            val gNorm = canonicalGender.displayName.lowercase().removeVietnameseDiacritics()
            val rNorm = canonicalRegion.displayName.lowercase().removeVietnameseDiacritics()
            val sNorm = canonicalStyle.displayName.lowercase().removeVietnameseDiacritics()
            val matchesQuery = nameNorm.contains(q) || descNorm.contains(q) ||
                    gNorm.contains(q) || rNorm.contains(q) || sNorm.contains(q)
            if (!matchesQuery) return false
        }
        return true
    }

    fun toVoiceInfo(isDefault: Boolean = false): VoiceInfo {
        return VoiceInfo(
            id = name,
            displayName = getFormattedDisplayName(),
            description = getFormattedDescription(),
            gender = genderDisplay,
            region = regionDisplay,
            isDefault = isDefault,
            style = styleDisplay
        )
    }

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
    private val aliasesMap = ConcurrentHashMap<String, String>()
    var defaultVoiceName: String = "Hải Đăng"

    val DEFAULT_ALIASES: Map<String, String> = mapOf(
        "Minh Quân Pro" to "Hải Đăng",
        "Minh Quân" to "Hải Đăng",
        "Anh Khôi" to "Thiện Minh",
        "Mạnh Dũng" to "Quốc Tuấn"
    )

    val availableVoices: List<String>
        get() = presetsMap.keys.toList()

    fun registerVoice(preset: VoicePreset) {
        presetsMap[preset.name] = preset
    }

    fun resolveVoiceName(name: String?): String {
        if (name.isNullOrBlank()) return defaultVoiceName
        val trimmed = name.trim()
        val target = aliasesMap[trimmed] ?: DEFAULT_ALIASES[trimmed] ?: trimmed
        return if (presetsMap.containsKey(target) || presetsMap.isEmpty()) {
            target
        } else {
            defaultVoiceName
        }
    }

    fun getVoice(name: String): VoicePreset? {
        val resolved = resolveVoiceName(name)
        return presetsMap[resolved] ?: presetsMap[name] ?: presetsMap[defaultVoiceName]
    }

    fun getVoicePresets(): List<VoicePreset> {
        return presetsMap.values.toList()
    }

    fun filterVoices(criteria: VoiceFilterCriteria): List<VoicePreset> {
        return VoiceFilterEngine.filterPresets(getVoicePresets(), criteria)
    }

    fun filterVoices(
        gender: VoiceGender = VoiceGender.ALL,
        region: VoiceRegion = VoiceRegion.ALL,
        style: VoiceStyle = VoiceStyle.ALL,
        searchQuery: String = ""
    ): List<VoicePreset> {
        return filterVoices(VoiceFilterCriteria(gender, region, style, searchQuery))
    }

    fun getFilteredVoiceInfos(criteria: VoiceFilterCriteria): List<VoiceInfo> {
        return filterVoices(criteria).map {
            it.toVoiceInfo(isDefault = it.name == defaultVoiceName)
        }
    }

    fun getFilteredVoiceInfos(
        gender: VoiceGender = VoiceGender.ALL,
        region: VoiceRegion = VoiceRegion.ALL,
        style: VoiceStyle = VoiceStyle.ALL,
        searchQuery: String = ""
    ): List<VoiceInfo> {
        return getFilteredVoiceInfos(VoiceFilterCriteria(gender, region, style, searchQuery))
    }

    fun getVoiceEmbedding(name: String): FloatArray? {
        return getVoice(name)?.speakerEmb
    }

    fun getRefCodes(name: String): Array<IntArray>? {
        return getVoice(name)?.codes
    }

    fun getAliases(): Map<String, String> {
        val combined = HashMap(DEFAULT_ALIASES)
        combined.putAll(aliasesMap)
        return combined
    }

    fun clear() {
        presetsMap.clear()
        aliasesMap.clear()
        defaultVoiceName = "Hải Đăng"
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

            if (vObj.has("aliases")) {
                val aliasesArr = vObj.getJSONArray("aliases")
                for (a in 0 until aliasesArr.length()) {
                    val alias = aliasesArr.getString(a)
                    aliasesMap[alias] = name
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
