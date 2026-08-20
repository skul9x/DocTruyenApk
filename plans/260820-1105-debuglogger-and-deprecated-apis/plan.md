# Plan: Bounded Memory DebugLogger & Modern Deprecated APIs Migration

Created: 2026-08-20 11:05 (Updated with Unified Single Test Suite)
Status: 🟡 In Progress

## Overview

This plan resolves the unbounded memory growth in `DebugLogger` and eliminates deprecated Android platform API usages identified during the system audit. All fixes are verified through **a single consolidated test file** (`AuditFixesCombinedTest.kt`) so everything can be tested and validated in a single run without executing disparate test files.

## Tech Stack & Target Platforms
- Platform: Android (Min SDK 24, Target SDK 35, Compile SDK 35)
- Language: Kotlin 2.0.21
- Architecture: MVVM + Foreground Service + Reactive LiveData
- Unified Test: `app/src/test/java/com/skul9x/doctruyen/AuditFixesCombinedTest.kt`
- Key APIs: AndroidX Activity 1.9.3 (OnBackPressedDispatcher), MediaSessionCompat, WebViewClient, ArrayDeque bounded memory queue

---

## Phases

| Phase | Name | Scope & Key Objectives | Status | Progress |
|:-----:|:-----|:-----------------------|:------:|:--------:|
| **01** | [Bounded Memory DebugLogger](./phase-01-bounded-debuglogger.md) | Enforce fixed-capacity FIFO eviction (MAX 500 entries) in `DebugLogger` to prevent memory leaks and GC allocation churn | ⬜ Pending | 0% |
| **02** | [Activity Transitions & Back Navigation](./phase-02-activity-transitions-and-back-dispatcher.md) | Migrate `overridePendingTransition` to Android 14+ `overrideActivityTransition` and replace deprecated `onBackPressed()` with `OnBackPressedCallback` | ⬜ Pending | 0% |
| **03** | [MediaSession & WebView Deprecations](./phase-03-mediasession-and-webview-deprecations.md) | Clean up legacy `MediaSessionCompat.setFlags` and update `WebViewClient.onReceivedError` to modern `WebResourceError` API | ⬜ Pending | 0% |
| **04** | [Single Unified Test Verification](./phase-04-verification-and-test-suite.md) | Execute the single combined test file (`./gradlew testDebugUnitTest --tests "*AuditFixesCombinedTest"`) and verify 100% pass rate | ⬜ Pending | 0% |

---

## Single Test Command
Run all verifications with one command:
```bash
./gradlew testDebugUnitTest --tests "com.skul9x.doctruyen.AuditFixesCombinedTest"
```

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
