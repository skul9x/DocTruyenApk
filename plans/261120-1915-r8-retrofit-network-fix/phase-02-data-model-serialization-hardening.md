# Phase 02: Resilient Data Model Serialization & Field Hardening

**Status:** ✅ Completed  
**Dependencies:** Phase 01  

## Objective
Ensure 100% resilience of all network data models against R8 field renaming, dead-field pruning, and synthetic constructor removal. Annotate all DTO classes (`Story`, `StoryListResponse`, `StoryDetailResponse`, `RandomStoryResponse`) with `@androidx.annotation.Keep` and guarantee explicit `@SerializedName` annotations on every single field.

## Scope
- Modify `app/src/main/java/com/skul9x/doctruyen/network/ApiService.kt` data model definitions.
- Add `@androidx.annotation.Keep` to every network model class.
- Add explicit `@SerializedName("...")` to all properties across all models, removing any implicit dependency on Kotlin bytecode parameter names.
- Provide safe default initializers for optional and collection fields to prevent `NullPointerException` when server responses omit keys.

## Requirements
### Functional
1. **Explicit Serialization Names**: Every JSON property mapped by Gson must be declared with `@SerializedName("exact_json_key")`.
2. **Class & Constructor Preservation**: All network DTOs must be annotated with `@androidx.annotation.Keep` to prevent R8 from stripping primary constructors or default value bridges.
3. **Null Safety & Resilience**: Optional fields (e.g. `image`, `content`, `contentText`, `message`) must have safe nullability contracts, and list fields (e.g. `data: List<Story>`) must default to `emptyList()`.
4. **Backward Compatibility**: Serialization and deserialization of existing backend responses (`api.php`) must remain identical with 0 schema breakage.

### Non-Functional
- Zero overhead at runtime. Deserialization latency with Gson remains identical or improves due to direct `@SerializedName` lookup table caching.

## How to Implement
1. **Update Data Classes in `ApiService.kt`**:
   - Annotate `Story` with `@Keep`:
     ```kotlin
     @androidx.annotation.Keep
     data class Story(
         @SerializedName("id") val id: Int,
         @SerializedName("title") val title: String,
         @SerializedName("image") val image: String?,
         @SerializedName("content") val content: String? = null,
         @SerializedName("content_text") val contentText: String? = null
     )
     ```
   - Annotate `StoryListResponse` with `@Keep`:
     ```kotlin
     @androidx.annotation.Keep
     data class StoryListResponse(
         @SerializedName("status") val status: String,
         @SerializedName("data") val data: List<Story> = emptyList(),
         @SerializedName("total") val total: Int = 0,
         @SerializedName("page") val page: Int = 1,
         @SerializedName("total_pages") val totalPages: Int = 1,
         @SerializedName("message") val message: String? = null
     )
     ```
   - Annotate `StoryDetailResponse` and `RandomStoryResponse` with `@Keep`:
     ```kotlin
     @androidx.annotation.Keep
     data class StoryDetailResponse(
         @SerializedName("status") val status: String,
         @SerializedName("data") val data: Story
     )

     @androidx.annotation.Keep
     data class RandomStoryResponse(
         @SerializedName("status") val status: String,
         @SerializedName("data") val data: Story
     )
     ```
2. **Verify ProGuard Matchers**:
   - Verify that `-keepclassmembers,allowobfuscation class * { @com.google.gson.annotations.SerializedName <fields>; }` in `proguard-rules.pro` correctly protects every annotated field.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/network/ApiService.kt` - Add `@Keep` and comprehensive `@SerializedName` annotations to all models.

## Dependencies
- Phase 01 (R8 rules in `proguard-rules.pro` that preserve `@SerializedName` fields and `@Keep` annotations).

## Constraints
- Do not change field data types or backend JSON keys.
- Do not introduce alternative serialization libraries (keep Gson 2.9.0 without adding new heavy dependencies).

## Acceptance Criteria
- All properties in `Story`, `StoryListResponse`, `StoryDetailResponse`, and `RandomStoryResponse` have `@SerializedName` annotations matching backend keys.
- All 4 model classes are decorated with `@androidx.annotation.Keep`.
- Deserialization of valid and partial JSON payloads completes cleanly without missing field errors.

## Comprehensive File-Based Test
- **File:** `app/src/test/java/com/skul9x/doctruyen/network/DataModelSerializationIntegrityTest.kt`
- **Verification Strategy:**
  1. `testAllModelFieldsHaveSerializedName`: Uses reflection to iterate through all fields of `Story`, `StoryListResponse`, `StoryDetailResponse`, `RandomStoryResponse` and asserts that every declared member has a `@SerializedName` annotation.
  2. `testAllModelsHaveKeepAnnotation`: Verifies via reflection that all 4 DTO classes have `@Keep` annotation present.
  3. `testJsonDeserializationWithObfuscatedSimulation`: Simulates deserialization of realistic backend JSON responses and confirms field mapping integrity.
  4. `testDefaultValuesWhenKeysMissing`: Verifies that omitting optional fields (such as `contentText`, `message`, `data`) safely falls back to default values without throwing exceptions.

