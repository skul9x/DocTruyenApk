# Phase 01: Bounded Memory DebugLogger Optimization

Status: ✅ Complete  
Dependencies: None

## Objective

Fix the unbounded memory growth in `DebugLogger.kt`. Replace the unbounded `mutableListOf<LogEntry>` with a thread-safe, bounded FIFO structure (capacity limit: 500 entries) and optimize LiveData posting to eliminate unnecessary heap allocations and garbage collection churn during continuous, hours-long TTS playback.

---

## Requirements

### 1. Functional Requirements
- **Configurable Max Log Capacity (`MAX_LOG_ENTRIES`):** Set default capacity to `500` entries.
- **FIFO Eviction:** When the log count reaches `MAX_LOG_ENTRIES`, inserting a new `LogEntry` automatically drops the oldest entry in O(1) time.
- **Thread Safety:** Ensure all append, clear, filter, and format operations are safely synchronized across multiple threads (TTS coroutines, network interceptor threads, UI main thread).
- **Snapshot Efficiency:** Avoid deep array cloning on every log insert. Post an unmodifiable snapshot list to LiveData observers.
- **Backwards Compatibility:** Maintain existing public API: `log(tag, message, type)`, `logError(tag, message, e)`, `clear()`, `getFormattedLogs(filterType)`, and LiveData `logs`.

### 2. Non-Functional Requirements
- **Memory Consumption:** Memory footprint of `DebugLogger` must remain flat and bounded regardless of whether 500 or 500,000 logs are emitted.
- **Performance:** Appending a log entry must execute in O(1) time with zero noticeable UI latency.

---

## Implementation Steps

1. [x] Open `app/src/main/java/com/skul9x/doctruyen/utils/DebugLogger.kt`.
2. [x] Define constant `const val MAX_LOG_ENTRIES = 500`.
3. [x] Replace `private val logList = mutableListOf<LogEntry>()` with an `ArrayDeque<LogEntry>(MAX_LOG_ENTRIES)`.
4. [x] In `log(tag, message, type)`:
   - Synchronize on the buffer lock.
   - If buffer size >= `MAX_LOG_ENTRIES`, remove the oldest entry (`removeFirst()`).
   - Add the new entry (`addLast()`).
   - Post an unmodifiable snapshot list to `_logs.postValue(ArrayList(logBuffer))`.
5. [x] In `clear()`:
   - Clear buffer within synchronized block and post `emptyList()`.
6. [x] In `getFormattedLogs(filterType)`:
   - Iterate safely inside synchronized block to construct formatted output.
7. [x] Add test cases to the unified test file `app/src/test/java/com/skul9x/doctruyen/AuditFixesCombinedTest.kt`.

---

## Files to Create / Modify
- [MODIFY] `app/src/main/java/com/skul9x/doctruyen/utils/DebugLogger.kt` — Implement bounded FIFO capacity and synchronization.
- [NEW] `app/src/test/java/com/skul9x/doctruyen/AuditFixesCombinedTest.kt` — Unified test suite (contains `testDebugLogger_boundedCapacityAndFifoEviction`).

---

## Unified Test Criteria (Part 1 - DebugLogger)

Target test method in `app/src/test/java/com/skul9x/doctruyen/AuditFixesCombinedTest.kt`:

1. **`testDebugLogger_boundedCapacityAndFifoEviction`:**
   - Emit 1,500 logs sequentially.
   - Assert entries count never exceeds 500.
   - Assert entry #1,001 is present and older entries #1 to #1,000 are evicted in exact FIFO order.
   - Test `clear()` operation empties buffer and resets LiveData.
   - Test concurrent logging from 10 threads (1,000 total writes) completes without race conditions or `ConcurrentModificationException`.
   - Test `getFormattedLogs(LogType.ERROR)` correctly filters entries.

---
Next Phase: [Phase 02 — Activity Transitions & Back Navigation](./phase-02-activity-transitions-and-back-dispatcher.md)
