# Project Structure: DocTruyen

## Root Directory
`c:\Users\Admin\AndroidStudioProjects\DocTruyen\`

## Source Code Structure (`app/src/main/java/com/skul9x/doctruyen/`)

### Core Activities
- **MainActivity.kt**: The main entry point of the application, likely displaying a list of stories.
- **StoryDetailActivity.kt**: Handles the display of a single story's details (image, title, content) and controls the TTS playback (Play/Pause/Stop).
- **SettingsActivity.kt**: Manages application settings.
- **DebugLogActivity.kt**: A utility activity for viewing application debug logs in real-time.

### Packages
- **adapter/**: Contains RecyclerView adapters for lists (e.g., story lists).
- **network/**: Handles network operations.
    - Likely contains `RetrofitClient` and API interfaces.
- **service/**: Background services.
    - **ReadingService.kt**: Foreground service responsible for TTS playback, ensuring audio continues when the app is in the background.
    - Contains service binder and state management logic.
- **tts/**: Text-To-Speech engine management.
    - **TTSManager.kt**: Wrapper around Android's `TextToSpeech` engine, handling initialization, language setting, and reading commands.
- **utils/**: Utility classes.
    - **DebugLogger.kt**: centralized logging utility.
    - **AuthenticatedGlideUrl.kt**: Custom Glide loader for images requiring headers (cookies/user-agent).

## Key Files
- **AndroidManifest.xml**: Defines app permissions (Internet, Foreground Service, Notifications) and activities.
- **build.gradle.kts (app)**: Dependencies include Retrofit, Glide, Coroutines, Media, and standard AndroidX libraries.
