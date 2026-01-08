# DocTruyen - Android Story Reading App

DocTruyen is an Android application designed for reading stories with integrated Text-to-Speech (TTS) capabilities. It allows users to browse stories, read them as text, or listen to them via an automated voice.

## Features

-   **Story Browsing**: Browse a collection of stories fetched from a remote API.
-   **Text-to-Speech (TTS)**: Listen to stories using high-quality TTS.
    -   **Background Playback**: Continues reading even when the app is minimized or the screen is off.
    -   **Media Controls**: Play, Pause, Resume, and Stop functionality.
    -   **Notification Controls**: Control playback directly from the notification shade (Android 13+ support).
-   **Smart Content Handling**:
    -   **HTML Content**: proper rendering of story text.
    -   **Image Loading**: Secure image loading with custom headers (Cookies, User-Agent) using Glide.
-   **Debug Mode**: Built-in debug logging viewer for monitoring app performance and logic on the fly.

## Technical Stack

-   **Language**: Kotlin
-   **Minimum SDK**: 24 (Android 7.0)
-   **Target SDK**: 35 (Android 15)
-   **Architecture**: MVVM / Service-based for Audio
-   **Networking**: Retrofit 2, OkHttp 3, Gson
-   **Image Loading**: Glide 4.x
-   **Concurrency**: Kotlin Coroutines
-   **UI Components**: Material Design 3, RecyclerView, CardView, SwipeRefreshLayout

## Permissions

The app requires the following permissions:
-   `INTERNET`: To fetch story data and images.
-   `ACCESS_NETWORK_STATE`: To check connectivity.
-   `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: To run the TTS service in the background.
-   `POST_NOTIFICATIONS`: To show media controls in the notification area (Android 13+).

## Development Notes

### TTS Logic
The TTS engine is managed by `TTSManager` and exposed via `ReadingService`. The `StoryDetailActivity` binds to this service to update the UI (buttons, progress) based on the current playback state.

### Debugging
The app includes a custom `DebugLogger` utility. Logs can be viewed in Logcat or within the app's `DebugLogActivity`.

## Build
To build the project, use standard Gradle commands:
```bash
./gradlew assembleDebug
```
