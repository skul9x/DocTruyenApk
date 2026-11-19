# Handover Document: Retrofit R8 ParameterizedType Fix & Network Resilience

**Date:** 2026-11-19 23:12  
**Project:** DocTruyen (VieNeu-TTS v3 Turbo On-Device Neural Reader)  
**Status:** Completed & Shipped (Release v1.14 on GitHub)

---

## 📍 Summary of Today's Work
1. **Audited Logcat & System Metrics:**
   - Verified app running on device `CPH2691` (OnePlus/OPPO Android 15).
   - Fast cold start: MainActivity (206ms), SettingsActivity (89ms). Zero native memory leaks, RSS 153MB–170MB.
2. **Investigated Server API (`skul9x.free.nf/truyen/api.php`):**
   - Confirmed backend is alive with 114 stories.
   - Diagnosed InfinityFree anti-bot protection requiring `__test` cookie and `X-Requested-With: com.skul9x.doctruyen`.
3. **Fixed `java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType`:**
   - Root cause: R8 Full Mode in release build stripping generic signatures from Kotlin coroutines `Continuation` in Retrofit suspend functions.
   - Fixed by adding explicit ProGuard rules for `interface retrofit2.Call`, `class retrofit2.Response`, and `class kotlin.coroutines.Continuation`.
4. **Enhanced HostingVerifier & Network Error Recovery:**
   - Removed 1x1 pixel invisible dialog that triggered Chromium WebView JS throttling on Android 15; replaced with responsive dialog layout.
   - Added `CookieManager.getInstance().flush()`.
   - Updated `MainActivity.kt` and `StoryDetailActivity.kt` to catch `JsonSyntaxException` (caused when server returns HTML challenge instead of JSON), automatically clear expired cookies, and re-run verification.
5. **Fixed GitHub Actions CI/CD Compilation Error:**
   - Root cause: In `StoryDetailActivity.kt`, line 364 invoked `loadStoryDetail(storyId)` which did not exist (`Unresolved reference 'loadStoryDetail'`).
   - Fix: Replaced with `loadedStoryId = -1; loadStoryContent()`.
   - Best practice: Added `CancellationException` handling and unified anti-bot error detection.

---

## 📁 Key Files Modified
- `app/src/main/java/com/skul9x/doctruyen/StoryDetailActivity.kt`
- `app/proguard-rules.pro`
- `app/src/main/java/com/skul9x/doctruyen/MainActivity.kt`
- `app/src/main/java/com/skul9x/doctruyen/network/HostingVerifier.kt`
- `app/src/test/java/com/skul9x/doctruyen/performance/AssetExtractionAndProguardRulesTest.kt`
- `.brain/brain.json`
- `.brain/session.json`
- `.brain/session_log.txt`
