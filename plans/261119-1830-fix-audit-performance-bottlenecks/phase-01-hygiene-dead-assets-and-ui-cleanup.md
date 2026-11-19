# Phase 01: Hygiene, Dead Assets, and UI/Network Cleanup

Status: ✅ Completed  
Dependencies: None  

## Objective
Remediate immediate low-risk, high-impact hygiene issues across the application: declare `largeHeap="true"` to raise the JVM heap headroom, purge 85MB of unused models from APK assets, eliminate frame drops caused by repetitive Regex compilation and heavy HTML string sanitization on the Main UI thread, replace repetitive XML animation inflation in `StoryAdapter`, and resolve HTTP header leakage and dynamic proxy churn in `ApiService`.

## Scope
- `app/src/main/AndroidManifest.xml` (PERF-003 part 1: `android:largeHeap="true"`).
- `app/src/main/assets/vieneu/denoiser/` & `app/src/main/assets/vieneu/codec/moss_audio_tokenizer_encode.*` (PERF-005: dead models removal).
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuConfig.kt` (remove dead denoiser constant).
- `app/src/main/java/com/skul9x/doctruyen/StoryDetailActivity.kt` (PERF-010: static pre-compiled Regex and offload HTML work to `Dispatchers.Default`).
- `app/src/main/java/com/skul9x/doctruyen/adapter/StoryAdapter.kt` (UI animation optimization: replace `AnimationUtils.loadAnimation` XML parsing).
- `app/src/main/java/com/skul9x/doctruyen/network/ApiService.kt` (PERF-012: set `HttpLoggingInterceptor.Level.NONE` on Release and cache `ApiService` instance).

## Requirements

### Functional Requirements
- [x] Add `android:largeHeap="true"` to the `<application>` tag in `app/src/main/AndroidManifest.xml`.
- [x] Delete dead assets:
  - `app/src/main/assets/vieneu/denoiser/denoiser.onnx` (41MB)
  - `app/src/main/assets/vieneu/codec/moss_audio_tokenizer_encode.onnx` (797KB)
  - `app/src/main/assets/vieneu/codec/moss_audio_tokenizer_encode.data` (43MB)
- [x] Remove `VieNeuConfig.DENOISER_DIR` and any references to denoiser paths.
- [x] In `StoryDetailActivity.kt`:
  - Extract the 5 regular expressions used in `sanitizeHtml()` into static pre-compiled `Regex` constants inside `companion object`:
    - `SCRIPT_REGEX`
    - `EVENT_HANDLER_REGEX`
    - `JAVASCRIPT_URI_REGEX`
    - `TAG_PAIR_REGEX`
    - `SELF_CLOSING_TAG_REGEX`
  - Wrap `sanitizeHtml()` and `Html.fromHtml()` inside `withContext(Dispatchers.Default)` so that parsing of large chapters does not block the Main UI Thread.
- [x] In `StoryAdapter.kt`:
  - Avoid calling `AnimationUtils.loadAnimation(..., android.R.anim.fade_in)` inside `onBindViewHolder`.
  - Use `itemView.alpha = 0f; itemView.animate().alpha(1f).setDuration(300).setStartDelay(...)` (hardware-accelerated `ViewPropertyAnimator`) or cache the loaded `Animation` object once.
- [x] In `ApiService.kt`:
  - Configure `HttpLoggingInterceptor.level`: if `BuildConfig.DEBUG` use `Level.BODY`, otherwise use `Level.NONE` (preventing sensitive cookie/header emission in release).
  - Cache the created `ApiService` instance in a private variable and reuse it as long as `baseUrl` remains unchanged, avoiding repeated `Retrofit.create()` reflection proxy allocations.

### Non-Functional Requirements
- [x] APK Size Reduction: Reduce asset payload by ~85MB.
- [x] UI Responsiveness: Zero frame drops (< 16ms) during chapter loading in `StoryDetailActivity`.
- [x] Security: No authorization headers or session cookies written to Logcat in production release builds.
- [x] Zero Regression: Existing HTML formatting (paragraphs, bold, italic, line breaks) and API calls function identically.

## How to Implement

1. **Manifest Configuration:**
   - In `app/src/main/AndroidManifest.xml`, locate `<application ...>` and add `android:largeHeap="true"`.
2. **Asset Purge & Config Cleanup:**
   - Run shell removal for `app/src/main/assets/vieneu/denoiser/` and `app/src/main/assets/vieneu/codec/moss_audio_tokenizer_encode.*`.
   - In `VieNeuConfig.kt`, remove `const val DENOISER_DIR = "$ASSET_DIR/denoiser"`.
3. **Regex Precompilation & Dispatcher Switching in `StoryDetailActivity.kt`:**
   - In `companion object`, declare:
     ```kotlin
     private val SCRIPT_REGEX = Regex("<script[^>]*>.*?</script>", RegexOption.IGNORE_CASE)
     private val EVENT_HANDLER_REGEX = Regex("\\s*on\\w+\\s*=\\s*[\"'][^\"']*[\"']", RegexOption.IGNORE_CASE)
     private val JAVASCRIPT_URI_REGEX = Regex("javascript:", RegexOption.IGNORE_CASE)
     private val DISALLOWED_PAIRED_TAGS_REGEX = Regex("<(iframe|object|embed|form|input)[^>]*>.*?</(iframe|object|embed|form|input)>", RegexOption.IGNORE_CASE)
     private val DISALLOWED_SELF_CLOSING_TAGS_REGEX = Regex("<(iframe|object|embed|form|input)[^>]*/>", RegexOption.IGNORE_CASE)
     ```
   - In `loadStoryContent()`, move HTML sanitization and `Html.fromHtml` into `withContext(Dispatchers.Default)`:
     ```kotlin
     val (spannedContent, plainString) = withContext(Dispatchers.Default) {
         var raw = rawContent.replace("\\\\n", "\n")
         if (!raw.contains("<br") && !raw.contains("<p")) {
             raw = raw.replace("\n", "<br>")
         }
         val sanitized = sanitizeHtml(raw)
         val spanned = Html.fromHtml(sanitized, Html.FROM_HTML_MODE_LEGACY)
         Pair(spanned, spanned.toString())
     }
     storyContent = plainString
     tvContent.text = spannedContent
     ```
4. **Adapter Animation Optimization in `StoryAdapter.kt`:**
   - Replace `AnimationUtils.loadAnimation` with:
     ```kotlin
     if (position > lastAnimatedPosition) {
         holder.itemView.alpha = 0f
         holder.itemView.animate()
             .alpha(1f)
             .setDuration(300)
             .setStartDelay((position % 4) * 50L)
             .start()
         lastAnimatedPosition = position
     }
     ```
   - In `onViewDetachedFromWindow`, call `holder.itemView.animate().cancel()`.
5. **Retrofit Proxy Caching & Log Level in `ApiService.kt`:**
   - In `createClient()`:
     ```kotlin
     val loggingInterceptor = HttpLoggingInterceptor().apply {
         level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
     }
     ```
   - In `RetrofitClient`:
     ```kotlin
     private var cachedApiService: ApiService? = null
     private var cachedUrl: String? = null

     fun getApiService(context: android.content.Context): ApiService {
         val url = com.skul9x.doctruyen.utils.UserConfig.getBaseUrl(context)
         if (cachedApiService == null || cachedUrl != url) {
             cachedUrl = url
             cachedApiService = getClient(url).create(ApiService::class.java)
         }
         return cachedApiService!!
     }
     ```

## Files to Create/Modify
- `app/src/main/AndroidManifest.xml` - Add `android:largeHeap="true"`.
- `app/src/main/assets/vieneu/denoiser/*` - Remove directory and files.
- `app/src/main/assets/vieneu/codec/moss_audio_tokenizer_encode.*` - Remove files.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuConfig.kt` - Remove `DENOISER_DIR`.
- `app/src/main/java/com/skul9x/doctruyen/StoryDetailActivity.kt` - Static Regexes and Default dispatcher offload.
- `app/src/main/java/com/skul9x/doctruyen/adapter/StoryAdapter.kt` - Hardware-accelerated ViewPropertyAnimator.
- `app/src/main/java/com/skul9x/doctruyen/network/ApiService.kt` - Release Level.NONE logging and ApiService proxy caching.
- `app/src/test/java/com/skul9x/doctruyen/performance/HygieneAndAssetCleanupTest.kt` - Single comprehensive unit test.

## Dependencies
- None. Standard AndroidX, OkHttp, and Kotlin library capabilities.

## Constraints
- Do NOT alter the HTML sanitize security rules (keep filtering scripts, event handlers, javascript:, iframe, embed).
- Do NOT break `StoryDetailActivity` UI bindings or auto-play flow.

## Acceptance Criteria
- [x] Manifest explicitly declares `android:largeHeap="true"`.
- [x] Assets directory no longer contains `denoiser/` or `moss_audio_tokenizer_encode.*`. Total asset size reduced by ~85MB.
- [x] HTML sanitization executes using precompiled static Regex patterns and executes off the Main thread.
- [x] `StoryAdapter` applies fade-in animations via `ViewPropertyAnimator` without XML inflation.
- [x] `RetrofitClient.getApiService()` returns cached instance when called repeatedly with the same base URL.
- [x] All scenarios in `HygieneAndAssetCleanupTest.kt` pass with 100% success rate.

## Single File-Based Test Criteria
- File: `app/src/test/java/com/skul9x/doctruyen/performance/HygieneAndAssetCleanupTest.kt`
- Test Scenarios:
  1. `testManifestContainsLargeHeap`: Verify `AndroidManifest.xml` contains `android:largeHeap="true"`.
  2. `testDeadAssetsPurged`: Verify that `denoiser.onnx` and `moss_audio_tokenizer_encode.*` files do not exist in the assets directory.
  3. `testHtmlSanitizationSecurityAndSpeed`: Verify precompiled regexes in `StoryDetailActivity` correctly strip malicious script tags, event handlers, and javascript URIs while preserving valid tags (`<p>`, `<br>`, `<b>`).
  4. `testRetrofitClientCaching`: Verify that multiple calls to `getApiService()` return the identical cached proxy instance, and invalidates when base URL changes.
  5. `testReleaseLogLevelIsNone`: Verify that in non-debug mode, the logging interceptor level is configured to `Level.NONE`.

## Review Checkpoint
Stop after running `HygieneAndAssetCleanupTest.kt` and await user review before proceeding to Phase 02.

