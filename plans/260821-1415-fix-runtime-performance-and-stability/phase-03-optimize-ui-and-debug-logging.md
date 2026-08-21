# Phase 03: Optimize UI Rendering, Image Spam & LiveData Garbage Collection
Status: 🟢 Completed
Dependencies: phase-02-optimize-acoustic-engine-and-npz-io.md

## Objective
Eliminate UI thread stutter, main thread parsing lag, and heap GC churn across UI and utility layers:
1. Prevent `DebugLogger.kt` from allocating new 500-element `ArrayList` objects into `_logs` when no active UI observer is open.
2. Eliminate redundant double HTML parsing (`Html.fromHtml()`) on the Main thread in `StoryDetailActivity.kt`.
3. Enable `setHasFixedSize(true)` on `MainActivity.kt`'s RecyclerView and remove/guard image load logging in `StoryAdapter.kt`.

## Requirements

### Functional Requirements
- [x] **Observer-Guarded LiveData Posting in DebugLogger**: In `DebugLogger.log()` and `DebugLogger.clear()`, only post a new `ArrayList(logBuffer)` copy to `_logs` if `_logs.hasActiveObservers()` is `true`. Maintain in-memory `logBuffer` and `getFormattedLogs()` capability regardless of UI observation.
- [x] **Single-Pass HTML Parsing**: In `StoryDetailActivity.kt` (lines 330-337), parse sanitized HTML once using `Html.fromHtml(sanitizedContent, Html.FROM_HTML_MODE_LEGACY)` into `val spannedContent = ...`. Reuse `spannedContent` for both plain-text extraction (`storyContent = spannedContent.toString()`) and TextView rendering (`tvContent.text = spannedContent`).
- [x] **RecyclerView Layout Optimization**: In `MainActivity.kt` (`setupRecyclerView()`), invoke `recyclerView.setHasFixedSize(true)` to skip redundant layout measurements when scrolling the 2-column grid.
- [x] **Spam-Free Adapter Binding**: In `StoryAdapter.kt`, remove or guard the verbose `DebugLogger.log("IMAGE", ...)` inside `onBindViewHolder()` / `bind()` to prevent hundreds of allocations during fast list scrolling.

### Non-Functional Requirements
- [x] Eliminate ~100-300ms Main thread frame drops when loading long novel chapters.
- [x] Reduce Garbage Collector pause frequency (GC churn) by avoiding continuous 500-element list cloning on background events.

## Implementation Steps
1. In `app/src/main/java/com/skul9x/doctruyen/utils/DebugLogger.kt`:
   - In `log()`:
     ```kotlin
     synchronized(logBuffer) {
         if (logBuffer.size >= MAX_LOG_ENTRIES) {
             logBuffer.removeFirst()
         }
         logBuffer.addLast(entry)
         if (_logs.hasActiveObservers()) {
             _logs.postValue(ArrayList(logBuffer))
         }
     }
     ```
   - In `clear()`:
     ```kotlin
     synchronized(logBuffer) {
         logBuffer.clear()
         if (_logs.hasActiveObservers()) {
             _logs.postValue(emptyList())
         }
     }
     ```
2. In `app/src/main/java/com/skul9x/doctruyen/StoryDetailActivity.kt`:
   - Replace duplicate `Html.fromHtml()` calls in lines 330-337:
     ```kotlin
     val spannedContent = android.text.Html.fromHtml(sanitizedContent, android.text.Html.FROM_HTML_MODE_LEGACY)
     storyContent = spannedContent.toString()
     loadedStoryId = storyId

     val preview = storyContent.take(50) + if (storyContent.length > 50) "..." else ""
     DebugLogger.log(TAG, "✅ Content loaded: ${storyContent.length} chars, preview: '$preview'", LogType.RESPONSE)

     // Set text with HTML rendering
     tvContent.text = spannedContent
     ```
3. In `app/src/main/java/com/skul9x/doctruyen/MainActivity.kt`:
   - In `setupRecyclerView()`, add `recyclerView.setHasFixedSize(true)`.
4. In `app/src/main/java/com/skul9x/doctruyen/adapter/StoryAdapter.kt`:
   - Remove or guard `DebugLogger.log("IMAGE", "Loading: ...")` in `bind()`.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/utils/DebugLogger.kt` — Add active observer check before posting.
- `app/src/main/java/com/skul9x/doctruyen/StoryDetailActivity.kt` — Single-pass HTML parsing.
- `app/src/main/java/com/skul9x/doctruyen/MainActivity.kt` — Add `setHasFixedSize(true)`.
- `app/src/main/java/com/skul9x/doctruyen/adapter/StoryAdapter.kt` — Clean up bind log spam.
- `app/src/test/java/com/skul9x/doctruyen/ui/UiAndLoggingOptimizationTest.kt` — [NEW] Single comprehensive test file.

## Test Criteria (Single Test File)
**File:** `app/src/test/java/com/skul9x/doctruyen/ui/UiAndLoggingOptimizationTest.kt`
- [x] **Test 1**: Verify `DebugLogger.log()` adds entries to internal buffer but skips posting to `_logs` when no observers are active, and posts when observers are attached.
- [x] **Test 2**: Verify `DebugLogger.clear()` properly flushes buffer without crashing or leaking state.
- [x] **Test 3**: Verify single-pass HTML conversion produces identical text content and retains spannable styling attributes for novel content.

## Verification Command
```bash
./gradlew test --tests "com.skul9x.doctruyen.ui.UiAndLoggingOptimizationTest"
```

---
Plan Complete. Return to: [plan.md](./plan.md)
