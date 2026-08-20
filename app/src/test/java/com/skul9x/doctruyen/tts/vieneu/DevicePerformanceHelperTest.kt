package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePerformanceHelperTest {

    @Test
    fun testDeviceProfileExtraction() {
        val profile = DevicePerformanceHelper.getDeviceProfile(
            context = null,
            engineThreads = 4,
            quantization = "q4_k_m"
        )
        assertNotNull(profile)
        assertEquals(4, profile.engineThreads)
        assertEquals("q4_k_m", profile.quantization)
        assertTrue(profile.cpuCores >= 1)
        assertNotNull(profile.model)
        assertNotNull(profile.manufacturer)
        assertNotNull(profile.socModel)
    }

    @Test
    fun testMemorySnapshotAndDeltaCalculation() {
        val startSnapshot = MemorySnapshot(
            timestampMs = 1000L,
            nativeHeapAllocatedMb = 120.5f,
            nativeHeapTotalMb = 150.0f,
            nativeHeapFreeMb = 29.5f,
            jvmHeapUsedMb = 45.0f,
            jvmHeapMaxMb = 256.0f,
            systemAvailableRamMb = 2048L
        )

        val endSnapshotNormal = MemorySnapshot(
            timestampMs = 5000L,
            nativeHeapAllocatedMb = 135.5f, // +15MB
            nativeHeapTotalMb = 160.0f,
            nativeHeapFreeMb = 24.5f,
            jvmHeapUsedMb = 50.0f, // +5MB
            jvmHeapMaxMb = 256.0f,
            systemAvailableRamMb = 2000L
        )

        val deltaNormal = DevicePerformanceHelper.calculateMemoryDelta(startSnapshot, endSnapshotNormal)
        assertEquals(15.0f, deltaNormal.nativeHeapDeltaMb, 0.01f)
        assertEquals(5.0f, deltaNormal.jvmHeapDeltaMb, 0.01f)
        assertEquals(-48L, deltaNormal.systemRamDeltaMb)
        assertFalse(deltaNormal.isPotentialLeak)

        val endSnapshotLeaking = MemorySnapshot(
            timestampMs = 10000L,
            nativeHeapAllocatedMb = 190.5f, // +70MB (exceeds 50MB heuristic)
            nativeHeapTotalMb = 220.0f,
            nativeHeapFreeMb = 29.5f,
            jvmHeapUsedMb = 60.0f,
            jvmHeapMaxMb = 256.0f,
            systemAvailableRamMb = 1900L
        )

        val deltaLeaking = DevicePerformanceHelper.calculateMemoryDelta(startSnapshot, endSnapshotLeaking)
        assertEquals(70.0f, deltaLeaking.nativeHeapDeltaMb, 0.01f)
        assertTrue(deltaLeaking.isPotentialLeak)
    }

    @Test
    fun testThermalSnapshotAndDeltaCalculation() {
        assertEquals("NONE", DevicePerformanceHelper.mapThermalStatus(0))
        assertEquals("LIGHT", DevicePerformanceHelper.mapThermalStatus(1))
        assertEquals("MODERATE", DevicePerformanceHelper.mapThermalStatus(2))
        assertEquals("SEVERE", DevicePerformanceHelper.mapThermalStatus(3))
        assertEquals("CRITICAL", DevicePerformanceHelper.mapThermalStatus(4))
        assertEquals("EMERGENCY", DevicePerformanceHelper.mapThermalStatus(5))
        assertEquals("SHUTDOWN", DevicePerformanceHelper.mapThermalStatus(6))
        assertEquals("UNKNOWN", DevicePerformanceHelper.mapThermalStatus(99))

        val startThermal = ThermalSnapshot(
            timestampMs = 1000L,
            batteryTempCelsius = 35.5f,
            thermalThrottlingStatus = "NONE"
        )

        val endThermalNormal = ThermalSnapshot(
            timestampMs = 30000L,
            batteryTempCelsius = 38.0f,
            thermalThrottlingStatus = "LIGHT"
        )

        val deltaNormal = DevicePerformanceHelper.calculateThermalDelta(startThermal, endThermalNormal)
        assertEquals(2.5f, deltaNormal.tempDeltaCelsius, 0.01f)
        assertEquals("NONE", deltaNormal.startThrottlingStatus)
        assertEquals("LIGHT", deltaNormal.endThrottlingStatus)
        assertFalse(deltaNormal.isThrottled)

        val endThermalThrottled = ThermalSnapshot(
            timestampMs = 60000L,
            batteryTempCelsius = 43.5f,
            thermalThrottlingStatus = "SEVERE"
        )

        val deltaThrottled = DevicePerformanceHelper.calculateThermalDelta(startThermal, endThermalThrottled)
        assertEquals(8.0f, deltaThrottled.tempDeltaCelsius, 0.01f)
        assertEquals("SEVERE", deltaThrottled.endThrottlingStatus)
        assertTrue(deltaThrottled.isThrottled)
    }

    @Test
    fun testGetMemoryAndThermalSnapshotFallbackWithoutContext() {
        val mem = DevicePerformanceHelper.getMemorySnapshot(null)
        assertNotNull(mem)
        assertTrue(mem.timestampMs > 0)

        val thermal = DevicePerformanceHelper.getThermalSnapshot(null)
        assertNotNull(thermal)
        assertEquals("NONE", thermal.thermalThrottlingStatus)
    }
}
