# Phase 01: Device Hardware & Telemetry Profiler

Status: ✅ Completed
Dependencies: None

## Objective
Implement `DevicePerformanceHelper.kt` to capture device profile, hardware specifications (SoC, core count, RAM), runtime memory footprint (Native Heap allocated/free, total/available RAM), and thermal profile (battery temperature, thermal throttling status).

## Requirements
### Functional
- [x] Implement `DevicePerformanceHelper.getDeviceProfile(context: Context?, engineThreads: Int, quantization: String): DeviceProfile`.
  - Capture device model, manufacturer, board/hardware/SoC name (using `Build.SOC_MODEL` on API 31+ or fallback to `Build.HARDWARE`/`Build.BOARD`), CPU cores count (`Runtime.getRuntime().availableProcessors()`), and free RAM.
- [x] Implement `DevicePerformanceHelper.getMemorySnapshot(context: Context?): MemorySnapshot`.
  - Capture Native Heap allocated size (`Debug.getNativeHeapAllocatedSize()`), Native Heap total/free size, and JVM heap usage.
- [x] Implement `DevicePerformanceHelper.getThermalSnapshot(context: Context?): ThermalSnapshot`.
  - Read battery temperature from sticky battery broadcast (`BatteryManager.EXTRA_TEMPERATURE` in °C).
  - Read thermal throttling status using `PowerManager.currentThermalStatus` on API 29+ (mapping to `NONE`, `LIGHT`, `MODERATE`, `SEVERE`, `CRITICAL`, `EMERGENCY`, `SHUTDOWN`).
- [x] Provide helper calculation methods for deltas: `calculateMemoryDelta(start, end)` and `calculateThermalDelta(start, end)`.

### Non-Functional
- [x] Safe execution on JVM test environments (mock/fallback when Android Context or native Android methods return defaults).
- [x] Zero performance overhead (lightweight system property and sysfs/PowerManager reads).

## Implementation Steps
1. Create `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/DevicePerformanceHelper.kt`:
   - Define data classes: `DeviceProfile`, `MemorySnapshot`, `ThermalSnapshot`, `MemoryDelta`, `ThermalDelta`.
   - Implement telemetry capture functions with fallback for unit test and older Android versions.
2. Create unit test: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/DevicePerformanceHelperTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/DevicePerformanceHelper.kt` - Hardware and telemetry data collection helper.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/DevicePerformanceHelperTest.kt` - Single test verifying device profile, memory delta, and thermal status extraction.

## Test Criteria
- Exactly one test: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/DevicePerformanceHelperTest.kt`
- Verifies `DeviceProfile` extraction with core count, RAM info, and engine parameters.
- Verifies `MemorySnapshot` and `MemoryDelta` calculations (allocated MB, delta MB, leak detection heuristics).
- Verifies `ThermalSnapshot` and `ThermalDelta` parsing (temperature delta °C and throttling status string).

---
Next Phase: [Phase 02: Stage-by-Stage Latency Breakdown in Engine & Pipeline](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260820-1205-ai-self-diagnostic-logging/phase-02-stage-latency-breakdown.md)
