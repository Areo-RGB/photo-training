package com.paul.sprintsync.sensor_native

import android.util.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorNativeMathTest {
    @Test
    fun `hs roi recording buffer keeps latest frames within capacity`() {
        val buffer = HsRoiRecordingBuffer(capacityFrames = 3)
        buffer.append(HsRecordedRoiFrame(100L, byteArrayOf(1, 2), sampleCount = 2))
        buffer.append(HsRecordedRoiFrame(110L, byteArrayOf(3, 4), sampleCount = 2))
        buffer.append(HsRecordedRoiFrame(120L, byteArrayOf(5, 6), sampleCount = 2))
        buffer.append(HsRecordedRoiFrame(130L, byteArrayOf(7, 8), sampleCount = 2))

        val snapshot = buffer.snapshot()
        assertEquals(3, snapshot.size)
        assertEquals(110L, snapshot[0].timestampNanos)
        assertEquals(120L, snapshot[1].timestampNanos)
        assertEquals(130L, snapshot[2].timestampNanos)
    }

    @Test
    fun `hs live analysis uses fixed stride of four`() {
        assertFalse(HsAnalysisPolicy.shouldAnalyzeLiveFrame(1))
        assertFalse(HsAnalysisPolicy.shouldAnalyzeLiveFrame(2))
        assertFalse(HsAnalysisPolicy.shouldAnalyzeLiveFrame(3))
        assertTrue(HsAnalysisPolicy.shouldAnalyzeLiveFrame(4))
        assertFalse(HsAnalysisPolicy.shouldAnalyzeLiveFrame(5))
        assertTrue(HsAnalysisPolicy.shouldAnalyzeLiveFrame(8))
    }

    @Test
    fun `post race refiner selects first threshold crossing inside window`() {
        val frames = listOf(
            HsRecordedRoiFrame(100L, byteArrayOf(10, 10, 10, 10), sampleCount = 4),
            HsRecordedRoiFrame(108L, byteArrayOf(10, 10, 10, 10), sampleCount = 4),
            HsRecordedRoiFrame(116L, byteArrayOf(30, 30, 30, 30), sampleCount = 4),
            HsRecordedRoiFrame(124L, byteArrayOf(60, 60, 60, 60), sampleCount = 4),
        )
        val requests = listOf(
            HsTriggerRefinementRequest(
                triggerSensorNanos = 124L,
                triggerType = "split",
                splitIndex = 1,
                windowNanos = 20L,
            ),
        )

        val results = HsPostRaceRefiner.refineRequests(
            recordedFrames = frames,
            requests = requests,
            config = NativeMonitoringConfig.defaults(),
            defaultWindowNanos = 250_000_000L,
        )

        assertEquals(1, results.size)
        assertTrue(results[0].refined)
        assertEquals(116L, results[0].refinedSensorNanos)
        assertEquals(124L, results[0].provisionalSensorNanos)
    }

    @Test
    fun `post race refiner keeps provisional timestamp when no crossing exists`() {
        val frames = listOf(
            HsRecordedRoiFrame(100L, byteArrayOf(10, 10, 10, 10), sampleCount = 4),
            HsRecordedRoiFrame(108L, byteArrayOf(10, 10, 10, 10), sampleCount = 4),
            HsRecordedRoiFrame(116L, byteArrayOf(10, 10, 10, 10), sampleCount = 4),
        )
        val requests = listOf(
            HsTriggerRefinementRequest(
                triggerSensorNanos = 116L,
                triggerType = "split",
                splitIndex = 1,
            ),
        )

        val results = HsPostRaceRefiner.refineRequests(
            recordedFrames = frames,
            requests = requests,
            config = NativeMonitoringConfig.defaults(),
            defaultWindowNanos = 250_000_000L,
        )

        assertEquals(1, results.size)
        assertFalse(results[0].refined)
        assertEquals(116L, results[0].refinedSensorNanos)
    }

    @Test
    fun `post race refiner scales threshold for hs adjacent-frame cadence`() {
        val frames = listOf(
            HsRecordedRoiFrame(100L, byteArrayOf(10, 10, 10, 10), sampleCount = 4),
            HsRecordedRoiFrame(108L, byteArrayOf(10, 10, 10, 10), sampleCount = 4),
            HsRecordedRoiFrame(116L, byteArrayOf(12, 12, 12, 12), sampleCount = 4),
            HsRecordedRoiFrame(124L, byteArrayOf(12, 12, 12, 12), sampleCount = 4),
        )
        val requests = listOf(
            HsTriggerRefinementRequest(
                triggerSensorNanos = 124L,
                triggerType = "split",
                splitIndex = 1,
                windowNanos = 20L,
            ),
        )

        val strictConfig = NativeMonitoringConfig.defaults().copy(threshold = 0.02)
        val results = HsPostRaceRefiner.refineRequests(
            recordedFrames = frames,
            requests = requests,
            config = strictConfig,
            defaultWindowNanos = 250_000_000L,
        )

        assertEquals(1, results.size)
        assertTrue(results[0].refined)
        assertEquals(116L, results[0].refinedSensorNanos)
    }

    @Test
    fun `offline threshold scan backward returns latest threshold crossing`() {
        val metrics = listOf(
            1_000L to 0.009,
            2_000L to 0.031,
            3_000L to 0.012,
            4_000L to 0.033,
        )

        val selected = metrics.asReversed().firstOrNull { it.second >= 0.02 }

        assertNotNull(selected)
        assertEquals(4_000L, selected?.first)
    }

    @Test
    fun `offline threshold scan backward unresolved when no crossing exists`() {
        val metrics = listOf(
            1_000L to 0.006,
            2_000L to 0.006,
            3_000L to 0.006,
        )

        val selected = metrics.asReversed().firstOrNull { it.second >= 0.02 }

        assertNull(selected)
    }

    @Test
    fun `detection math emits split triggers with cooldown and rearm parity`() {
        val engine = NativeDetectionMath(NativeMonitoringConfig.defaults())

        var latest: NativeFrameStats? = null
        for (i in 0 until 8) {
            latest = engine.process(rawScore = 0.01, frameSensorNanos = i * 100_000_000L)
        }
        assertNotNull(latest)
        assertNull(latest?.triggerEvent)

        val firstTriggerFrame = engine.process(
            rawScore = 0.22,
            frameSensorNanos = 800_000_000L,
        )
        assertNotNull(firstTriggerFrame.triggerEvent)
        assertEquals("split", firstTriggerFrame.triggerEvent?.triggerType)
        assertEquals(1, firstTriggerFrame.triggerEvent?.splitIndex)

        for (i in 0 until 3) {
            engine.process(rawScore = 0.0, frameSensorNanos = 900_000_000L + (i * 100_000_000L))
        }
        val blockedByCooldown = engine.process(
            rawScore = 0.24,
            frameSensorNanos = 1_200_000_000L,
        )
        assertNull(blockedByCooldown.triggerEvent)

        val secondTriggerFrame = engine.process(
            rawScore = 0.25,
            frameSensorNanos = 1_700_000_000L,
        )
        assertNotNull(secondTriggerFrame.triggerEvent)
        assertEquals(2, secondTriggerFrame.triggerEvent?.splitIndex)
    }

    @Test
    fun `sensor elapsed arithmetic and offset smoothing are stable`() {
        val smoother = SensorOffsetSmoother()
        assertEquals(1200L, smoother.update(1200L))
        assertEquals(1300L, smoother.update(1600L))

        val sensorMinusElapsedNanos = 5_000_000L
        val sensorNanos = 12_000_000L
        val elapsed = sensorNanos - sensorMinusElapsedNanos
        assertEquals(7_000_000L, elapsed)

        val mappedBack = elapsed + sensorMinusElapsedNanos
        assertEquals(sensorNanos, mappedBack)
    }

    @Test
    fun `sensor utc arithmetic round-trips using gps offset`() {
        val sensorNanos = 12_345_678_901L
        val sensorMinusElapsedNanos = 98_765_432L
        val gpsUtcOffsetNanos = 1_700_000_000_000_000_000L

        val utcNanos = (sensorNanos - sensorMinusElapsedNanos) + gpsUtcOffsetNanos
        val mappedBackSensorNanos = (utcNanos - gpsUtcOffsetNanos) + sensorMinusElapsedNanos

        assertEquals(sensorNanos, mappedBackSensorNanos)
    }

    @Test
    fun `scorePrecroppedLuma returns stable normalized diff`() {
        val differ = RoiFrameDiffer()
        val initialScore = differ.scorePrecroppedLuma(
            luma = byteArrayOf(10, 20, 30, 40),
            sampleCount = 4,
        )
        assertEquals(0.0, initialScore, 0.0)

        val secondScore = differ.scorePrecroppedLuma(
            luma = byteArrayOf(20, 20, 30, 60),
            sampleCount = 4,
        )
        val expected = (10 + 0 + 0 + 20) / (4.0 * 255.0)
        assertEquals(expected, secondScore, 1e-6)
    }

    @Test
    fun `fps monitor reports no downgrade in normal mode and downgrade in low hs fps`() {
        val monitor = SensorNativeFpsMonitor(lowFpsThreshold = 80.0, warmupFrames = 3)
        var observation = monitor.update(frameSensorNanos = 0L, mode = NativeCameraFpsMode.NORMAL)
        assertFalse(observation.shouldDowngradeToNormal)
        observation = monitor.update(frameSensorNanos = 16_666_667L, mode = NativeCameraFpsMode.NORMAL)
        assertFalse(observation.shouldDowngradeToNormal)

        monitor.reset()
        monitor.update(frameSensorNanos = 0L, mode = NativeCameraFpsMode.HS120)
        monitor.update(frameSensorNanos = 50_000_000L, mode = NativeCameraFpsMode.HS120)
        val lowHs = monitor.update(frameSensorNanos = 100_000_000L, mode = NativeCameraFpsMode.HS120)
        assertTrue(lowHs.shouldDowngradeToNormal)
    }

    @Test
    fun `selectHighestFrameRateBounds prefers highest upper then lower`() {
        val bounds = setOf(
            24 to 30,
            30 to 30,
            30 to 60,
            45 to 60,
            15 to 24,
        )

        val selected = SensorNativeCameraPolicy.selectHighestFrameRateBounds(bounds)

        assertEquals(45, selected?.first)
        assertEquals(60, selected?.second)
    }

    @Test
    fun `selectPreferredNormalFrameRateBounds prefers upper 60 with highest lower`() {
        val bounds = setOf(
            24 to 30,
            15 to 60,
            30 to 60,
            45 to 60,
            30 to 120,
        )

        val selected = SensorNativeCameraPolicy.selectPreferredNormalFrameRateBounds(bounds)

        assertEquals(45, selected?.first)
        assertEquals(60, selected?.second)
    }

    @Test
    fun `selectPreferredNormalFrameRateBounds falls back to highest when 60 unavailable`() {
        val bounds = setOf(
            24 to 30,
            30 to 30,
            30 to 90,
            45 to 90,
        )

        val selected = SensorNativeCameraPolicy.selectPreferredNormalFrameRateBounds(bounds)

        assertEquals(45, selected?.first)
        assertEquals(90, selected?.second)
    }

    @Test
    fun `selectPreferredNormalFrameRateBounds returns null for null or empty`() {
        assertNull(SensorNativeCameraPolicy.selectPreferredNormalFrameRateBounds(null))
        assertNull(SensorNativeCameraPolicy.selectPreferredNormalFrameRateBounds(emptySet()))
    }

    @Test
    fun `selectHighestFrameRateBounds returns null for null or empty`() {
        assertNull(SensorNativeCameraPolicy.selectHighestFrameRateBounds(null))
        assertNull(SensorNativeCameraPolicy.selectHighestFrameRateBounds(emptySet()))
    }

    @Test
    fun `selectPreferredHsBounds prioritizes fixed 120 then highest fixed`() {
        val ranges = listOf(
            30 to 120,
            120 to 120,
            60 to 240,
        )
        val selected = SensorNativeCameraPolicy.selectPreferredHsBounds(ranges)
        assertEquals(120, selected?.first)
        assertEquals(120, selected?.second)

        val noFixed120 = listOf(30 to 120, 240 to 240)
        val selectedNoFixed120 = SensorNativeCameraPolicy.selectPreferredHsBounds(noFixed120)
        assertEquals(240, selectedNoFixed120?.second)
    }

    @Test
    fun `shouldLockAeAwb returns true only at and after warmup`() {
        assertFalse(SensorNativeCameraPolicy.shouldLockAeAwb(0))
        assertFalse(SensorNativeCameraPolicy.shouldLockAeAwb(399))
        assertTrue(SensorNativeCameraPolicy.shouldLockAeAwb(400))
        assertTrue(SensorNativeCameraPolicy.shouldLockAeAwb(401))
    }

    @Test
    fun `selectCameraFacing chooses preferred camera when available`() {
        val rearSelection = SensorNativeCameraPolicy.selectCameraFacing(
            preferred = NativeCameraFacing.REAR,
            hasRear = true,
            hasFront = true,
        )
        val frontSelection = SensorNativeCameraPolicy.selectCameraFacing(
            preferred = NativeCameraFacing.FRONT,
            hasRear = true,
            hasFront = true,
        )

        assertEquals(NativeCameraFacing.REAR, rearSelection?.selected)
        assertFalse(rearSelection?.fallbackUsed ?: true)
        assertEquals(NativeCameraFacing.FRONT, frontSelection?.selected)
        assertFalse(frontSelection?.fallbackUsed ?: true)
    }

    @Test
    fun `selectCameraFacing falls back to available camera`() {
        val fallbackToFront = SensorNativeCameraPolicy.selectCameraFacing(
            preferred = NativeCameraFacing.REAR,
            hasRear = false,
            hasFront = true,
        )
        val fallbackToRear = SensorNativeCameraPolicy.selectCameraFacing(
            preferred = NativeCameraFacing.FRONT,
            hasRear = true,
            hasFront = false,
        )

        assertEquals(NativeCameraFacing.FRONT, fallbackToFront?.selected)
        assertTrue(fallbackToFront?.fallbackUsed ?: false)
        assertEquals(NativeCameraFacing.REAR, fallbackToRear?.selected)
        assertTrue(fallbackToRear?.fallbackUsed ?: false)
    }

    @Test
    fun `selectCameraFacing returns null when no camera is available`() {
        val selection = SensorNativeCameraPolicy.selectCameraFacing(
            preferred = NativeCameraFacing.REAR,
            hasRear = false,
            hasFront = false,
        )

        assertNull(selection)
    }
}
