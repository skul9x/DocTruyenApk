# Plan: Comprehensive R8 Minification, Retrofit & Network Architecture Fix (Fix All)
Created: 2026-11-20 19:15 (GMT+7)  
Status: ✅ Completed  
Feature: Fix Release v1.14 Runtime Crash (`ClassCastException` in `ParameterizedType`), Harden ProGuard/R8 Rules, Secure Data Serialization & Implement Defensive Network Architecture

---

## Overview
In Release v1.14, code shrinking (`isMinifyEnabled = true`) was enabled with `proguard-android-optimize.txt` (R8 Full Mode). This caused runtime reflection failures in Retrofit suspend functions (`java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType`) because generic signatures on `kotlin.coroutines.Continuation` and Retrofit interface methods were aggressively stripped.

This plan resolves the crash completely and hardens the entire network architecture:
1. Configures official Square Retrofit 2025/2026 R8 Full Mode keep rules for Coroutines `Continuation`, `Call`, `Response`, and `@retrofit2.http.*` interfaces.
2. Hardens network data models with `@androidx.annotation.Keep` and 100% coverage of `@SerializedName` annotations to prevent field-stripping or constructor optimization bugs.
3. Implements a centralized, resilient `NetworkErrorResolver` replacing raw Java exception messages (`ClassCastException`) with polite, user-friendly Vietnamese messages and automatic cookie re-verification.
4. Comprehensive unit test suite and release build regression validation.

---

## Tech Stack
- **Language & Runtime:** Kotlin 2.0 / Java 11 / Android SDK 35
- **Networking & Serialization:** Square Retrofit 2.9.0 + Gson 2.9.0 + OkHttp 4.12.0
- **Asynchronous Execution:** Kotlin Coroutines 1.7.3 (`Dispatchers.IO`, `Dispatchers.Main`)
- **Optimization & Obfuscation:** Android Gradle Plugin 8.7.2 + R8 / ProGuard
- **Testing:** JUnit 4 + MockK + Robolectric / Kotlin Test

---

## Phases

| Phase | Name | Description | Status | Progress |
|---|---|---|---|---|
| 01 | R8 & ProGuard Rules Hardening | Apply official Square Retrofit rules for Kotlin Coroutines Continuation and generic types | ✅ Completed | 100% |
| 02 | Data Model Serialization Hardening | Annotate all DTOs with `@Keep` and explicit `@SerializedName` on all fields | ✅ Completed | 100% |
| 03 | Defensive Network Error Architecture | Implement `NetworkErrorResolver`, polite user messages & automatic retry/auth | ✅ Completed | 100% |
| 04 | Release Build Verification & CI | Verify minified release build, unit tests, and CI/CD artifact readiness | ✅ Completed | 100% |

---

## Quick Commands
- Start Phase 01: `/code phase-01`
- Review Plan: `/plan`
- Check Status: `/recap`
- Next Step: `/next`

