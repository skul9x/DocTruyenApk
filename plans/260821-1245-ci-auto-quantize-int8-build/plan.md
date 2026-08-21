# Plan: CI/CD Automated INT8 Quantization & Dual-Backbone APK Build

Created: 2026-08-21T12:45:00+07:00
Status: 🟡 In Progress

## Overview
Implement automated ONNX model dynamic INT8 quantization inside the GitHub Actions CI/CD pipeline (`.github/workflows/android.yml`). This enables the release APK to package both FP32 (`backbone/`) and INT8 (`backbone_int8/`) model assets without committing large binary assets (>100MB) directly to the Git repository, keeping the Git repo lightweight while delivering full offline INT8 TTS capabilities in published release APKs.

## Objectives
1. Add Python setup and ONNX quantization step to GitHub Actions CI workflow prior to `:app:assembleRelease`.
2. Generate dynamic INT8 quantized models (`vieneu_prefill.onnx`, `vieneu_decode_step.onnx`, `vieneu_acoustic_cached.onnx`) directly into `app/src/main/assets/vieneu/backbone_int8/`.
3. Verify asset packaging, extraction, and runtime selection in `OnnxSessionManager` when both `backbone` and `backbone_int8` are packaged together.
4. Validate that GitHub Release APK assets contain both precision models seamlessly.

## Phases

| Phase | Name | Description | Status | Test File |
|-------|------|-------------|--------|-----------|
| 01 | CI Workflow Quantization Step | Integrate Python setup, dependencies, and model quantization in GitHub Actions workflow | ⬜ Pending | `WorkflowQuantizationConfigTest.kt` |
| 02 | Dual-Asset Packaging & Runtime Resolution | Validate dual asset coexistence, extraction, and INT8 priority in engine runtime | ⬜ Pending | `DualAssetExtractionAndDetectionTest.kt` |

## Quick Commands
- Start Phase 1: Implement `phase-01-ci-workflow-quantization.md`
- Check progress: Review phase plans in `plans/260821-1245-ci-auto-quantize-int8-build/`
