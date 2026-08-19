# Plan: Audit Fix + TTS Engine Overhaul + Settings UX + Background Playback

Created: 2026-08-19 13:35  
Status: 🟡 In Progress

## Overview

Comprehensive plan addressing:
1. **3 Critical Issues** from performance audit (memory, GC pressure, CPU waste)
2. **6 Warning Issues** from performance audit (EOS brute-force, AudioTrack lifecycle, PCM copies, boxing, redundant anchor)
3. **Voice switching bug** — Settings voice change has no effect on TTS output
4. **Dual TTS engine support** — Let users choose between VieNeu (offline) and Google TTS (online)
5. **Settings UI/UX overhaul** — Modern, grouped, premium settings experience
6. **Story switching** — Stop current narration immediately when user opens a different story
7. **Background playback** — Ensure TTS continues when screen is off (WakeLock + foreground service hardening)

## Tech Stack
- Language: Kotlin
- Platform: Android (API 26+)
- TTS: VieNeu ONNX Engine (offline) + Android TextToSpeech (Google TTS, online)
- Build: Gradle Kotlin DSL

## Phases

| Phase | Name | Status | Progress |
|-------|------|--------|----------|
| 01 | Critical Audit Fixes (C1, C2, C3) | ⬜ Pending | 0% |
| 02 | Warning Audit Fixes (W1–W6) | ⬜ Pending | 0% |
| 03 | Voice Switching Bug Fix | ⬜ Pending | 0% |
| 04 | Dual TTS Engine (VieNeu / Google TTS) | ⬜ Pending | 0% |
| 05 | Settings UI/UX Overhaul | ⬜ Pending | 0% |
| 06 | Story Switching (Stop & Play New) | ⬜ Pending | 0% |
| 07 | Background Playback (Screen-Off) | ⬜ Pending | 0% |

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
