# Handover Document: Release v1.18 Shipped (Full Performance Remediation Phases 01-04)

**Date:** 2026-11-20 14:30  
**Project:** DocTruyen (VieNeu-TTS v3 Turbo On-Device Neural Reader)  
**Status:** Shipped - Release v1.18 Published on GitHub (All 10 Performance Bottlenecks Remediated, 100% CI/CD Pass)

---

## 📍 Summary of Latest Work (Release v1.18 Deployment)
1. **Remediation Completed (Phases 01 - 04):**
   - `PERF-001` (Inference Cancellation) & `PERF-005` (Audio Pre-buffering Stability)
   - `PERF-003` (Flash Storage Asset Whitelist) & `PERF-004` (NpzReader Direct Streaming)
   - `PERF-002` (Matrix Projection 16-unroll GEMV) & `PERF-007` (Single-Threaded Acoustic Session & Persistent Position Tensors)
   - `PERF-006` (Debounced Pre-warming) & `PERF-008` (Vietnamese Syllable Cache 2048)
   - `PERF-009` (Zero Glide Scroll Allocations & DebugLogger Gate) & `PERF-010` (Targeted arm64-v8a Release ABI Filter)

2. **Git & Release Pipeline Execution:**
   - Changes committed via commit `a14d9db`: `perf: complete full performance remediation across phases 01-04 (PERF-001 to PERF-010)`
   - Safely pushed to `origin/main` without force push.
   - GitHub Actions workflow run `44669496938` (`.github/workflows/android.yml`) completed successfully with 100% pass rate.
   - GitHub Release `v1.18` automatically created and published:
     - Tag: `v1.18`
     - Asset: `doctruyenapk-v1.18.apk` (Size: 502.71 MB, signed release APK).
     - Link: https://github.com/skul9x/DocTruyenApk/releases/tag/v1.18
   - Local repository synchronized with remote tags (`git fetch origin --tags`).

3. **Current Codebase State:**
   - Working tree: clean (`nothing to commit, working tree clean`).
   - Branch: `main` in sync with `origin/main`.
   - All 314+ unit tests passing 100%.

---

## 🔧 Key Technical Decisions & Architecture State
- **Matrix GEMV Acceleration:** Chose 16-way unrolling with 4 independent accumulators on Kotlin ART instead of Rust JNI to eliminate 2,880 JNI boundary crossings per sentence while maintaining exact IEEE 754 arithmetic equivalence (< 10^-5).
- **Acoustic Session:** Dedicated `intra_op_num_threads = 1` session options avoiding multi-core barrier latency for 1-token inputs.
- **Syllable Caching:** Granular 2048-entry LRU syllable cache in `SeaG2P` yielding > 85% hit rate in prose.
- **Packaging:** Targeted `arm64-v8a` ABI filter in release build reducing APK size by ~10.5MB, retaining `x86_64` for debug emulators.
- **Flash Memory:** Selective extraction whitelist in `OnnxSessionManager` avoiding extraction of 52MB `.npz` and `.json` files to internal flash storage.

---

## 📁 Key Files
- `plans/261120-0915-full-performance-remediation/plan.md` (Remediation Master Plan)
- `CHANGELOG.md` (v1.18 Release Notes)
- `abc.md` (Audit Baseline Report)
- `.brain/brain.json` (Knowledge Base)
- `.brain/session.json` (Session State)

---

## ➡️ Next Steps for Next Session
1. Gõ `/recap` để khôi phục toàn bộ ngữ cảnh dự án.
2. Nếu cần tính năng mới: Gõ `/plan` để lên kế hoạch.
