package com.paul.sprintsync.features.race_session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RaceSessionModelsTest {
    @Test
    fun `snapshot round-trips host GPS fields`() {
        val original = SessionSnapshotMessage(
            stage = SessionStage.MONITORING,
            monitoringActive = true,
            devices = listOf(
                SessionDevice(
                    id = "local-device",
                    name = "This Device",
                    role = SessionDeviceRole.START,
                    isLocal = true,
                ),
            ),
            hostStartSensorNanos = 1_000L,
            hostStopSensorNanos = 2_000L,
            hostSplitSensorNanos = listOf(1_300L, 1_600L),
            runId = "run-1",
            hostSensorMinusElapsedNanos = 120L,
            hostGpsUtcOffsetNanos = 8_000L,
            hostGpsFixAgeNanos = 600_000_000L,
            selfDeviceId = "peer-1",
        )

        val parsed = SessionSnapshotMessage.tryParse(original.toJsonString())

        assertNotNull(parsed)
        assertEquals(8_000L, parsed?.hostGpsUtcOffsetNanos)
        assertEquals(600_000_000L, parsed?.hostGpsFixAgeNanos)
        assertEquals(listOf(1_300L, 1_600L), parsed?.hostSplitSensorNanos)
    }

    @Test
    fun `timeline snapshot round-trips with optional fields`() {
        val original = SessionTimelineSnapshotMessage(
            hostStartSensorNanos = 1_000L,
            hostStopSensorNanos = 2_500L,
            hostSplitSensorNanos = listOf(1_250L, 1_850L),
            sentElapsedNanos = 90_000L,
        )

        val parsed = SessionTimelineSnapshotMessage.tryParse(original.toJsonString())

        assertNotNull(parsed)
        assertEquals(1_000L, parsed?.hostStartSensorNanos)
        assertEquals(2_500L, parsed?.hostStopSensorNanos)
        assertEquals(listOf(1_250L, 1_850L), parsed?.hostSplitSensorNanos)
        assertEquals(90_000L, parsed?.sentElapsedNanos)
    }

    @Test
    fun `timeline snapshot parser defaults split array for legacy payload`() {
        val legacy = """
            {"type":"timeline_snapshot","hostStartSensorNanos":1000,"hostStopSensorNanos":2500,"sentElapsedNanos":90000}
        """.trimIndent()

        val parsed = SessionTimelineSnapshotMessage.tryParse(legacy)

        assertNotNull(parsed)
        assertTrue(parsed?.hostSplitSensorNanos?.isEmpty() == true)
    }

    @Test
    fun `trigger message parse rejects invalid payload`() {
        val invalid = """
            {"type":"session_trigger","triggerType":"","triggerSensorNanos":0}
        """.trimIndent()

        val parsed = SessionTriggerMessage.tryParse(invalid)

        assertNull(parsed)
    }

    @Test
    fun `clock sync binary request and response round-trip`() {
        val request = SessionClockSyncBinaryRequest(clientSendElapsedNanos = 100L)
        val response = SessionClockSyncBinaryResponse(
            clientSendElapsedNanos = 100L,
            hostReceiveElapsedNanos = 220L,
            hostSendElapsedNanos = 260L,
        )

        val parsedRequest = SessionClockSyncBinaryCodec.decodeRequest(
            SessionClockSyncBinaryCodec.encodeRequest(request),
        )
        val parsedResponse = SessionClockSyncBinaryCodec.decodeResponse(
            SessionClockSyncBinaryCodec.encodeResponse(response),
        )

        assertNotNull(parsedRequest)
        assertEquals(100L, parsedRequest?.clientSendElapsedNanos)
        assertNotNull(parsedResponse)
        assertEquals(220L, parsedResponse?.hostReceiveElapsedNanos)
        assertEquals(260L, parsedResponse?.hostSendElapsedNanos)
    }

    @Test
    fun `clock sync binary codec rejects wrong version type and length`() {
        val validRequest = SessionClockSyncBinaryCodec.encodeRequest(
            SessionClockSyncBinaryRequest(clientSendElapsedNanos = 1L),
        )
        val wrongVersionRequest = validRequest.copyOf().apply { this[0] = 9 }
        val wrongTypeRequest = validRequest.copyOf().apply { this[1] = SessionClockSyncBinaryCodec.TYPE_RESPONSE }
        val wrongLengthRequest = validRequest.copyOf(9)

        val validResponse = SessionClockSyncBinaryCodec.encodeResponse(
            SessionClockSyncBinaryResponse(
                clientSendElapsedNanos = 1L,
                hostReceiveElapsedNanos = 2L,
                hostSendElapsedNanos = 3L,
            ),
        )
        val wrongVersionResponse = validResponse.copyOf().apply { this[0] = 9 }
        val wrongTypeResponse = validResponse.copyOf().apply { this[1] = SessionClockSyncBinaryCodec.TYPE_REQUEST }
        val wrongLengthResponse = validResponse.copyOf(25)

        assertNull(SessionClockSyncBinaryCodec.decodeRequest(wrongVersionRequest))
        assertNull(SessionClockSyncBinaryCodec.decodeRequest(wrongTypeRequest))
        assertNull(SessionClockSyncBinaryCodec.decodeRequest(wrongLengthRequest))
        assertNull(SessionClockSyncBinaryCodec.decodeResponse(wrongVersionResponse))
        assertNull(SessionClockSyncBinaryCodec.decodeResponse(wrongTypeResponse))
        assertNull(SessionClockSyncBinaryCodec.decodeResponse(wrongLengthResponse))
    }

    @Test
    fun `trigger refinement parser rejects missing run id`() {
        val invalid = """
            {"type":"trigger_refinement","runId":"","role":"start","provisionalHostSensorNanos":1,"refinedHostSensorNanos":2}
        """.trimIndent()

        val parsed = SessionTriggerRefinementMessage.tryParse(invalid)

        assertNull(parsed)
    }

    @Test
    fun `device identity message round-trips`() {
        val original = SessionDeviceIdentityMessage(
            stableDeviceId = "stable-device-1",
            deviceName = "Pixel 8 Pro",
        )

        val parsed = SessionDeviceIdentityMessage.tryParse(original.toJsonString())

        assertNotNull(parsed)
        assertEquals("stable-device-1", parsed?.stableDeviceId)
        assertEquals("Pixel 8 Pro", parsed?.deviceName)
    }

    @Test
    fun `device identity parser accepts legacy payload without udp endpoint`() {
        val legacyPayload = """
            {"type":"device_identity","stableDeviceId":"stable-device-2","deviceName":"Legacy Phone"}
        """.trimIndent()

        val parsed = SessionDeviceIdentityMessage.tryParse(legacyPayload)

        assertNotNull(parsed)
        assertEquals("stable-device-2", parsed?.stableDeviceId)
        assertEquals("Legacy Phone", parsed?.deviceName)
    }

    @Test
    fun `device role parsing and labels include split and display`() {
        assertEquals(SessionDeviceRole.SPLIT, sessionDeviceRoleFromName("split"))
        assertEquals("Split", sessionDeviceRoleLabel(SessionDeviceRole.SPLIT))
        assertEquals(SessionDeviceRole.DISPLAY, sessionDeviceRoleFromName("display"))
        assertEquals("Display", sessionDeviceRoleLabel(SessionDeviceRole.DISPLAY))
    }

    @Test
    fun `control command message round-trips reset action`() {
        val original = SessionControlCommandMessage(
            action = SessionControlAction.RESET_TIMER,
            targetEndpointId = "ep-2",
            senderDeviceName = "OnePlus Controller",
            limitMillis = null,
            sensitivityPercent = null,
        )

        val parsed = SessionControlCommandMessage.tryParse(original.toJsonString())

        assertNotNull(parsed)
        assertEquals(SessionControlAction.RESET_TIMER, parsed?.action)
        assertEquals("ep-2", parsed?.targetEndpointId)
        assertEquals("OnePlus Controller", parsed?.senderDeviceName)
        assertNull(parsed?.limitMillis)
        assertNull(parsed?.sensitivityPercent)
    }

    @Test
    fun `control command message round-trips set display limit action`() {
        val original = SessionControlCommandMessage(
            action = SessionControlAction.SET_DISPLAY_LIMIT,
            targetEndpointId = "ep-9",
            senderDeviceName = "OnePlus Controller",
            limitMillis = 7_260L,
            sensitivityPercent = null,
        )

        val parsed = SessionControlCommandMessage.tryParse(original.toJsonString())

        assertNotNull(parsed)
        assertEquals(SessionControlAction.SET_DISPLAY_LIMIT, parsed?.action)
        assertEquals("ep-9", parsed?.targetEndpointId)
        assertEquals("OnePlus Controller", parsed?.senderDeviceName)
        assertEquals(7_260L, parsed?.limitMillis)
        assertNull(parsed?.sensitivityPercent)
    }

    @Test
    fun `control command message round-trips motion sensitivity action`() {
        val original = SessionControlCommandMessage(
            action = SessionControlAction.SET_MOTION_SENSITIVITY,
            targetEndpointId = "ep-7",
            senderDeviceName = "OnePlus Controller",
            limitMillis = null,
            sensitivityPercent = 72,
        )

        val parsed = SessionControlCommandMessage.tryParse(original.toJsonString())

        assertNotNull(parsed)
        assertEquals(SessionControlAction.SET_MOTION_SENSITIVITY, parsed?.action)
        assertEquals("ep-7", parsed?.targetEndpointId)
        assertEquals(72, parsed?.sensitivityPercent)
        assertNull(parsed?.limitMillis)
    }

    @Test
    fun `control command parser rejects invalid payload`() {
        val missingTarget = """{"type":"control_command","action":"reset_timer","targetEndpointId":"","senderDeviceName":"OnePlus"}"""
        val invalidLimit = """{"type":"control_command","action":"set_display_limit","targetEndpointId":"ep-1","senderDeviceName":"OnePlus","limitMillis":0}"""
        val invalidSensitivity = """{"type":"control_command","action":"set_motion_sensitivity","targetEndpointId":"ep-1","senderDeviceName":"OnePlus","sensitivityPercent":101}"""

        assertNull(SessionControlCommandMessage.tryParse(missingTarget))
        assertNull(SessionControlCommandMessage.tryParse(invalidLimit))
        assertNull(SessionControlCommandMessage.tryParse(invalidSensitivity))
    }

    @Test
    fun `controller targets message round-trips`() {
        val original = SessionControllerTargetsMessage(
            senderDeviceName = "Display Host",
            targets = listOf(
                SessionControllerTarget(endpointId = "ep-a", deviceName = "Start Phone"),
                SessionControllerTarget(endpointId = "ep-b", deviceName = "Stop Phone"),
            ),
        )

        val parsed = SessionControllerTargetsMessage.tryParse(original.toJsonString())

        assertNotNull(parsed)
        assertEquals("Display Host", parsed?.senderDeviceName)
        assertEquals(2, parsed?.targets?.size)
        assertEquals("ep-a", parsed?.targets?.get(0)?.endpointId)
        assertEquals("Start Phone", parsed?.targets?.get(0)?.deviceName)
    }

    @Test
    fun `controller targets parser rejects invalid payload`() {
        val missingSender = """{"type":"controller_targets","senderDeviceName":"","targets":[{"endpointId":"ep-1","deviceName":"A"}]}"""
        val missingTargets = """{"type":"controller_targets","senderDeviceName":"Display Host"}"""

        assertNull(SessionControllerTargetsMessage.tryParse(missingSender))
        assertNull(SessionControllerTargetsMessage.tryParse(missingTargets))
    }
}
