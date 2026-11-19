package com.skul9x.doctruyen.tts

import com.skul9x.doctruyen.tts.vieneu.VoicePreset
import com.skul9x.doctruyen.tts.vieneu.VoicePresets
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single comprehensive verification test suite for Phase 02: Voice Metadata Categorization.
 * Covers canonical category enums, Vietnamese diacritics normalization, classification of all 25 VieNeu presets,
 * multi-faceted filtering (Gender, Region, Style), metadata badges, search query resilience, Google TTS integration,
 * thread-safety, and backward compatibility.
 */
class VoiceCategorizationFilterTest {

    @Before
    fun setUp() {
        VoicePresets.clear()
        val file = File("src/main/assets/vieneu/voices_v3_turbo.json")
        if (file.exists()) {
            VoicePresets.loadFromJson(file.readText(Charsets.UTF_8))
        }
    }

    @Test
    fun testVietnameseDiacriticsRemoval() {
        assertEquals("Hai Dang", "Hải Đăng".removeVietnameseDiacritics())
        assertEquals("Truc Ly", "Trúc Ly".removeVietnameseDiacritics())
        assertEquals("Doan Trang", "Đoan Trang".removeVietnameseDiacritics())
        assertEquals("Duc Tri", "Đức Trí".removeVietnameseDiacritics())
        assertEquals("Quynh Anh", "Quỳnh Anh".removeVietnameseDiacritics())
        assertEquals("Thuy Dung", "Thùy Dung".removeVietnameseDiacritics())
        assertEquals("Mien Bac", "Miền Bắc".removeVietnameseDiacritics())
        assertEquals("Mien Trung", "Miền Trung".removeVietnameseDiacritics())
        assertEquals("Mien Nam", "Miền Nam".removeVietnameseDiacritics())
        assertEquals("Ke chuyen", "Kể chuyện".removeVietnameseDiacritics())
        assertEquals("Tu nhien", "Tự nhiên".removeVietnameseDiacritics())
        assertEquals("Tin tuc", "Tin tức".removeVietnameseDiacritics())
        assertEquals("", "".removeVietnameseDiacritics())
    }

    @Test
    fun testCanonicalCategoryEnumsAndParsing() {
        // Gender parsing
        assertEquals(VoiceGender.MALE, VoiceGender.from("Nam"))
        assertEquals(VoiceGender.MALE, VoiceGender.from("nam"))
        assertEquals(VoiceGender.MALE, VoiceGender.from("male"))
        assertEquals(VoiceGender.MALE, VoiceGender.from("Giọng Nam"))
        assertEquals(VoiceGender.FEMALE, VoiceGender.from("Nữ"))
        assertEquals(VoiceGender.FEMALE, VoiceGender.from("nữ"))
        assertEquals(VoiceGender.FEMALE, VoiceGender.from("female"))
        assertEquals(VoiceGender.FEMALE, VoiceGender.from("Giọng Nữ"))
        assertEquals(VoiceGender.ALL, VoiceGender.from("All"))
        assertEquals(VoiceGender.ALL, VoiceGender.from("Tất cả"))
        assertEquals(VoiceGender.ALL, VoiceGender.from(null))
        assertEquals(VoiceGender.ALL, VoiceGender.from(""))
        assertEquals(VoiceGender.ALL, VoiceGender.from("Tiêu chuẩn"))
        // Critical: "Việt Nam" must NOT be parsed as MALE
        assertEquals(VoiceGender.ALL, VoiceGender.from("Việt Nam"))

        // Region parsing
        assertEquals(VoiceRegion.NORTH, VoiceRegion.from("Bắc"))
        assertEquals(VoiceRegion.NORTH, VoiceRegion.from("Miền Bắc"))
        assertEquals(VoiceRegion.NORTH, VoiceRegion.from("Giọng Miền Bắc"))
        assertEquals(VoiceRegion.NORTH, VoiceRegion.from("North"))
        assertEquals(VoiceRegion.CENTRAL, VoiceRegion.from("Trung"))
        assertEquals(VoiceRegion.CENTRAL, VoiceRegion.from("Miền Trung"))
        assertEquals(VoiceRegion.CENTRAL, VoiceRegion.from("Giọng Miền Trung"))
        assertEquals(VoiceRegion.CENTRAL, VoiceRegion.from("Central"))
        assertEquals(VoiceRegion.SOUTH, VoiceRegion.from("Nam"))
        assertEquals(VoiceRegion.SOUTH, VoiceRegion.from("Miền Nam"))
        assertEquals(VoiceRegion.SOUTH, VoiceRegion.from("Giọng Miền Nam"))
        assertEquals(VoiceRegion.SOUTH, VoiceRegion.from("South"))
        assertEquals(VoiceRegion.ALL, VoiceRegion.from("All"))
        assertEquals(VoiceRegion.ALL, VoiceRegion.from("Tất cả"))
        assertEquals(VoiceRegion.ALL, VoiceRegion.from(null))
        assertEquals(VoiceRegion.ALL, VoiceRegion.from(""))
        // Critical: "Việt Nam" must NOT be parsed as SOUTH
        assertEquals(VoiceRegion.ALL, VoiceRegion.from("Việt Nam"))
        assertEquals(VoiceRegion.ALL, VoiceRegion.from("Google TTS (Việt Nam)"))

        // Style parsing
        assertEquals(VoiceStyle.NATURAL, VoiceStyle.from("tu_nhien"))
        assertEquals(VoiceStyle.NATURAL, VoiceStyle.from("Tự nhiên"))
        assertEquals(VoiceStyle.NATURAL, VoiceStyle.from("Giọng đọc tự nhiên"))
        assertEquals(VoiceStyle.NATURAL, VoiceStyle.from("Phong cách tự nhiên"))
        assertEquals(VoiceStyle.NATURAL, VoiceStyle.from("natural"))
        assertEquals(VoiceStyle.STORYTELLING, VoiceStyle.from("doc_truyen"))
        assertEquals(VoiceStyle.STORYTELLING, VoiceStyle.from("Đọc truyện"))
        assertEquals(VoiceStyle.STORYTELLING, VoiceStyle.from("Kể chuyện"))
        assertEquals(VoiceStyle.STORYTELLING, VoiceStyle.from("Phong cách kể chuyện"))
        assertEquals(VoiceStyle.STORYTELLING, VoiceStyle.from("Phong cách đọc truyện"))
        assertEquals(VoiceStyle.STORYTELLING, VoiceStyle.from("Phong cách kiếm hiệp"))
        assertEquals(VoiceStyle.STORYTELLING, VoiceStyle.from("story"))
        assertEquals(VoiceStyle.NEWS, VoiceStyle.from("tin_tuc"))
        assertEquals(VoiceStyle.NEWS, VoiceStyle.from("Tin tức"))
        assertEquals(VoiceStyle.NEWS, VoiceStyle.from("Phong cách tin tức"))
        assertEquals(VoiceStyle.NEWS, VoiceStyle.from("Báo chí"))
        assertEquals(VoiceStyle.NEWS, VoiceStyle.from("news"))
        assertEquals(VoiceStyle.ALL, VoiceStyle.from("All"))
        assertEquals(VoiceStyle.ALL, VoiceStyle.from("Tất cả"))
        assertEquals(VoiceStyle.ALL, VoiceStyle.from(null))
    }

    @Test
    fun testAll25VieNeuPresetsClassification() {
        val presets = VoicePresets.getVoicePresets()
        assertEquals("Total presets loaded from JSON must be 25", 25, presets.size)

        var maleCount = 0
        var femaleCount = 0
        var northCount = 0
        var centralCount = 0
        var southCount = 0
        var naturalCount = 0
        var storytellingCount = 0
        var newsCount = 0

        for (preset in presets) {
            assertNotEquals("Gender must be categorized for ${preset.name}", VoiceGender.ALL, preset.canonicalGender)
            assertNotEquals("Region must be categorized for ${preset.name}", VoiceRegion.ALL, preset.canonicalRegion)
            assertNotEquals("Style must be categorized for ${preset.name}", VoiceStyle.ALL, preset.canonicalStyle)

            when (preset.canonicalGender) {
                VoiceGender.MALE -> maleCount++
                VoiceGender.FEMALE -> femaleCount++
                else -> fail("Unexpected gender for ${preset.name}")
            }

            when (preset.canonicalRegion) {
                VoiceRegion.NORTH -> northCount++
                VoiceRegion.CENTRAL -> centralCount++
                VoiceRegion.SOUTH -> southCount++
                else -> fail("Unexpected region for ${preset.name}")
            }

            when (preset.canonicalStyle) {
                VoiceStyle.NATURAL -> naturalCount++
                VoiceStyle.STORYTELLING -> storytellingCount++
                VoiceStyle.NEWS -> newsCount++
                else -> fail("Unexpected style for ${preset.name}")
            }
        }

        assertEquals("Must have 14 Male presets", 14, maleCount)
        assertEquals("Must have 11 Female presets", 11, femaleCount)
        assertEquals("Must have 15 North presets", 15, northCount)
        assertEquals("Must have 2 Central presets", 2, centralCount)
        assertEquals("Must have 8 South presets", 8, southCount)
        assertEquals("Must have 11 Natural style presets", 11, naturalCount)
        assertEquals("Must have 10 Storytelling style presets", 10, storytellingCount)
        assertEquals("Must have 4 News style presets", 4, newsCount)
    }

    @Test
    fun testSingleFacetFiltering() {
        // Gender filtering
        val maleOnly = VoicePresets.filterVoices(VoiceFilterCriteria(gender = VoiceGender.MALE))
        assertEquals(14, maleOnly.size)
        assertTrue(maleOnly.all { it.canonicalGender == VoiceGender.MALE })

        val femaleOnly = VoicePresets.filterVoices(VoiceFilterCriteria(gender = VoiceGender.FEMALE))
        assertEquals(11, femaleOnly.size)
        assertTrue(femaleOnly.all { it.canonicalGender == VoiceGender.FEMALE })

        // Region filtering
        val northOnly = VoicePresets.filterVoices(VoiceFilterCriteria(region = VoiceRegion.NORTH))
        assertEquals(15, northOnly.size)
        assertTrue(northOnly.all { it.canonicalRegion == VoiceRegion.NORTH })

        val centralOnly = VoicePresets.filterVoices(VoiceFilterCriteria(region = VoiceRegion.CENTRAL))
        assertEquals(2, centralOnly.size)
        val centralNames = centralOnly.map { it.name }.toSet()
        assertTrue(centralNames.contains("Quang Sơn"))
        assertTrue(centralNames.contains("Ngọc Trân"))

        val southOnly = VoicePresets.filterVoices(VoiceFilterCriteria(region = VoiceRegion.SOUTH))
        assertEquals(8, southOnly.size)
        assertTrue(southOnly.all { it.canonicalRegion == VoiceRegion.SOUTH })

        // Style filtering
        val naturalOnly = VoicePresets.filterVoices(VoiceFilterCriteria(style = VoiceStyle.NATURAL))
        assertEquals(11, naturalOnly.size)
        assertTrue(naturalOnly.all { it.canonicalStyle == VoiceStyle.NATURAL })

        val storytellingOnly = VoicePresets.filterVoices(VoiceFilterCriteria(style = VoiceStyle.STORYTELLING))
        assertEquals(10, storytellingOnly.size)
        assertTrue(storytellingOnly.all { it.canonicalStyle == VoiceStyle.STORYTELLING })

        val newsOnly = VoicePresets.filterVoices(VoiceFilterCriteria(style = VoiceStyle.NEWS))
        assertEquals(4, newsOnly.size)
        val newsNames = newsOnly.map { it.name }.toSet()
        assertEquals(setOf("Mai Anh", "Thùy Dung", "Minh Đức", "Minh Triết"), newsNames)

        // All criteria returns 25
        val allVoices = VoicePresets.filterVoices(VoiceFilterCriteria.ALL)
        assertEquals(25, allVoices.size)
    }

    @Test
    fun testMultiFacetedCombinedFilteringIntersections() {
        // Male + South + Storytelling -> Thái Sơn, Đức Trí (2 voices)
        val maleSouthStory = VoicePresets.filterVoices(
            VoiceFilterCriteria(
                gender = VoiceGender.MALE,
                region = VoiceRegion.SOUTH,
                style = VoiceStyle.STORYTELLING
            )
        )
        assertEquals(2, maleSouthStory.size)
        val maleSouthStoryNames = maleSouthStory.map { it.name }.toSet()
        assertEquals(setOf("Thái Sơn", "Đức Trí"), maleSouthStoryNames)

        // Female + South + Storytelling -> Thục Đoan, Mỹ Duyên, Kim Thanh (3 voices)
        val femaleSouthStory = VoicePresets.filterVoices(
            VoiceFilterCriteria(
                gender = VoiceGender.FEMALE,
                region = VoiceRegion.SOUTH,
                style = VoiceStyle.STORYTELLING
            )
        )
        assertEquals(3, femaleSouthStory.size)
        val femaleSouthStoryNames = femaleSouthStory.map { it.name }.toSet()
        assertEquals(setOf("Thục Đoan", "Mỹ Duyên", "Kim Thanh"), femaleSouthStoryNames)

        // Male + Central + Natural -> Quang Sơn (1 voice)
        val maleCentralNatural = VoicePresets.filterVoices(
            VoiceFilterCriteria(
                gender = VoiceGender.MALE,
                region = VoiceRegion.CENTRAL,
                style = VoiceStyle.NATURAL
            )
        )
        assertEquals(1, maleCentralNatural.size)
        assertEquals("Quang Sơn", maleCentralNatural[0].name)

        // Female + Central + Natural -> Ngọc Trân (1 voice)
        val femaleCentralNatural = VoicePresets.filterVoices(
            VoiceFilterCriteria(
                gender = VoiceGender.FEMALE,
                region = VoiceRegion.CENTRAL,
                style = VoiceStyle.NATURAL
            )
        )
        assertEquals(1, femaleCentralNatural.size)
        assertEquals("Ngọc Trân", femaleCentralNatural[0].name)

        // Male + North + News -> Minh Đức (1 voice)
        val maleNorthNews = VoicePresets.filterVoices(
            gender = VoiceGender.MALE,
            region = VoiceRegion.NORTH,
            style = VoiceStyle.NEWS
        )
        assertEquals(1, maleNorthNews.size)
        assertEquals("Minh Đức", maleNorthNews[0].name)

        // Female + North + News -> Mai Anh (1 voice)
        val femaleNorthNews = VoicePresets.filterVoices(
            gender = VoiceGender.FEMALE,
            region = VoiceRegion.NORTH,
            style = VoiceStyle.NEWS
        )
        assertEquals(1, femaleNorthNews.size)
        assertEquals("Mai Anh", femaleNorthNews[0].name)

        // Female + South + News -> Thùy Dung (1 voice)
        val femaleSouthNews = VoicePresets.filterVoices(
            gender = VoiceGender.FEMALE,
            region = VoiceRegion.SOUTH,
            style = VoiceStyle.NEWS
        )
        assertEquals(1, femaleSouthNews.size)
        assertEquals("Thùy Dung", femaleSouthNews[0].name)

        // Male + South + News -> Minh Triết (1 voice)
        val maleSouthNews = VoicePresets.filterVoices(
            gender = VoiceGender.MALE,
            region = VoiceRegion.SOUTH,
            style = VoiceStyle.NEWS
        )
        assertEquals(1, maleSouthNews.size)
        assertEquals("Minh Triết", maleSouthNews[0].name)

        // Empty intersection: Central + News -> 0
        val centralNews = VoicePresets.filterVoices(
            gender = VoiceGender.ALL,
            region = VoiceRegion.CENTRAL,
            style = VoiceStyle.NEWS
        )
        assertEquals(0, centralNews.size)
    }

    @Test
    fun testBadgeFormattingAndExtraction() {
        val thaiSon = VoicePresets.getVoice("Thái Sơn")!!
        val thaiSonBadges = thaiSon.getBadges()
        assertEquals(listOf("[Nam]", "[Miền Nam]", "[Kể chuyện]"), thaiSonBadges)
        assertEquals("[Nam] [Miền Nam] [Kể chuyện]", thaiSon.getFormattedBadges())

        val thaiSonRawBadges = thaiSon.getBadges(bracketed = false)
        assertEquals(listOf("Nam", "Miền Nam", "Kể chuyện"), thaiSonRawBadges)

        val thuyDung = VoicePresets.getVoice("Thùy Dung")!!
        assertEquals(listOf("[Nữ]", "[Miền Nam]", "[Tin tức]"), thuyDung.getBadges())
        assertEquals("[Nữ] [Miền Nam] [Tin tức]", thuyDung.getFormattedBadges())

        val haiDang = VoicePresets.getVoice("Hải Đăng")!!
        assertEquals(listOf("[Nam]", "[Miền Bắc]", "[Tự nhiên]"), haiDang.getBadges())
        assertEquals("[Nam] [Miền Bắc] [Tự nhiên]", haiDang.getFormattedBadges())

        val quangSon = VoicePresets.getVoice("Quang Sơn")!!
        assertEquals(listOf("[Nam]", "[Miền Trung]", "[Tự nhiên]"), quangSon.getBadges())

        // VoiceInfo badge consistency
        val voiceInfo = thaiSon.toVoiceInfo()
        assertEquals(listOf("[Nam]", "[Miền Nam]", "[Kể chuyện]"), voiceInfo.getBadges())
        assertEquals("[Nam] [Miền Nam] [Kể chuyện]", voiceInfo.getFormattedBadges())
    }

    @Test
    fun testDiacriticsResilientSearchQuery() {
        // Unaccented lowercase search
        val haiDangQuery = VoicePresets.filterVoices(
            VoiceFilterCriteria(searchQuery = "hai dang")
        )
        assertEquals(1, haiDangQuery.size)
        assertEquals("Hải Đăng", haiDangQuery[0].name)

        // Uppercase accented search with extra whitespace
        val trucLyQuery = VoicePresets.filterVoices(
            VoiceFilterCriteria(searchQuery = "  TRÚC LY  ")
        )
        assertEquals(1, trucLyQuery.size)
        assertEquals("Trúc Ly", trucLyQuery[0].name)

        // Substring prefix search "thien" -> "Thiện Minh" and "Thiền Tâm Đức"
        val thienQuery = VoicePresets.filterVoices(
            VoiceFilterCriteria(searchQuery = "thien")
        )
        assertEquals(2, thienQuery.size)
        val thienNames = thienQuery.map { it.name }.toSet()
        assertTrue(thienNames.contains("Thiện Minh"))
        assertTrue(thienNames.contains("Thiền Tâm Đức"))

        // Search combined with facet filter: female + query "duyen" -> Mỹ Duyên
        val combinedQuery = VoicePresets.filterVoices(
            VoiceFilterCriteria(
                gender = VoiceGender.FEMALE,
                searchQuery = "duyen"
            )
        )
        assertEquals(1, combinedQuery.size)
        assertEquals("Mỹ Duyên", combinedQuery[0].name)

        // Search query not matching any voice
        val nonExistent = VoicePresets.filterVoices(
            VoiceFilterCriteria(searchQuery = "non_existent_xyz_123")
        )
        assertEquals(0, nonExistent.size)
    }

    @Test
    fun testGoogleTTSVoiceInfoCategorizationAndFiltering() {
        val googleVoices = listOf(
            VoiceInfo(
                id = "vi-vn-x-vic-network",
                displayName = "Google Nữ (Online)",
                description = "Google TTS · Nữ · Chất lượng cao (Online)",
                gender = "Nữ",
                region = "Việt Nam",
                isDefault = true,
                style = "Tự nhiên"
            ),
            VoiceInfo(
                id = "vi-vn-x-vif-local",
                displayName = "Google Nam (Offline)",
                description = "Google TTS · Nam · Offline",
                gender = "Nam",
                region = "Việt Nam",
                isDefault = false,
                style = "Tự nhiên"
            ),
            VoiceInfo(
                id = "vi-vn-default",
                displayName = "Google Tiếng Việt (Mặc định)",
                description = "Giọng đọc hệ thống Tiếng Việt",
                gender = "Tiêu chuẩn",
                region = "Việt Nam",
                isDefault = false,
                style = "Tự nhiên"
            )
        )

        // Test canonical mapping
        assertEquals(VoiceGender.FEMALE, googleVoices[0].canonicalGender)
        assertEquals(VoiceGender.MALE, googleVoices[1].canonicalGender)
        assertEquals(VoiceGender.ALL, googleVoices[2].canonicalGender)

        // Region for "Việt Nam" is national (ALL), not misclassified as South
        assertEquals(VoiceRegion.ALL, googleVoices[0].canonicalRegion)
        assertEquals(VoiceRegion.ALL, googleVoices[1].canonicalRegion)

        // Test badges
        assertTrue(googleVoices[0].getBadges().contains("[Nữ]"))
        assertTrue(googleVoices[1].getBadges().contains("[Nam]"))

        // Filter Google voices by Gender
        val femaleFiltered = VoiceFilterEngine.filterVoiceInfos(
            googleVoices,
            VoiceFilterCriteria(gender = VoiceGender.FEMALE)
        )
        assertEquals(1, femaleFiltered.size)
        assertEquals("vi-vn-x-vic-network", femaleFiltered[0].id)

        val maleFiltered = VoiceFilterEngine.filterVoiceInfos(
            googleVoices,
            VoiceFilterCriteria(gender = VoiceGender.MALE)
        )
        assertEquals(1, maleFiltered.size)
        assertEquals("vi-vn-x-vif-local", maleFiltered[0].id)

        val allFiltered = VoiceFilterEngine.filterVoiceInfos(
            googleVoices,
            VoiceFilterCriteria.ALL
        )
        assertEquals(3, allFiltered.size)

        // Search query filter on Google voices
        val offlineFiltered = VoiceFilterEngine.filterVoiceInfos(
            googleVoices,
            VoiceFilterCriteria(searchQuery = "offline")
        )
        assertEquals(1, offlineFiltered.size)
        assertEquals("vi-vn-x-vif-local", offlineFiltered[0].id)
    }

    @Test
    fun testResetFilterRestoresCompleteList() {
        var criteria = VoiceFilterCriteria(
            gender = VoiceGender.MALE,
            region = VoiceRegion.SOUTH,
            style = VoiceStyle.STORYTELLING,
            searchQuery = "thai son"
        )
        assertFalse(criteria.isDefault)

        // Reset criteria
        criteria = criteria.reset()
        assertTrue(criteria.isDefault)
        assertEquals(VoiceGender.ALL, criteria.gender)
        assertEquals(VoiceRegion.ALL, criteria.region)
        assertEquals(VoiceStyle.ALL, criteria.style)
        assertEquals("", criteria.searchQuery)

        val restored = VoicePresets.filterVoices(criteria)
        assertEquals("Reset criteria must return all 25 presets", 25, restored.size)
    }

    @Test
    fun testConcurrencyAndInstantPerformance() {
        val presets = VoicePresets.getVoicePresets()
        val numThreads = 8
        val opsPerThread = 500
        val executor = Executors.newFixedThreadPool(numThreads)
        val latch = CountDownLatch(numThreads)
        val successCount = AtomicInteger(0)

        val startTime = System.nanoTime()

        for (t in 0 until numThreads) {
            executor.submit {
                try {
                    for (i in 0 until opsPerThread) {
                        val criteria = when (i % 4) {
                            0 -> VoiceFilterCriteria(gender = VoiceGender.MALE)
                            1 -> VoiceFilterCriteria(region = VoiceRegion.SOUTH)
                            2 -> VoiceFilterCriteria(style = VoiceStyle.STORYTELLING)
                            else -> VoiceFilterCriteria(gender = VoiceGender.FEMALE, searchQuery = "truc")
                        }
                        val result = VoiceFilterEngine.filterPresets(presets, criteria)
                        if (result.isNotEmpty()) {
                            successCount.incrementAndGet()
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0
        val totalOps = numThreads * opsPerThread
        println("Executed $totalOps filter operations concurrently across $numThreads threads in ${elapsedMs}ms (${elapsedMs / totalOps}ms/op)")

        assertEquals(totalOps, successCount.get())
        assertTrue("Average filter latency should be well under 10ms", elapsedMs / totalOps < 1.0)
    }

    @Test
    fun testBackwardCompatibility() {
        // availableVoices list
        val available = VoicePresets.availableVoices
        assertEquals(25, available.size)
        assertTrue(available.contains("Hải Đăng"))
        assertTrue(available.contains("Trúc Ly"))

        // getVoice resolution & aliases
        assertEquals("Hải Đăng", VoicePresets.resolveVoiceName("Minh Quân"))
        assertEquals("Hải Đăng", VoicePresets.resolveVoiceName("Minh Quân Pro"))
        assertEquals("Thiện Minh", VoicePresets.resolveVoiceName("Anh Khôi"))
        assertEquals("Quốc Tuấn", VoicePresets.resolveVoiceName("Mạnh Dũng"))
        assertEquals("Hải Đăng", VoicePresets.resolveVoiceName(null))
        assertEquals("Hải Đăng", VoicePresets.resolveVoiceName(""))

        val trucLy = VoicePresets.getVoice("Trúc Ly")
        assertNotNull(trucLy)
        assertEquals("Nữ", trucLy!!.genderDisplay)
        assertEquals("Giọng Miền Bắc", trucLy.regionDisplay)
        assertEquals("Phong cách tự nhiên", trucLy.styleDisplay)
        assertEquals("Nữ - Giọng Miền Bắc - Phong cách tự nhiên", trucLy.getFormattedDescription())
        assertEquals("Trúc Ly - Nữ - Giọng Miền Bắc - Phong cách tự nhiên", trucLy.getFormattedDisplayName())
    }
}
