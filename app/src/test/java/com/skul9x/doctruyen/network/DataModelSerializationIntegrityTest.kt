package com.skul9x.doctruyen.network

import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Comprehensive verification for Phase 02:
 * 1. Reflection verification that all DTO fields have explicit @SerializedName
 * 2. Reflection verification that all DTO classes have @Keep annotation
 * 3. JSON deserialization simulation of realistic backend responses (api.php)
 * 4. Safe default initializers when keys are omitted or missing
 * 5. Full round-trip serialization preserving exact JSON keys
 * 6. UTF-8 Vietnamese character integrity verification
 */
class DataModelSerializationIntegrityTest {

    private val gson: Gson = GsonBuilder().setLenient().create()

    private val dtoClasses = listOf(
        Story::class.java,
        StoryListResponse::class.java,
        StoryDetailResponse::class.java,
        RandomStoryResponse::class.java
    )

    @Test
    fun testAllModelFieldsHaveSerializedName() {
        val expectedFieldMappings = mapOf(
            Story::class.java to mapOf(
                "id" to "id",
                "title" to "title",
                "image" to "image",
                "content" to "content",
                "contentText" to "content_text"
            ),
            StoryListResponse::class.java to mapOf(
                "status" to "status",
                "data" to "data",
                "total" to "total",
                "page" to "page",
                "totalPages" to "total_pages",
                "message" to "message"
            ),
            StoryDetailResponse::class.java to mapOf(
                "status" to "status",
                "data" to "data",
                "message" to "message"
            ),
            RandomStoryResponse::class.java to mapOf(
                "status" to "status",
                "data" to "data",
                "message" to "message"
            )
        )

        for (clazz in dtoClasses) {
            val expectedMappings = expectedFieldMappings[clazz]
                ?: throw AssertionError("No expected mapping defined for ${clazz.simpleName}")

            val declaredFields = clazz.declaredFields.filter {
                !it.isSynthetic && !Modifier.isStatic(it.modifiers)
            }

            assertTrue(
                "Class ${clazz.simpleName} must have declared non-static fields",
                declaredFields.isNotEmpty()
            )

            for (field in declaredFields) {
                val serializedName = field.getAnnotation(SerializedName::class.java)
                assertNotNull(
                    "Field '${field.name}' in class ${clazz.simpleName} MUST have @SerializedName annotation",
                    serializedName
                )

                val expectedJsonName = expectedMappings[field.name]
                assertNotNull(
                    "Field '${field.name}' in class ${clazz.simpleName} is not recognized in expected mappings",
                    expectedJsonName
                )

                assertEquals(
                    "SerializedName for '${field.name}' in class ${clazz.simpleName} must match expected key",
                    expectedJsonName,
                    serializedName!!.value
                )
            }
        }
    }

    @Test
    fun testAllModelsHaveKeepAnnotation() {
        for (clazz in dtoClasses) {
            // androidx.annotation.Keep has RetentionPolicy.CLASS (BINARY retention),
            // which means R8 and compilers read it from RuntimeInvisibleAnnotations in the .class file.
            val hasRuntimeAnnotation = clazz.isAnnotationPresent(Keep::class.java)
            val hasBytecodeAnnotation = checkBytecodeHasKeep(clazz)

            assertTrue(
                "DTO class ${clazz.name} must have @androidx.annotation.Keep annotation in bytecode for R8 preservation",
                hasRuntimeAnnotation || hasBytecodeAnnotation
            )
        }
    }

    private fun checkBytecodeHasKeep(clazz: Class<*>): Boolean {
        val resourcePath = clazz.name.replace('.', '/') + ".class"
        val bytes = clazz.classLoader?.getResourceAsStream(resourcePath)?.readBytes()
            ?: clazz.getResourceAsStream("/$resourcePath")?.readBytes()
            ?: clazz.getResourceAsStream("${clazz.simpleName}.class")?.readBytes()
            ?: return false

        val keepDescriptor = "Landroidx/annotation/Keep;".toByteArray(Charsets.UTF_8)
        val keepClass = "androidx/annotation/Keep".toByteArray(Charsets.UTF_8)
        return containsBytes(bytes, keepDescriptor) || containsBytes(bytes, keepClass)
    }

    private fun containsBytes(source: ByteArray, target: ByteArray): Boolean {
        if (target.isEmpty() || source.size < target.size) return false
        for (i in 0..(source.size - target.size)) {
            var found = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    found = false
                    break
                }
            }
            if (found) return true
        }
        return false
    }

    @Test
    fun testJsonDeserializationWithObfuscatedSimulation() {
        // 1. StoryListResponse deserialization
        val storyListJson = """
            {
                "status": "success",
                "data": [
                    {
                        "id": 101,
                        "title": "Truyện Kiều (Đoạn Trường Tân Thanh)",
                        "image": "https://skul9x.free.nf/truyen/images/kieu.jpg",
                        "content": "<p>Trăm năm trong cõi người ta, chữ tài chữ mệnh khéo là ghét nhau.</p>",
                        "content_text": "Trăm năm trong cõi người ta, chữ tài chữ mệnh khéo là ghét nhau."
                    },
                    {
                        "id": 102,
                        "title": "Lục Vân Tiên",
                        "image": null,
                        "content": "<p>Trước đèn xem chuyện Tây Minh, bõ công đọc sách quang minh một đời.</p>",
                        "content_text": "Trước đèn xem chuyện Tây Minh, bõ công đọc sách quang minh một đời."
                    }
                ],
                "total": 2,
                "page": 1,
                "total_pages": 1,
                "message": "Danh sách truyện tải thành công"
            }
        """.trimIndent()

        val listResponse = gson.fromJson(storyListJson, StoryListResponse::class.java)
        assertNotNull(listResponse)
        assertEquals("success", listResponse.status)
        assertEquals(2, listResponse.total)
        assertEquals(1, listResponse.page)
        assertEquals(1, listResponse.totalPages)
        assertEquals("Danh sách truyện tải thành công", listResponse.message)
        assertEquals(2, listResponse.data.size)

        val story1 = listResponse.data[0]
        assertEquals(101, story1.id)
        assertEquals("Truyện Kiều (Đoạn Trường Tân Thanh)", story1.title)
        assertEquals("https://skul9x.free.nf/truyen/images/kieu.jpg", story1.image)
        assertEquals("<p>Trăm năm trong cõi người ta, chữ tài chữ mệnh khéo là ghét nhau.</p>", story1.content)
        assertEquals("Trăm năm trong cõi người ta, chữ tài chữ mệnh khéo là ghét nhau.", story1.contentText)

        val story2 = listResponse.data[1]
        assertEquals(102, story2.id)
        assertEquals("Lục Vân Tiên", story2.title)
        assertNull(story2.image)
        assertEquals("<p>Trước đèn xem chuyện Tây Minh, bõ công đọc sách quang minh một đời.</p>", story2.content)
        assertEquals("Trước đèn xem chuyện Tây Minh, bõ công đọc sách quang minh một đời.", story2.contentText)

        // 2. StoryDetailResponse deserialization
        val storyDetailJson = """
            {
                "status": "success",
                "data": {
                    "id": 101,
                    "title": "Truyện Kiều (Đoạn Trường Tân Thanh)",
                    "image": "https://skul9x.free.nf/truyen/images/kieu.jpg",
                    "content": "<p>Đoạn trường tân thanh chi tiết</p>",
                    "content_text": "Đoạn trường tân thanh chi tiết"
                }
            }
        """.trimIndent()

        val detailResponse = gson.fromJson(storyDetailJson, StoryDetailResponse::class.java)
        assertNotNull(detailResponse)
        assertEquals("success", detailResponse.status)
        assertEquals(101, detailResponse.data.id)
        assertEquals("Truyện Kiều (Đoạn Trường Tân Thanh)", detailResponse.data.title)
        assertEquals("https://skul9x.free.nf/truyen/images/kieu.jpg", detailResponse.data.image)
        assertEquals("<p>Đoạn trường tân thanh chi tiết</p>", detailResponse.data.content)
        assertEquals("Đoạn trường tân thanh chi tiết", detailResponse.data.contentText)

        // 3. RandomStoryResponse deserialization
        val randomStoryJson = """
            {
                "status": "success",
                "data": {
                    "id": 888,
                    "title": "Ngẫu Nhiên Một Câu Chuyện",
                    "image": null,
                    "content": "<p>Nội dung truyện ngẫu nhiên</p>",
                    "content_text": "Nội dung truyện ngẫu nhiên"
                }
            }
        """.trimIndent()

        val randomResponse = gson.fromJson(randomStoryJson, RandomStoryResponse::class.java)
        assertNotNull(randomResponse)
        assertEquals("success", randomResponse.status)
        assertEquals(888, randomResponse.data.id)
        assertEquals("Ngẫu Nhiên Một Câu Chuyện", randomResponse.data.title)
        assertNull(randomResponse.data.image)
    }

    @Test
    fun testDefaultValuesWhenKeysMissing() {
        // Test 1: Story with minimal keys (omits image, content, content_text)
        val minimalStoryJson = """
            {
                "id": 42,
                "title": "Truyện Cổ Tích Tối Giản"
            }
        """.trimIndent()

        val story = gson.fromJson(minimalStoryJson, Story::class.java)
        assertNotNull(story)
        assertEquals(42, story.id)
        assertEquals("Truyện Cổ Tích Tối Giản", story.title)
        assertNull("Omitted image must deserialize to null", story.image)
        assertNull("Omitted content must deserialize to null", story.content)
        assertNull("Omitted content_text must deserialize to null", story.contentText)

        // Test 2: StoryListResponse with only status (omits data, total, page, total_pages, message)
        val minimalListJson = """
            {
                "status": "success"
            }
        """.trimIndent()

        val minimalListResponse = gson.fromJson(minimalListJson, StoryListResponse::class.java)
        assertNotNull(minimalListResponse)
        assertEquals("success", minimalListResponse.status)
        assertNotNull("data list must default to non-null emptyList", minimalListResponse.data)
        assertTrue("data list must be empty", minimalListResponse.data.isEmpty())
        assertEquals(0, minimalListResponse.total)
        assertEquals(1, minimalListResponse.page)
        assertEquals(1, minimalListResponse.totalPages)
        assertNull("Omitted message must deserialize to null", minimalListResponse.message)

        // Test 3: StoryListResponse with empty json object {}
        val emptyListJson = "{}"
        val emptyListResponse = gson.fromJson(emptyListJson, StoryListResponse::class.java)
        assertNotNull(emptyListResponse)
        assertNotNull("Empty JSON must still produce non-null data list", emptyListResponse.data)
        assertTrue("Empty JSON data list must be empty", emptyListResponse.data.isEmpty())
        assertEquals(1, emptyListResponse.page)
        assertEquals(1, emptyListResponse.totalPages)

        // Test 4: Unknown / extra server fields are safely ignored without errors
        val jsonWithExtraFields = """
            {
                "status": "success",
                "unexpected_server_flag": true,
                "debug_info": {"server": "node-1", "timestamp": 123456789},
                "data": []
            }
        """.trimIndent()

        val safeResponse = gson.fromJson(jsonWithExtraFields, StoryListResponse::class.java)
        assertNotNull(safeResponse)
        assertEquals("success", safeResponse.status)
        assertTrue(safeResponse.data.isEmpty())
    }

    @Test
    fun testSerializationRoundTripPreservesExactJsonKeys() {
        val originalStory = Story(
            id = 200,
            title = "Dế Mèn Phiêu Lưu Ký",
            image = "https://example.com/demen.jpg",
            content = "<p>Bởi tôi ăn uống điều độ...</p>",
            contentText = "Bởi tôi ăn uống điều độ..."
        )

        val originalResponse = StoryListResponse(
            status = "success",
            data = listOf(originalStory),
            total = 1,
            page = 1,
            totalPages = 1,
            message = "OK"
        )

        val serializedJson = gson.toJson(originalResponse)
        assertNotNull(serializedJson)

        // Verify that serialized JSON uses exact snake_case keys as specified in @SerializedName
        assertTrue("Serialized JSON must contain 'content_text'", serializedJson.contains("\"content_text\""))
        assertTrue("Serialized JSON must contain 'total_pages'", serializedJson.contains("\"total_pages\""))
        assertTrue("Serialized JSON must contain 'id'", serializedJson.contains("\"id\""))
        assertTrue("Serialized JSON must contain 'title'", serializedJson.contains("\"title\""))
        assertTrue("Serialized JSON must contain 'image'", serializedJson.contains("\"image\""))

        // Deserialize back and assert equality
        val roundTripResponse = gson.fromJson(serializedJson, StoryListResponse::class.java)
        assertEquals(originalResponse.status, roundTripResponse.status)
        assertEquals(originalResponse.total, roundTripResponse.total)
        assertEquals(originalResponse.page, roundTripResponse.page)
        assertEquals(originalResponse.totalPages, roundTripResponse.totalPages)
        assertEquals(originalResponse.message, roundTripResponse.message)
        assertEquals(1, roundTripResponse.data.size)

        val roundTripStory = roundTripResponse.data[0]
        assertEquals(originalStory.id, roundTripStory.id)
        assertEquals(originalStory.title, roundTripStory.title)
        assertEquals(originalStory.image, roundTripStory.image)
        assertEquals(originalStory.content, roundTripStory.content)
        assertEquals(originalStory.contentText, roundTripStory.contentText)
    }
}
