# Phase 01: Early G2P Dictionary Pre-warming & Phoneme LRU Cache
Status: ✅ Completed
Dependencies: None

## Objective
Eliminate cold-start G2P phonemization latency spikes on Chunk 1 (previously reaching 79ms - 129ms) by implementing early asynchronous dictionary extraction & pre-warming at app launch, exposing JNI `nativeInit`, and adding an in-memory thread-safe JVM-compatible LRU cache for recurring words and phrases.

## Requirements
### Functional
- Expose JNI `nativeInit(dictPath: String): Boolean` in `SeaG2P.kt` corresponding to the exported Rust native function `Java_com_skul9x_doctruyen_tts_vieneu_SeaG2P_nativeInit`.
- Implement `SeaG2P.warmUp(context: Context?)` that:
  - Ensures `sea_g2p.bin` is extracted from APK assets (`vieneu/sea_g2p.bin`) to internal storage (`filesDir/vieneu/sea_g2p.bin`) if not already present on disk.
  - Locates `sea_g2p.bin` in app internal storage or asset paths and invokes `nativeInit`.
  - Executes a lightweight warm-up phonemization (e.g. `"Khởi động"`) to pre-compile normalizer regexes and warm CPU instruction/data caches.
  - Is safely idempotent and callable multiple times without re-initialization overhead (`isWarmedUp()` check).
- Implement an in-memory thread-safe JVM-compatible LRU cache (`LinkedHashMap` with bounded size, default 512 entries) in `SeaG2P` to avoid redundant native JNI transitions for recurring phrases, compatible with both Android runtime and JVM unit test runners.
- Add cache management APIs: `clearCache()`, `getCacheSize(): Int`, and `isWarmedUp(): Boolean`.
- Update `phonemize(text: String)`:
  - Fast-path for empty or blank strings (returns `""`).
  - Check LRU cache for normalized/trimmed text before calling `nativePhonemize`.
  - Store successful phonemizations in the LRU cache.
- Trigger `SeaG2P.warmUp(this)` asynchronously in `MainActivity.onCreate()` (and within `VieNeuTTSManager.initManager()`) on `Dispatchers.IO`.

### Non-Functional
- Chunk 1 phonemization latency after warmup must be < 10ms (well under the 50ms `G2P_OVERHEAD` threshold).
- Thread-safe access to phoneme cache and G2P native routines across concurrent coroutines.
- Unit tests must run cleanly on both JVM (`./gradlew test`) and Android instrumentation without `android.util.LruCache` stub errors.

## Implementation Steps
1. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/SeaG2P.kt`:
   - Declare `private external fun nativeInit(dictPath: String): Boolean`.
   - Add thread-safe bounded LRU cache (e.g. synchronized `LinkedHashMap` with max capacity 512).
   - Implement `fun warmUp(context: Context? = null)` with asset extraction check and warmup phonemization.
   - Implement `fun isWarmedUp(): Boolean`.
   - Update `phonemize(text: String)` to check and populate the LRU cache with fast-path for blank strings.
   - Add `fun clearCache()` and `fun getCacheSize(): Int`.
2. In `app/src/main/java/com/skul9x/doctruyen/MainActivity.kt`:
   - Call `lifecycleScope.launch(Dispatchers.IO) { SeaG2P.warmUp(applicationContext) }` in `onCreate()`.
3. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt`:
   - Ensure `SeaG2P.warmUp(ctx)` is called during engine setup.
4. Create comprehensive verification unit test `EarlyG2PWarmingAndCacheTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/SeaG2P.kt` - Add native init, warmup routine with asset extraction, and JVM-compatible LRU cache.
- `app/src/main/java/com/skul9x/doctruyen/MainActivity.kt` - Trigger early warmup on app launch.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt` - Integrate warmup in manager init.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/EarlyG2PWarmingAndCacheTest.kt` - Verification test for warmup and caching.

## Test Criteria
- [x] Exactly one file-based unit test `EarlyG2PWarmingAndCacheTest.kt` passes, verifying:
  - Idempotent warmup completes without errors.
  - LRU cache hit/miss behavior and caching of phonemized strings.
  - Warmup reduces subsequent phonemization latency to < 10ms.
  - Cache clearing and thread-safety under concurrent access.

---
Next Phase: [Phase 02](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/plans/260821-0955-g2p-prewarm-and-native-memory-management/phase-02-native-memory-lifecycle-management.md)

