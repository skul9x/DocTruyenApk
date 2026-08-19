# Phase 07: Background Playback & Screen-Off Optimization

Status: ✅ Completed  
Dependencies: Phase 04 (Dual TTS Engine), Phase 06 (Story Switching)

## Objective

Ensure rock-solid, uninterrupted TTS playback when the device screen is turned off, during Android Doze mode, and when switching between foreground/background states.

## Background Execution Challenges & Solutions

| Challenge | Impact | Solution |
|---|---|---|
| **CPU Suspension in Doze Mode** | ONNX synthesis stops or lags when screen turns off, leading to AudioTrack buffer starvation and choppy audio. | Acquire `PowerManager.PARTIAL_WAKE_LOCK` whenever TTS state is `PLAYING` or `SYNTHESIZING`. Safely release on `PAUSED`, `IDLE`, or `onDestroy()`. |
| **Android 14+ Foreground Service Policy** | Service killed if `foregroundServiceType` is invalid or permissions are missing. | Use `FOREGROUND_SERVICE_MEDIA_PLAYBACK` + `POST_NOTIFICATIONS` + `startForeground` with proper media notification. |
| **Lock Screen Controls** | User unable to control playback when phone is locked. | Implement `androidx.media.app.NotificationCompat.MediaStyle` with `VISIBILITY_PUBLIC`, lock-screen Play/Pause/Stop actions, and `MediaSessionCompat`. |
| **Audio Focus Transitions** | Incoming calls or alarms cause audio collision or permanent silence. | Implement `AudioManagerCompat` with transient focus loss handling (ducking / pausing during call, resuming on regain). |
| **OEM Aggressive Battery Killers** | MIUI/ColorOS/OneUI killing background services after screen off. | Add helper utility to check battery optimization exemption status (`PowerManager.isIgnoringBatteryOptimizations`). |

## Implementation Steps

### 7.1 WakeLock Management in `ReadingService`
1. [ ] Add `PowerManager.WakeLock?` to `ReadingService`:
   - Initialize `powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DocTruyen:ReadingWakeLock")`.
   - `setReferenceCounted(false)`.
2. [ ] Implement safe lock acquisition in `acquireWakeLock()`:
   - Call whenever state transitions to `PLAYING` or `SYNTHESIZING`.
3. [ ] Implement safe lock release in `releaseWakeLock()`:
   - Call whenever state transitions to `PAUSED`, `READY`, `IDLE`, `ERROR`, or in `onDestroy()`.

### 7.2 MediaSession & Lock Screen Controls
1. [ ] Integrate `MediaSessionCompat` into `ReadingService`:
   - Set playback state (`PlaybackStateCompat.STATE_PLAYING` / `STATE_PAUSED`).
   - Connect transport control callbacks (Play, Pause, Stop, Seek).
   - Set session token on `NotificationCompat.MediaStyle`.
2. [ ] Configure Notification Channel for Lock Screen Visibility:
   - Set `NotificationCompat.VISIBILITY_PUBLIC` on reading notifications so controls appear directly on the lock screen.

### 7.3 Audio Focus Hardening
1. [ ] Enhance audio focus listener in `TTSAudioPipeline` and `GoogleTTSManager`:
   - `AUDIOFOCUS_LOSS_TRANSIENT`: Pause playback temporarily.
   - `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`: Lower volume to 0.2f.
   - `AUDIOFOCUS_GAIN`: Restore volume or resume playback.
   - `AUDIOFOCUS_LOSS`: Stop playback and release foreground.

### 7.4 Battery Optimization Helper
1. [ ] Create `BatteryOptimizationHelper` utility to check and request battery optimization ignore intent if background playback is killed on specific OEM devices.

## Files to Create/Modify
- `[NEW] app/src/main/java/com/skul9x/doctruyen/utils/BatteryOptimizationHelper.kt`
- `[MODIFY] app/src/main/java/com/skul9x/doctruyen/service/ReadingService.kt`
- `[MODIFY] app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt`
- `[MODIFY] app/src/main/java/com/skul9x/doctruyen/tts/GoogleTTSManager.kt`
- `[MODIFY] app/src/main/AndroidManifest.xml`

## Test Criteria

### Automated Tests
- [ ] **WakeLockLifecycleTest.kt**:
  - Verify `wakeLock.acquire()` is called when reading starts.
  - Verify `wakeLock.release()` is called when reading is paused or stopped.
  - Verify `wakeLock.isHeld` is false after service shutdown.
- [ ] **MediaSessionStateTest.kt**:
  - Verify `MediaSessionCompat` playback state matches TTS state.
  - Test transport callback dispatch for Play/Pause intents.

### Manual Verification
- Start reading a long story (3000+ words).
- Turn off screen (lock phone).
- Wait 5–10 minutes with screen off.
- Verify audio continues playing seamlessly across sentence and paragraph boundaries without stuttering or stopping.
- Verify lock screen widget displays story title and working Play/Pause buttons.
- Receive a simulated phone call -> verify audio pauses and resumes cleanly after call ends.

---
All Phases Defined. Ready for Review.
