package com.skul9x.doctruyen.tts.vieneu

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import java.util.Locale

/**
 * Data class representing static and semi-static device profile info.
 */
data class DeviceProfile(
    val model: String = "Unknown",
    val manufacturer: String = "Unknown",
    val socModel: String = "Unknown",
    val cpuCores: Int = 1,
    val totalRamMb: Long = 0L,
    val availableRamMb: Long = 0L,
    val engineThreads: Int = 1,
    val quantization: String = "Unknown"
)

/**
 * Data class representing a snapshot of memory usage at a specific point in time.
 */
data class MemorySnapshot(
    val timestampMs: Long = System.currentTimeMillis(),
    val nativeHeapAllocatedMb: Float = 0f,
    val nativeHeapTotalMb: Float = 0f,
    val nativeHeapFreeMb: Float = 0f,
    val jvmHeapUsedMb: Float = 0f,
    val jvmHeapMaxMb: Float = 0f,
    val systemAvailableRamMb: Long = 0L
)

/**
 * Data class representing memory differences between two snapshots.
 */
data class MemoryDelta(
    val nativeHeapDeltaMb: Float = 0f,
    val jvmHeapDeltaMb: Float = 0f,
    val systemRamDeltaMb: Long = 0L,
    val isPotentialLeak: Boolean = false
)

/**
 * Data class representing thermal and battery status at a specific point in time.
 */
data class ThermalSnapshot(
    val timestampMs: Long = System.currentTimeMillis(),
    val batteryTempCelsius: Float = 0f,
    val thermalThrottlingStatus: String = "NONE"
)

/**
 * Data class representing thermal differences between two snapshots.
 */
data class ThermalDelta(
    val tempDeltaCelsius: Float = 0f,
    val startThrottlingStatus: String = "NONE",
    val endThrottlingStatus: String = "NONE",
    val isThrottled: Boolean = false
)

/**
 * Helper object for capturing hardware specifications, memory footprint, and thermal telemetry.
 */
object DevicePerformanceHelper {

    fun getDeviceProfile(
        context: Context?,
        engineThreads: Int = 1,
        quantization: String = "Unknown"
    ): DeviceProfile {
        val model = try { Build.MODEL ?: "Unknown" } catch (_: Throwable) { "Unknown" }
        val manufacturer = try { Build.MANUFACTURER ?: "Unknown" } catch (_: Throwable) { "Unknown" }
        
        val soc = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL.takeIf { !it.isNullOrBlank() && it != Build.UNKNOWN }
                    ?: Build.HARDWARE.takeIf { !it.isNullOrBlank() && it != Build.UNKNOWN }
                    ?: Build.BOARD.takeIf { !it.isNullOrBlank() && it != Build.UNKNOWN }
                    ?: "Unknown"
            } else {
                Build.HARDWARE.takeIf { !it.isNullOrBlank() && it != Build.UNKNOWN }
                    ?: Build.BOARD.takeIf { !it.isNullOrBlank() && it != Build.UNKNOWN }
                    ?: "Unknown"
            }
        } catch (_: Throwable) {
            "Unknown"
        }

        val cpuCores = try {
            Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        } catch (_: Throwable) {
            1
        }

        var totalRamMb = 0L
        var availRamMb = 0L
        if (context != null) {
            try {
                val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (actManager != null) {
                    val memInfo = ActivityManager.MemoryInfo()
                    actManager.getMemoryInfo(memInfo)
                    totalRamMb = memInfo.totalMem / (1024 * 1024)
                    availRamMb = memInfo.availMem / (1024 * 1024)
                }
            } catch (_: Throwable) {
                // Fallback / ignore in test environments
            }
        }

        if (totalRamMb == 0L) {
            val maxJvm = Runtime.getRuntime().maxMemory()
            if (maxJvm > 0 && maxJvm != Long.MAX_VALUE) {
                totalRamMb = maxJvm / (1024 * 1024)
            }
        }

        return DeviceProfile(
            model = model,
            manufacturer = manufacturer,
            socModel = soc,
            cpuCores = cpuCores,
            totalRamMb = totalRamMb,
            availableRamMb = availRamMb,
            engineThreads = engineThreads,
            quantization = quantization
        )
    }

    fun getMemorySnapshot(context: Context?): MemorySnapshot {
        val bytesToMb = 1024f * 1024f
        
        var nativeAllocated = 0f
        var nativeTotal = 0f
        var nativeFree = 0f
        try {
            nativeAllocated = Debug.getNativeHeapAllocatedSize() / bytesToMb
            nativeTotal = Debug.getNativeHeapSize() / bytesToMb
            nativeFree = Debug.getNativeHeapFreeSize() / bytesToMb
        } catch (_: Throwable) {
            // Android Debug class may return dummy/throw in mock environment
        }

        val runtime = Runtime.getRuntime()
        val totalJvm = runtime.totalMemory()
        val freeJvm = runtime.freeMemory()
        val maxJvm = runtime.maxMemory()
        val usedJvm = (totalJvm - freeJvm).coerceAtLeast(0) / bytesToMb
        val maxJvmMb = if (maxJvm == Long.MAX_VALUE) 0f else maxJvm / bytesToMb

        var sysAvailMb = 0L
        if (context != null) {
            try {
                val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (actManager != null) {
                    val memInfo = ActivityManager.MemoryInfo()
                    actManager.getMemoryInfo(memInfo)
                    sysAvailMb = memInfo.availMem / (1024 * 1024)
                }
            } catch (_: Throwable) {
                // Fallback
            }
        }

        return MemorySnapshot(
            timestampMs = System.currentTimeMillis(),
            nativeHeapAllocatedMb = nativeAllocated,
            nativeHeapTotalMb = nativeTotal,
            nativeHeapFreeMb = nativeFree,
            jvmHeapUsedMb = usedJvm,
            jvmHeapMaxMb = maxJvmMb,
            systemAvailableRamMb = sysAvailMb
        )
    }

    fun getThermalSnapshot(context: Context?): ThermalSnapshot {
        var tempCelsius = 0f
        var throttlingStatus = "NONE"

        if (context != null) {
            try {
                val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val batteryStatus = context.registerReceiver(null, intentFilter)
                if (batteryStatus != null) {
                    val tempTenths = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                    if (tempTenths > 0) {
                        tempCelsius = tempTenths / 10f
                    }
                }
            } catch (_: Throwable) {
                // Fallback
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (powerManager != null) {
                        throttlingStatus = mapThermalStatus(powerManager.currentThermalStatus)
                    }
                }
            } catch (_: Throwable) {
                // Fallback
            }
        }

        return ThermalSnapshot(
            timestampMs = System.currentTimeMillis(),
            batteryTempCelsius = tempCelsius,
            thermalThrottlingStatus = throttlingStatus
        )
    }

    fun mapThermalStatus(status: Int): String {
        return when (status) {
            0 -> "NONE" // PowerManager.THERMAL_STATUS_NONE
            1 -> "LIGHT" // PowerManager.THERMAL_STATUS_LIGHT
            2 -> "MODERATE" // PowerManager.THERMAL_STATUS_MODERATE
            3 -> "SEVERE" // PowerManager.THERMAL_STATUS_SEVERE
            4 -> "CRITICAL" // PowerManager.THERMAL_STATUS_CRITICAL
            5 -> "EMERGENCY" // PowerManager.THERMAL_STATUS_EMERGENCY
            6 -> "SHUTDOWN" // PowerManager.THERMAL_STATUS_SHUTDOWN
            else -> "UNKNOWN"
        }
    }

    fun calculateMemoryDelta(start: MemorySnapshot, end: MemorySnapshot): MemoryDelta {
        val nativeDelta = end.nativeHeapAllocatedMb - start.nativeHeapAllocatedMb
        val jvmDelta = end.jvmHeapUsedMb - start.jvmHeapUsedMb
        val sysDelta = end.systemAvailableRamMb - start.systemAvailableRamMb
        
        // Potential leak heuristic: substantial native growth (> 50MB) or JVM growth (> 50MB)
        val isPotentialLeak = nativeDelta > 50f || jvmDelta > 50f

        return MemoryDelta(
            nativeHeapDeltaMb = nativeDelta,
            jvmHeapDeltaMb = jvmDelta,
            systemRamDeltaMb = sysDelta,
            isPotentialLeak = isPotentialLeak
        )
    }

    fun calculateThermalDelta(start: ThermalSnapshot, end: ThermalSnapshot): ThermalDelta {
        val tempDelta = end.batteryTempCelsius - start.batteryTempCelsius
        val throttledStatuses = setOf("MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")
        val isThrottled = throttledStatuses.contains(end.thermalThrottlingStatus.uppercase(Locale.US))

        return ThermalDelta(
            tempDeltaCelsius = tempDelta,
            startThrottlingStatus = start.thermalThrottlingStatus,
            endThrottlingStatus = end.thermalThrottlingStatus,
            isThrottled = isThrottled
        )
    }
}
