package com.skul9x.doctruyen.tts

import com.skul9x.doctruyen.tts.vieneu.VoicePreset
import java.text.Normalizer

private val COMBINING_DIACRITICS_REGEX = "\\p{Mn}+".toRegex()

/**
 * Removes Vietnamese accents/diacritics and decomposes special letters like đ/Đ -> d/D.
 */
fun String.removeVietnameseDiacritics(): String {
    if (this.isEmpty()) return this
    val normalized = Normalizer.normalize(this, Normalizer.Form.NFD)
    val stripped = COMBINING_DIACRITICS_REGEX.replace(normalized, "")
    return stripped
        .replace('đ', 'd')
        .replace('Đ', 'D')
}

/**
 * Canonical gender categories for voice filtering.
 */
enum class VoiceGender(val displayName: String) {
    ALL("Tất cả"),
    MALE("Nam"),
    FEMALE("Nữ");

    companion object {
        fun from(value: String?): VoiceGender {
            if (value.isNullOrBlank()) return ALL
            val clean = value.trim().lowercase()
            val norm = clean.removeVietnameseDiacritics()

            if (norm.isEmpty() || norm == "all" || norm == "tat ca" || norm == "tat_ca") return ALL

            if (norm == "female" || norm == "nu" || norm.contains("female") ||
                norm.contains("giong nu") || norm.contains("nu ·") || norm.contains("nu -") ||
                clean.contains("nữ")
            ) {
                return FEMALE
            }

            val withoutCountry = norm.replace("viet nam", "").replace("vietnam", "").trim()
            if (withoutCountry == "male" || withoutCountry == "nam" ||
                withoutCountry.contains("male") || withoutCountry.contains("giong nam") ||
                withoutCountry.startsWith("nam ·") || withoutCountry.startsWith("nam -") || withoutCountry.startsWith("nam ") ||
                withoutCountry.contains("· nam") || withoutCountry.contains("- nam") ||
                (clean.contains("nam") && !clean.contains("miền nam", ignoreCase = true) && !clean.contains("việt nam", ignoreCase = true))
            ) {
                return MALE
            }

            return ALL
        }

        fun fromString(value: String?): VoiceGender = from(value)
    }
}

/**
 * Canonical geographical region categories for voice filtering.
 */
enum class VoiceRegion(val displayName: String, val shortName: String) {
    ALL("Tất cả", "Tất cả"),
    NORTH("Miền Bắc", "Bắc"),
    CENTRAL("Miền Trung", "Trung"),
    SOUTH("Miền Nam", "Nam");

    companion object {
        fun from(value: String?): VoiceRegion {
            if (value.isNullOrBlank()) return ALL
            val clean = value.trim().lowercase()
            val norm = clean.removeVietnameseDiacritics()

            if (norm.isEmpty() || norm == "all" || norm == "tat ca" || norm == "tat_ca") return ALL

            if (norm.contains("bac") || norm.contains("north")) return NORTH
            if (norm.contains("trung") || norm.contains("central")) return CENTRAL

            val withoutCountry = norm.replace("viet nam", "").replace("vietnam", "").trim()
            if (withoutCountry == "nam" || withoutCountry.startsWith("nam ") || withoutCountry.endsWith(" nam") ||
                withoutCountry.contains("mien nam") || withoutCountry.contains("giong nam") ||
                withoutCountry.contains("south") || withoutCountry.contains("sai gon") || withoutCountry.contains("tphcm") ||
                clean.contains("miền nam", ignoreCase = true)
            ) {
                return SOUTH
            }

            return ALL
        }

        fun fromString(value: String?): VoiceRegion = from(value)
    }
}

/**
 * Canonical reading styles for voice filtering.
 */
enum class VoiceStyle(val displayName: String) {
    ALL("Tất cả"),
    STORYTELLING("Kể chuyện"), // Covers Kể chuyện / Đọc truyện / Kiếm hiệp
    NATURAL("Tự nhiên"),       // Covers Tự nhiên / Giọng đọc tự nhiên
    NEWS("Tin tức");          // Covers Tin tức / Báo chí

    companion object {
        fun from(value: String?): VoiceStyle {
            if (value.isNullOrBlank()) return ALL
            val clean = value.trim().lowercase().replace("_", " ")
            val norm = clean.removeVietnameseDiacritics()

            if (norm.isEmpty() || norm == "all" || norm == "tat ca" || norm == "tat_ca") return ALL

            return when {
                norm.contains("ke chuyen") || norm.contains("doc truyen") || norm.contains("story") ||
                norm.contains("kiem hiep") || norm.contains("truyen") -> STORYTELLING

                norm.contains("tin tuc") || norm.contains("bao chi") || norm.contains("news") -> NEWS

                norm.contains("tu nhien") || norm.contains("natural") -> NATURAL

                else -> ALL
            }
        }

        fun fromString(value: String?): VoiceStyle = from(value)
    }
}

/**
 * Filter criteria holding active selection across facets.
 */
data class VoiceFilterCriteria(
    val gender: VoiceGender = VoiceGender.ALL,
    val region: VoiceRegion = VoiceRegion.ALL,
    val style: VoiceStyle = VoiceStyle.ALL,
    val searchQuery: String = ""
) {
    val isDefault: Boolean
        get() = gender == VoiceGender.ALL && region == VoiceRegion.ALL && style == VoiceStyle.ALL && searchQuery.isBlank()

    fun reset(): VoiceFilterCriteria = ALL

    companion object {
        val ALL = VoiceFilterCriteria()

        fun from(
            gender: String? = null,
            region: String? = null,
            style: String? = null,
            searchQuery: String = ""
        ): VoiceFilterCriteria {
            return VoiceFilterCriteria(
                gender = VoiceGender.from(gender),
                region = VoiceRegion.from(region),
                style = VoiceStyle.from(style),
                searchQuery = searchQuery
            )
        }

        fun fromString(
            gender: String? = null,
            region: String? = null,
            style: String? = null,
            searchQuery: String = ""
        ): VoiceFilterCriteria = from(gender, region, style, searchQuery)
    }
}

/**
 * Engine providing query and filtering capabilities across voice collections.
 */
object VoiceFilterEngine {
    fun filterPresets(presets: List<VoicePreset>, criteria: VoiceFilterCriteria): List<VoicePreset> {
        if (criteria.isDefault) return presets
        return presets.filter { it.matches(criteria) }
    }

    fun filterVoiceInfos(voices: List<VoiceInfo>, criteria: VoiceFilterCriteria): List<VoiceInfo> {
        if (criteria.isDefault) return voices
        return voices.filter { it.matches(criteria) }
    }

    @JvmName("filterPresetsList")
    fun filter(presets: List<VoicePreset>, criteria: VoiceFilterCriteria): List<VoicePreset> =
        filterPresets(presets, criteria)

    @JvmName("filterVoiceInfosList")
    fun filter(voices: List<VoiceInfo>, criteria: VoiceFilterCriteria): List<VoiceInfo> =
        filterVoiceInfos(voices, criteria)

    fun filter(
        presets: List<VoicePreset>,
        gender: VoiceGender = VoiceGender.ALL,
        region: VoiceRegion = VoiceRegion.ALL,
        style: VoiceStyle = VoiceStyle.ALL,
        searchQuery: String = ""
    ): List<VoicePreset> = filterPresets(presets, VoiceFilterCriteria(gender, region, style, searchQuery))
}
