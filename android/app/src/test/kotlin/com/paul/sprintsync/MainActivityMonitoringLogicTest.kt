package com.paul.sprintsync

import com.paul.sprintsync.features.race_session.SessionOperatingMode
import com.paul.sprintsync.features.race_session.SessionDeviceRole
import com.paul.sprintsync.features.race_session.SessionNetworkRole
import com.paul.sprintsync.features.race_session.SessionStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityMonitoringLogicTest {
    @Test
    fun `starts local capture when monitoring active resumed assigned and local capture is idle`() {
        val action = resolveLocalCaptureAction(
            monitoringActive = true,
            isAppResumed = true,
            shouldRunLocalCapture = true,
            isLocalMotionMonitoring = false,
            localCaptureStartPending = false,
        )

        assertEquals(LocalCaptureAction.START, action)
    }

    @Test
    fun `stops local capture when app pauses during monitoring`() {
        val action = resolveLocalCaptureAction(
            monitoringActive = true,
            isAppResumed = false,
            shouldRunLocalCapture = true,
            isLocalMotionMonitoring = true,
            localCaptureStartPending = false,
        )

        assertEquals(LocalCaptureAction.STOP, action)
    }

    @Test
    fun `stops local capture when local role becomes unassigned during monitoring`() {
        val action = resolveLocalCaptureAction(
            monitoringActive = true,
            isAppResumed = true,
            shouldRunLocalCapture = false,
            isLocalMotionMonitoring = true,
            localCaptureStartPending = false,
        )

        assertEquals(LocalCaptureAction.STOP, action)
    }

    @Test
    fun `keeps local capture unchanged when monitoring state is already satisfied`() {
        val action = resolveLocalCaptureAction(
            monitoringActive = true,
            isAppResumed = true,
            shouldRunLocalCapture = true,
            isLocalMotionMonitoring = true,
            localCaptureStartPending = false,
        )

        assertEquals(LocalCaptureAction.NONE, action)
    }

    @Test
    fun `timer refresh runs only during active in-progress resumed monitoring`() {
        assertTrue(
            shouldKeepTimerRefreshActive(
                monitoringActive = true,
                isAppResumed = true,
                hasStopSensor = false,
            ),
        )
        assertFalse(
            shouldKeepTimerRefreshActive(
                monitoringActive = true,
                isAppResumed = false,
                hasStopSensor = false,
            ),
        )
        assertFalse(
            shouldKeepTimerRefreshActive(
                monitoringActive = true,
                isAppResumed = true,
                hasStopSensor = true,
            ),
        )
    }

    @Test
    fun `does not start capture again while start is pending`() {
        val action = resolveLocalCaptureAction(
            monitoringActive = true,
            isAppResumed = true,
            shouldRunLocalCapture = true,
            isLocalMotionMonitoring = false,
            localCaptureStartPending = true,
        )

        assertEquals(LocalCaptureAction.NONE, action)
    }

    @Test
    fun `does not start local capture when user monitoring toggle is off`() {
        val action = resolveLocalCaptureAction(
            monitoringActive = true,
            isAppResumed = true,
            shouldRunLocalCapture = false,
            isLocalMotionMonitoring = false,
            localCaptureStartPending = false,
        )

        assertEquals(LocalCaptureAction.NONE, action)
    }

    @Test
    fun `stops local capture when user monitoring toggle is turned off during monitoring`() {
        val action = resolveLocalCaptureAction(
            monitoringActive = true,
            isAppResumed = true,
            shouldRunLocalCapture = false,
            isLocalMotionMonitoring = true,
            localCaptureStartPending = false,
        )

        assertEquals(LocalCaptureAction.STOP, action)
    }

    @Test
    fun `re-enabling user monitoring toggle allows local capture start when guards are met`() {
        val action = resolveLocalCaptureAction(
            monitoringActive = true,
            isAppResumed = true,
            shouldRunLocalCapture = true,
            isLocalMotionMonitoring = false,
            localCaptureStartPending = false,
        )

        assertEquals(LocalCaptureAction.START, action)
    }

    @Test
    fun `display host mode prefers landscape orientation`() {
        assertTrue(shouldUseLandscapeForMode(SessionOperatingMode.DISPLAY_HOST))
        assertFalse(shouldUseLandscapeForMode(SessionOperatingMode.SINGLE_DEVICE))
    }

    @Test
    fun `display host mode uses immersive fullscreen and other modes do not`() {
        assertTrue(shouldUseImmersiveModeForMode(SessionOperatingMode.DISPLAY_HOST))
        assertFalse(shouldUseImmersiveModeForMode(SessionOperatingMode.SINGLE_DEVICE))
    }

    @Test
    fun `timer display uses ss cc below one minute and no three-digit milliseconds`() {
        assertEquals("00.00", formatElapsedTimerDisplay(totalMillis = 0))
        assertEquals("01.67", formatElapsedTimerDisplay(totalMillis = 1_678))
        assertEquals("59.99", formatElapsedTimerDisplay(totalMillis = 59_999))
    }

    @Test
    fun `timer display prepends minutes from one minute onward with centiseconds`() {
        assertEquals("01:00.00", formatElapsedTimerDisplay(totalMillis = 60_000))
        assertEquals("02:05.43", formatElapsedTimerDisplay(totalMillis = 125_432))
    }

    @Test
    fun `split history renders ordered split labels with elapsed time`() {
        val history = buildSplitHistoryForTimeline(
            startedSensorNanos = 1_000_000_000L,
            splitSensorNanos = listOf(11_000_000_000L, 21_000_000_000L),
        )

        assertEquals(listOf("Split 1: 10.00", "Split 2: 20.00"), history)
    }

    @Test
    fun `applies live local camera facing update when local monitoring active`() {
        assertTrue(
            shouldApplyLiveLocalCameraFacingUpdate(
                isLocalMotionMonitoring = true,
                assignedDeviceId = "local-1",
                localDeviceId = "local-1",
            ),
        )
    }

    @Test
    fun `does not apply live local camera facing update when monitoring inactive`() {
        assertFalse(
            shouldApplyLiveLocalCameraFacingUpdate(
                isLocalMotionMonitoring = false,
                assignedDeviceId = "local-1",
                localDeviceId = "local-1",
            ),
        )
    }

    @Test
    fun `does not apply live local camera facing update for non local device`() {
        assertFalse(
            shouldApplyLiveLocalCameraFacingUpdate(
                isLocalMotionMonitoring = true,
                assignedDeviceId = "remote-1",
                localDeviceId = "local-1",
            ),
        )
    }

    @Test
    fun `display rows show READY for connected endpoints with no lap yet`() {
        val rows = buildDisplayLapRowsForConnectedDevices(
            connectedEndpointIds = linkedSetOf("ep-1"),
            deviceNamesByEndpointId = mapOf("ep-1" to "Pixel 7"),
            elapsedByEndpointId = emptyMap(),
            limitMillisByEndpointId = emptyMap(),
            hostStartSensorNanos = null,
            hostStopSensorNanos = null,
            monitoringActive = false,
            nowSensorNanos = 0L,
        )

        assertEquals(1, rows.size)
        assertEquals("Pixel 7", rows[0].deviceName)
        assertEquals("READY", rows[0].lapTimeLabel)
    }

    @Test
    fun `display rows show formatted lap for connected endpoints with lap`() {
        val rows = buildDisplayLapRowsForConnectedDevices(
            connectedEndpointIds = linkedSetOf("ep-1"),
            deviceNamesByEndpointId = mapOf("ep-1" to "Pixel 7"),
            elapsedByEndpointId = mapOf("ep-1" to 1_730_000_000L),
            limitMillisByEndpointId = emptyMap(),
            hostStartSensorNanos = 1_000_000_000L,
            hostStopSensorNanos = null,
            monitoringActive = true,
            nowSensorNanos = 4_000_000_000L,
        )

        assertEquals(1, rows.size)
        assertEquals("Pixel 7", rows[0].deviceName)
        assertEquals(formatElapsedTimerDisplay(1_730L), rows[0].lapTimeLabel)
    }

    @Test
    fun `display rows include mixed connected devices with lap and READY`() {
        val rows = buildDisplayLapRowsForConnectedDevices(
            connectedEndpointIds = linkedSetOf("ep-1", "ep-2"),
            deviceNamesByEndpointId = mapOf("ep-1" to "Pixel 7", "ep-2" to "CPH2399"),
            elapsedByEndpointId = mapOf("ep-1" to 1_730_000_000L),
            limitMillisByEndpointId = emptyMap(),
            hostStartSensorNanos = null,
            hostStopSensorNanos = null,
            monitoringActive = false,
            nowSensorNanos = 0L,
        )

        assertEquals(2, rows.size)
        assertEquals("Pixel 7", rows[0].deviceName)
        assertEquals(formatElapsedTimerDisplay(1_730L), rows[0].lapTimeLabel)
        assertEquals("CPH2399", rows[1].deviceName)
        assertEquals("READY", rows[1].lapTimeLabel)
    }

    @Test
    fun `display rows only include currently connected endpoints`() {
        val rows = buildDisplayLapRowsForConnectedDevices(
            connectedEndpointIds = linkedSetOf("ep-2"),
            deviceNamesByEndpointId = mapOf("ep-1" to "Pixel 7", "ep-2" to "CPH2399"),
            elapsedByEndpointId = mapOf(
                "ep-1" to 1_730_000_000L,
                "ep-2" to 1_770_000_000L,
            ),
            limitMillisByEndpointId = emptyMap(),
            hostStartSensorNanos = null,
            hostStopSensorNanos = null,
            monitoringActive = false,
            nowSensorNanos = 0L,
        )

        assertEquals(1, rows.size)
        assertEquals("CPH2399", rows[0].deviceName)
        assertEquals(formatElapsedTimerDisplay(1_770L), rows[0].lapTimeLabel)
    }

    @Test
    fun `display rows show live timer during active run when endpoint has no final`() {
        val rows = buildDisplayLapRowsForConnectedDevices(
            connectedEndpointIds = linkedSetOf("ep-1"),
            deviceNamesByEndpointId = mapOf("ep-1" to "Pixel 7"),
            elapsedByEndpointId = emptyMap(),
            limitMillisByEndpointId = emptyMap(),
            hostStartSensorNanos = 1_000_000_000L,
            hostStopSensorNanos = null,
            monitoringActive = true,
            nowSensorNanos = 2_730_000_000L,
        )

        assertEquals(1, rows.size)
        assertEquals("Pixel 7", rows[0].deviceName)
        assertEquals(formatElapsedTimerDisplay(1_730L), rows[0].lapTimeLabel)
    }

    @Test
    fun `display rows keep endpoint final when live timer is available`() {
        val rows = buildDisplayLapRowsForConnectedDevices(
            connectedEndpointIds = linkedSetOf("ep-1"),
            deviceNamesByEndpointId = mapOf("ep-1" to "Pixel 7"),
            elapsedByEndpointId = mapOf("ep-1" to 1_730_000_000L),
            limitMillisByEndpointId = emptyMap(),
            hostStartSensorNanos = 1_000_000_000L,
            hostStopSensorNanos = null,
            monitoringActive = true,
            nowSensorNanos = 9_000_000_000L,
        )

        assertEquals(1, rows.size)
        assertEquals(formatElapsedTimerDisplay(1_730L), rows[0].lapTimeLabel)
    }

    @Test
    fun `display rows support mixed final and live timers`() {
        val rows = buildDisplayLapRowsForConnectedDevices(
            connectedEndpointIds = linkedSetOf("ep-1", "ep-2"),
            deviceNamesByEndpointId = mapOf("ep-1" to "Pixel 7", "ep-2" to "CPH2399"),
            elapsedByEndpointId = mapOf("ep-1" to 1_730_000_000L),
            limitMillisByEndpointId = emptyMap(),
            hostStartSensorNanos = 1_000_000_000L,
            hostStopSensorNanos = null,
            monitoringActive = true,
            nowSensorNanos = 2_500_000_000L,
        )

        assertEquals(2, rows.size)
        assertEquals(formatElapsedTimerDisplay(1_730L), rows[0].lapTimeLabel)
        assertEquals(formatElapsedTimerDisplay(1_500L), rows[1].lapTimeLabel)
    }

    @Test
    fun `display rows keep running after host stop until endpoint final arrives`() {
        val rows = buildDisplayLapRowsForConnectedDevices(
            connectedEndpointIds = linkedSetOf("ep-1"),
            deviceNamesByEndpointId = mapOf("ep-1" to "Pixel 7"),
            elapsedByEndpointId = emptyMap(),
            limitMillisByEndpointId = emptyMap(),
            hostStartSensorNanos = 1_000_000_000L,
            hostStopSensorNanos = 2_000_000_000L,
            monitoringActive = false,
            nowSensorNanos = 3_000_000_000L,
        )

        assertEquals(1, rows.size)
        assertEquals(formatElapsedTimerDisplay(2_000L), rows[0].lapTimeLabel)
    }

    @Test
    fun `display role never runs local monitoring capture`() {
        assertFalse(
            shouldRunLocalMonitoring(
                mode = SessionOperatingMode.SINGLE_DEVICE,
                userMonitoringEnabled = true,
                localRole = SessionDeviceRole.DISPLAY,
            ),
        )
    }

    @Test
    fun `passive display client mode only matches network race client display role`() {
        assertTrue(
            shouldUsePassiveDisplayClientMode(
                mode = SessionOperatingMode.SINGLE_DEVICE,
                networkRole = SessionNetworkRole.CLIENT,
                localRole = SessionDeviceRole.DISPLAY,
            ),
        )
        assertFalse(
            shouldUsePassiveDisplayClientMode(
                mode = SessionOperatingMode.SINGLE_DEVICE,
                networkRole = SessionNetworkRole.HOST,
                localRole = SessionDeviceRole.DISPLAY,
            ),
        )
    }

    @Test
    fun `auto start role maps single and display flavors`() {
        assertEquals(AutoStartRole.SINGLE, resolveAutoStartRole(configuredRole = "single"))
        assertEquals(AutoStartRole.DISPLAY, resolveAutoStartRole(configuredRole = "display"))
        assertEquals(AutoStartRole.CONTROLLER, resolveAutoStartRole(configuredRole = "controller"))
        assertEquals(AutoStartRole.NONE, resolveAutoStartRole(configuredRole = "unknown"))
    }

    @Test
    fun `controller startup uses monitoring stage`() {
        assertEquals(SessionStage.MONITORING, controllerInitialStage())
    }

    @Test
    fun `effective auto-start role falls back to oneplus flavor controller`() {
        assertEquals(
            "controller",
            resolveEffectiveAutoStartRole(
                configuredRole = "none",
                flavorName = "oneplusSingle",
            ),
        )
    }

    @Test
    fun `effective auto-start role forces controller for oneplus flavor`() {
        assertEquals(
            "controller",
            resolveEffectiveAutoStartRole(
                configuredRole = "single",
                flavorName = "oneplusSingle",
            ),
        )
    }

    @Test
    fun `oneplus flavor helper matches oneplus single only`() {
        assertTrue(isOneplusControllerFlavor("oneplusSingle"))
        assertFalse(isOneplusControllerFlavor("pixel7Single"))
    }

    @Test
    fun `controller endpoint name helper matches controller suffix`() {
        assertTrue(isControllerEndpointName("CPH2399 (Controller)"))
        assertFalse(isControllerEndpointName("Topaz"))
    }

    @Test
    fun `sensitivity percent maps to clamped inverted threshold`() {
        assertEquals(0.08, thresholdFromSensitivityPercent(0), 0.000001)
        assertEquals(0.001, thresholdFromSensitivityPercent(100), 0.000001)
        assertEquals(0.001, thresholdFromSensitivityPercent(150), 0.000001)
        assertEquals(0.08, thresholdFromSensitivityPercent(-10), 0.000001)
    }

    @Test
    fun `display rows mark over limit when elapsed exceeds per-endpoint limit`() {
        val rows = buildDisplayLapRowsForConnectedDevices(
            connectedEndpointIds = linkedSetOf("ep-1", "ep-2"),
            deviceNamesByEndpointId = mapOf("ep-1" to "Pixel 7", "ep-2" to "CPH2399"),
            elapsedByEndpointId = mapOf(
                "ep-1" to 31_000_000_000L,
                "ep-2" to 15_000_000_000L,
            ),
            limitMillisByEndpointId = mapOf(
                "ep-1" to 30_000L,
                "ep-2" to 30_000L,
            ),
            hostStartSensorNanos = null,
            hostStopSensorNanos = null,
            monitoringActive = false,
            nowSensorNanos = 0L,
        )

        assertEquals(true, rows[0].isOverLimit)
        assertEquals(false, rows[1].isOverLimit)
        assertEquals("Limit 30000 ms", rows[0].limitLabel)
    }

    @Test
    fun `tcp host ip prefers hotspot gateway and falls back when gateway missing`() {
        assertEquals("10.173.42.224", resolveTcpHostAddress(gatewayIp = "10.173.42.224"))
        assertEquals("192.168.43.1", resolveTcpHostAddress(gatewayIp = null))
        assertEquals("192.168.43.1", resolveTcpHostAddress(gatewayIp = ""))
    }

    @Test
    fun `reconnect delay uses bounded exponential backoff`() {
        assertEquals(500L, reconnectDelayMillis(attempt = 0))
        assertEquals(1000L, reconnectDelayMillis(attempt = 1))
        assertEquals(2000L, reconnectDelayMillis(attempt = 2))
        assertEquals(5000L, reconnectDelayMillis(attempt = 6))
        assertEquals(5000L, reconnectDelayMillis(attempt = 12))
    }

    @Test
    fun `little-endian dhcp gateway int converts to ipv4`() {
        assertEquals("10.173.42.224", ipv4FromLittleEndianInt(0xE02AAD0A.toInt()))
        assertEquals(null, ipv4FromLittleEndianInt(0))
    }
}
