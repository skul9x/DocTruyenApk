# Phase 04: Dual TTS Engine (VieNeu Neural vs Google TTS)

Status: ✅ Completed
Dependencies: Phase 03 (Voice Switching)

## Objective

Implement a unified TTS engine architecture allowing users to choose between:
1. **VieNeu Neural TTS (Offline)**: Fast on-device neural synthesis using ONNX models with Vietnamese preset voices ("Trúc Ly", "Minh Đức", etc.).
2. **Google TTS (Online/System)**: High-compatibility cloud/system Text-to-Speech using Android's native `android.speech.tts.TextToSpeech` with full engine targeting (`com.google.android.tts`), voice selection, pitch/rate controls, and gapless sentence sequencing.

## Architecture & Design

### Unified TTS Interface (`ITTSManager`)
Create a shared interface for both engines to ensure seamless interchangeability in `ReadingService`:

```kotlin
package com.skul9x.doctruyen.tts

import com.skul9x.doctruyen.tts.vieneu.PauseConfig

interface ITTSManager {
    enum class EngineType {
        VIENEU,      // Offline Neural ONNX
        GOOGLE_TTS   // Android System / Google TTS
    }

    enum class TTSState {
        IDLE,
        INITIALIZING,
        READY,
        PLAYING,
        PAUSED,
        ERROR
    }

    val currentState: TTSState
    val engineType: EngineType
    val currentVoiceName: String

    fun speak(text: String, storyId: Int = 0)
    fun speakWithTitlePause(title: String, content: String, storyId: Int = 0, pauseConfig: PauseConfig = PauseConfig())
    fun pause()
    fun resume()
    fun stop()
    fun isPlaying(): Boolean
    fun isPaused(): Boolean
    fun setVoice(voiceName: String)
    fun getAvailableVoices(): List<VoiceInfo>
    fun setSpeed(speed: Float)
    fun setPitch(pitch: Float)
    fun prepareStory(storyId: Int, title: String, content: String, voice: String? = null, pauseConfig: PauseConfig = PauseConfig())
    fun cancelPrewarm()
    fun shutdown()
}

data class VoiceInfo(
    val id: String,
    val displayName: String,
    val description: String = "",
    val gender: String = "",
    val region: String = "",
    val isDefault: Boolean = false
)
```

### Concrete Google TTS Engine Implementation (`GoogleTTSManager`)
Build a dedicated, high-resilience Google TTS implementation:
1. **Targeted Engine Binding**: Initializes `TextToSpeech` specifically preferring `com.google.android.tts`, with graceful fallback to system default.
2. **Voice Resolution**: Enumerates Vietnamese (`vi-VN`) voices provided by Google TTS (e.g. `vi-vn-x-vic-network`, `vi-vn-x-vif-local`), parses gender/quality metadata, and formats user-friendly display labels.
3. **Chunking & Natural Pauses**: Employs `SmartTextSegmenter` to split content into sentence chunks, injecting silent utterances between title/paragraphs to match cadence settings.
4. **Utterance Progress Tracking**: Hooks `UtteranceProgressListener` to calculate reading progress (0–100%) and report chunk transitions.
5. **Dynamic Modulation**: Applies user speed (0.5x–2.0x) and pitch (0.5x–2.0x) parameters seamlessly.

### Config & Service Switching
1. **UserConfig Additions**:
   - `KEY_TTS_ENGINE` ("VIENEU" | "GOOGLE_TTS", default: "VIENEU")
   - `KEY_GOOGLE_TTS_VOICE` (selected Google voice ID)
   - `getTtsEngine(Context): ITTSManager.EngineType`
   - `setTtsEngine(Context, ITTSManager.EngineType)`
   - `getGoogleTtsVoice(Context): String?`
   - `setGoogleTtsVoice(Context, String)`
2. **Unified Factory / Orchestrator (`TTSManagerFactory`)**:
   - Instantiates or swaps `ITTSManager` in `ReadingService` dynamically when settings change.

## Implementation Steps

1. [ ] Create `com.skul9x.doctruyen.tts.ITTSManager` interface and `VoiceInfo` data model.
2. [ ] Refactor `VieNeuTTSManager` to implement `ITTSManager`.
3. [ ] Rebuild `AndroidTTSManager` into `GoogleTTSManager` implementing `ITTSManager`:
   - Add explicit Google TTS engine specification (`com.google.android.tts`).
   - Add Vietnamese voice filtering and metadata extraction.
   - Support `speakWithTitlePause` with configurable sentence/paragraph delays.
   - Implement real-time progress callbacks.
4. [ ] Update `UserConfig` with TTS engine preference keys and getters/setters.
5. [ ] Create `TTSManagerFactory` to instantiate the appropriate engine based on `UserConfig`.
6. [ ] Update `ReadingService` to operate on `ITTSManager`, seamlessly switching engines without activity restarts.
7. [ ] Add `<queries>` for `android.intent.action.TTS_SERVICE` in `AndroidManifest.xml` for Android 11+ package visibility.

## Files to Create/Modify
- `[NEW] app/src/main/java/com/skul9x/doctruyen/tts/ITTSManager.kt`
- `[NEW] app/src/main/java/com/skul9x/doctruyen/tts/GoogleTTSManager.kt`
- `[NEW] app/src/main/java/com/skul9x/doctruyen/tts/TTSManagerFactory.kt`
- `[MODIFY] app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt`
- `[MODIFY] app/src/main/java/com/skul9x/doctruyen/utils/UserConfig.kt`
- `[MODIFY] app/src/main/java/com/skul9x/doctruyen/service/ReadingService.kt`
- `[MODIFY] app/src/main/AndroidManifest.xml`

## Test Criteria

### Automated Tests
- [x] **DualEngineFactoryTest.kt**:
  - Verify `TTSManagerFactory.create()` returns `VieNeuTTSManager` when config is `VIENEU`.
  - Verify `TTSManagerFactory.create()` returns `GoogleTTSManager` when config is `GOOGLE_TTS`.
- [x] **GoogleTTSManagerTest.kt**:
  - Test Vietnamese voice filtering from mock Voice set.
  - Verify state transitions: `IDLE` -> `INITIALIZING` -> `READY` -> `PLAYING` -> `PAUSED` -> `READY`.
  - Verify sentence chunk queueing and utterance ID progression.
- [x] **EngineSwitchingServiceTest.kt**:
  - Verify switching engine in `ReadingService` stops ongoing playback and initializes new engine cleanly without memory leak.

### Manual Verification
- In Settings, switch engine to "Google TTS".
- Verify available voices list updates to Google Vietnamese voices.
- Play a story and verify Google TTS voice output and cadence.
- Switch back to "VieNeu Neural TTS" and verify on-device neural voice plays instantly.

---
Next Phase: [Phase 05 — Settings UI/UX Overhaul](./phase-05-settings-ui-ux.md)
