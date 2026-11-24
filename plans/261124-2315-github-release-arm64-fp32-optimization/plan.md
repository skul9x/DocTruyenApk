# Plan: GitHub Release FP32 & 64-Bit ARM APK Optimization
Created: 2026-11-24
Status: ✅ Completed

## Overview
Optimize the build and release pipeline to ensure that the release APK published on GitHub Releases mirrors the lightweight footprint (~250-254 MB) of the local debug build, packaging exclusively the unquantized FP32 neural speech models and targeting only 64-bit ARM architectures (`arm64-v8a`).

1. **Phase 01:** Gradle Architecture & Packaging Alignment (Target exclusively `arm64-v8a`, eliminate multi-ABI splits and universal APK bloat, configure single unified APK output).
2. **Phase 02:** GitHub Actions CI/CD Workflow Streamlining & FP32 Release Alignment (Remove runtime INT8 quantization pass and dependencies, package and publish single lightweight ~254 MB release APK).

## Architecture & Tech Stack
- **Target Platform:** Android (Min SDK 24, Target SDK 35, Min Architecture: 64-bit ARM `arm64-v8a`)
- **Build System:** Gradle Kotlin DSL (`build.gradle.kts`, AGP 8.7.2, Kotlin 2.0.21)
- **CI/CD:** GitHub Actions (`.github/workflows/android.yml`, Ubuntu Latest, Temurin JDK 17)
- **Neural TTS Assets:** VieNeu ONNX FP32 Backbone (`vieneu/backbone`), SeaG2P Dictionary (`sea_g2p.bin`)

## Phases

| Phase | Name | Status | Progress |
|-------|------|--------|----------|
| 01 | Gradle Architecture & Packaging Alignment (arm64-v8a only) | ✅ Completed | 100% |
| 02 | GitHub Actions CI/CD Workflow Streamlining (FP32 release) | ✅ Completed | 100% |

## Guidelines & Constraints
- Define WHAT to accomplish, allowing the coding agent to determine the optimal implementation based on the existing architecture and best practices.
- Each phase must contain exactly one comprehensive file-based test.
- Maintain full runtime stability and ensure the app operates seamlessly with FP32 models on 64-bit devices.
