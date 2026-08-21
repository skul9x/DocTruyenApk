# Plan: Fix INT8 Precision Detection & Cold-Start Initialization
Created: 2026-08-21
Status: 🟡 In Progress

## Overview
Fix three bugs causing TTS engine to always report FP32 quantization even when user selects INT8 precision.

## Phases

| Phase | Name | Status | Progress |
|-------|------|--------|----------|
| 01 | Fix `detectQuantizationType()` path checking | ⬜ Pending | 0% |
| 02 | Fix cold-start `initManager()` precision pass-through | ✅ Completed | 100% |
| 03 | Add explicit fallback logging for missing INT8 assets | ✅ Completed | 100% |

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
