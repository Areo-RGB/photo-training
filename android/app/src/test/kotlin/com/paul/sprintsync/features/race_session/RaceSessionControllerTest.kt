package com.paul.sprintsync.features.race_session

import com.paul.sprintsync.core.services.NearbyEvent
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class RaceSessionControllerTest {
    @Test
    fun `clock sync burst selects minimum RTT sample and breaks ties by earliest accepted`() {
        val scriptedNow = ArrayDeque(
            listOf(
                1_000L,
                2_000L,
                3_000L,
                5_000L,
                6_000L,
                9_000L,
                10_000L,
            ),
        )
        var fallbackNow = 10_000L
        val sentPayloads = mutableListOf<ByteArray>()

        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete ->
                onComplete(Result.success(Unit))
            },
            sendClockSyncPayload = { _, payloadBytes, onComplete ->
                sentPayloads += payloadBytes
                onComplete(Result.success(Unit))
            },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = {
                if (scriptedNow.isEmpty()) {
                    fallbackNow += 1_000L
                    fallbackNow
                } else {
                    scriptedNow.removeFirst()
                }
            },
            clockSyncDelay = { _ -> },
        )

        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "ep-1",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )

        controller.startClockSyncBurst(endpointId = "ep-1", sampleCount = 3)
        assertTrue(controller.uiState.value.clockSyncInProgress)

        val requests = sentPayloads.mapNotNull { SessionClockSyncBinaryCodec.decodeRequest(it) }
        assertEquals(3, requests.size)

        val request1 = requests[0]
        val request2 = requests[1]
        val request3 = requests[2]

        val response2 = SessionClockSyncBinaryResponse(
            clientSendElapsedNanos = request2.clientSendElapsedNanos,
            hostReceiveElapsedNanos = request2.clientSendElapsedNanos + 100L,
            hostSendElapsedNanos = request2.clientSendElapsedNanos + 310L,
        )
        val response3 = SessionClockSyncBinaryResponse(
            clientSendElapsedNanos = request3.clientSendElapsedNanos,
            hostReceiveElapsedNanos = request3.clientSendElapsedNanos + 100L,
            hostSendElapsedNanos = request3.clientSendElapsedNanos + 510L,
        )
        val response1 = SessionClockSyncBinaryResponse(
            clientSendElapsedNanos = request1.clientSendElapsedNanos,
            hostReceiveElapsedNanos = request1.clientSendElapsedNanos + 100L,
            hostSendElapsedNanos = request1.clientSendElapsedNanos + 310L,
        )

        controller.onNearbyEvent(
            NearbyEvent.ClockSyncSampleReceived(
                endpointId = "ep-1",
                sample = response2,
            ),
        )
        controller.onNearbyEvent(
            NearbyEvent.ClockSyncSampleReceived(
                endpointId = "ep-1",
                sample = response3,
            ),
        )
        controller.onNearbyEvent(
            NearbyEvent.ClockSyncSampleReceived(
                endpointId = "ep-1",
                sample = response1,
            ),
        )

        assertFalse(controller.uiState.value.clockSyncInProgress)
        assertNotNull(controller.clockState.value.hostMinusClientElapsedNanos)
        assertEquals(-1_295L, controller.clockState.value.hostMinusClientElapsedNanos)
        assertEquals(3_000L, controller.clockState.value.hostClockRoundTripNanos)
        assertTrue(controller.hasFreshClockLock())
        assertEquals("clock_sync_complete", controller.uiState.value.lastEvent)
    }

    @Test
    fun `clock sync burst staggers sends by 50ms and finishes only after all pending samples resolve`() {
        var now = 10_000L
        val sentPayloads = mutableListOf<ByteArray>()
        val delayCalls = mutableListOf<Long>()

        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            sendClockSyncPayload = { _, payloadBytes, onComplete ->
                sentPayloads += payloadBytes
                onComplete(Result.success(Unit))
            },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = {
                now += 1_000L
                now
            },
            clockSyncDelay = { delayCalls += it },
        )

        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "ep-1",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.startClockSyncBurst(endpointId = "ep-1", sampleCount = 4)

        assertEquals(listOf(50L, 50L, 50L), delayCalls)
        val requests = sentPayloads.mapNotNull { SessionClockSyncBinaryCodec.decodeRequest(it) }
        assertEquals(4, requests.size)
        assertTrue(controller.uiState.value.clockSyncInProgress)

        requests.take(3).forEach { request ->
            controller.onNearbyEvent(
                NearbyEvent.ClockSyncSampleReceived(
                    endpointId = "ep-1",
                    sample = SessionClockSyncBinaryResponse(
                        clientSendElapsedNanos = request.clientSendElapsedNanos,
                        hostReceiveElapsedNanos = request.clientSendElapsedNanos + 100L,
                        hostSendElapsedNanos = request.clientSendElapsedNanos + 200L,
                    ),
                ),
            )
        }
        assertTrue(controller.uiState.value.clockSyncInProgress)

        val lastRequest = requests.last()
        controller.onNearbyEvent(
            NearbyEvent.ClockSyncSampleReceived(
                endpointId = "ep-1",
                sample = SessionClockSyncBinaryResponse(
                    clientSendElapsedNanos = lastRequest.clientSendElapsedNanos,
                    hostReceiveElapsedNanos = lastRequest.clientSendElapsedNanos + 100L,
                    hostSendElapsedNanos = lastRequest.clientSendElapsedNanos + 200L,
                ),
            ),
        )

        assertFalse(controller.uiState.value.clockSyncInProgress)
        assertEquals("clock_sync_complete", controller.uiState.value.lastEvent)
    }

    @Test
    fun `clock sync burst rejects unconnected endpoint`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.startClockSyncBurst(endpointId = "missing", sampleCount = 3)

        assertEquals("Clock sync ignored: endpoint not connected", controller.uiState.value.lastError)
    }

    @Test
    fun `timeline start stop persists completed run`() {
        var savedRunStarted: Long? = null
        var savedRunStopped: Long? = null
        val latch = CountDownLatch(1)

        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { run ->
                savedRunStarted = run.startedSensorNanos
                savedRunStopped = run.stoppedSensorNanos
                latch.countDown()
            },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.ingestLocalTrigger("start", splitIndex = 0, triggerSensorNanos = 1_000L, broadcast = false)
        controller.ingestLocalTrigger("stop", splitIndex = 0, triggerSensorNanos = 2_000L, broadcast = false)
        assertTrue(latch.await(2, TimeUnit.SECONDS))

        assertEquals(1_000L, savedRunStarted)
        assertEquals(2_000L, savedRunStopped)
    }

    @Test
    fun `timeline snapshot maps host sensor into local sensor in client mode`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.CLIENT)
        controller.updateClockState(
            hostMinusClientElapsedNanos = 100L,
            hostSensorMinusElapsedNanos = 500L,
            localSensorMinusElapsedNanos = 200L,
        )

        val snapshot = SessionTimelineSnapshotMessage(
            hostStartSensorNanos = 1_000L,
            hostStopSensorNanos = 2_000L,
            sentElapsedNanos = 10L,
        )

        controller.onNearbyEvent(
            NearbyEvent.PayloadReceived(
                endpointId = "ep-1",
                message = snapshot.toJsonString(),
            ),
        )

        assertEquals(600L, controller.uiState.value.timeline.hostStartSensorNanos)
        assertEquals(1_600L, controller.uiState.value.timeline.hostStopSensorNanos)
    }

    @Test
    fun `snapshot ignores host timeline when client is unsynced`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.CLIENT)
        controller.updateClockState(
            hostMinusClientElapsedNanos = 100L,
            hostSensorMinusElapsedNanos = 500L,
            localSensorMinusElapsedNanos = 200L,
        )
        controller.onNearbyEvent(
            NearbyEvent.PayloadReceived(
                endpointId = "ep-1",
                message = SessionTimelineSnapshotMessage(
                    hostStartSensorNanos = 1_000L,
                    hostStopSensorNanos = null,
                    sentElapsedNanos = 10L,
                ).toJsonString(),
            ),
        )
        assertEquals(600L, controller.uiState.value.timeline.hostStartSensorNanos)
        assertNull(controller.uiState.value.timeline.hostStopSensorNanos)

        controller.updateClockState(
            hostMinusClientElapsedNanos = null,
            hostSensorMinusElapsedNanos = null,
            localSensorMinusElapsedNanos = null,
            hostGpsUtcOffsetNanos = null,
            localGpsUtcOffsetNanos = null,
        )
        controller.onNearbyEvent(
            NearbyEvent.PayloadReceived(
                endpointId = "ep-1",
                message = SessionSnapshotMessage(
                    stage = SessionStage.MONITORING,
                    monitoringActive = true,
                    devices = listOf(
                        SessionDevice(
                            id = "local",
                            name = "Local",
                            role = SessionDeviceRole.STOP,
                            isLocal = true,
                        ),
                    ),
                    hostStartSensorNanos = 5_000L,
                    hostStopSensorNanos = 7_000L,
                    runId = "run-1",
                    hostSensorMinusElapsedNanos = null,
                    hostGpsUtcOffsetNanos = null,
                    hostGpsFixAgeNanos = null,
                    selfDeviceId = "local",
                ).toJsonString(),
            ),
        )

        assertEquals(600L, controller.uiState.value.timeline.hostStartSensorNanos)
        assertNull(controller.uiState.value.timeline.hostStopSensorNanos)
        assertEquals("snapshot_applied_unsynced_timeline_ignored", controller.uiState.value.lastEvent)
    }

    @Test
    fun `single device mode auto resets active timeline and retains latest completed lap`() {
        val sentMessages = mutableListOf<String>()
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, messageJson, onComplete ->
                sentMessages += messageJson
                onComplete(Result.success(Unit))
            },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.startSingleDeviceMonitoring()
        controller.onLocalMotionTrigger("motion", splitIndex = 0, triggerSensorNanos = 1_000L)
        controller.onLocalMotionTrigger("motion", splitIndex = 0, triggerSensorNanos = 2_000L)

        val state = controller.uiState.value
        assertEquals(SessionOperatingMode.SINGLE_DEVICE, state.operatingMode)
        assertEquals(SessionStage.MONITORING, state.stage)
        assertTrue(state.monitoringActive)
        assertNull(state.timeline.hostStartSensorNanos)
        assertNull(state.timeline.hostStopSensorNanos)
        assertEquals(1_000L, state.latestCompletedTimeline?.hostStartSensorNanos)
        assertEquals(2_000L, state.latestCompletedTimeline?.hostStopSensorNanos)
        assertEquals("single_device_stop", state.lastEvent)
        assertTrue(sentMessages.isEmpty())
    }

    @Test
    fun `single device mode ignores non-monotonic stop trigger`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.startSingleDeviceMonitoring()
        controller.onLocalMotionTrigger("motion", splitIndex = 0, triggerSensorNanos = 2_000L)
        controller.onLocalMotionTrigger("motion", splitIndex = 0, triggerSensorNanos = 1_000L)

        val state = controller.uiState.value
        assertEquals(2_000L, state.timeline.hostStartSensorNanos)
        assertNull(state.timeline.hostStopSensorNanos)
        assertNull(state.latestCompletedTimeline)
    }

    @Test
    fun `clock sync sample can be ingested from non-nearby transport`() {
        var nowNanos = 0L
        val sentPayloads = mutableListOf<ByteArray>()
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            sendClockSyncPayload = { _, payloadBytes, onComplete ->
                sentPayloads += payloadBytes
                onComplete(Result.success(Unit))
            },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = {
                nowNanos += 1_000L
                nowNanos
            },
            clockSyncDelay = { _ -> },
        )
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "ep-1",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.startClockSyncBurst(endpointId = "ep-1", sampleCount = 3)

        val requests = sentPayloads.mapNotNull { SessionClockSyncBinaryCodec.decodeRequest(it) }
        assertEquals(3, requests.size)
        requests.forEach { request ->
            controller.onClockSyncSampleReceived(
                endpointId = "ep-1",
                sample = SessionClockSyncBinaryResponse(
                    clientSendElapsedNanos = request.clientSendElapsedNanos,
                    hostReceiveElapsedNanos = request.clientSendElapsedNanos + 200L,
                    hostSendElapsedNanos = request.clientSendElapsedNanos + 300L,
                ),
            )
        }

        assertFalse(controller.uiState.value.clockSyncInProgress)
        assertEquals("clock_sync_complete", controller.uiState.value.lastEvent)
        assertNotNull(controller.clockState.value.hostMinusClientElapsedNanos)
    }

    @Test
    fun `auto ticker does not start NTP burst when fresh GPS lock exists`() {
        val sentClockSyncRequests = AtomicInteger(0)
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            sendClockSyncPayload = { _, _, onComplete ->
                sentClockSyncRequests.incrementAndGet()
                onComplete(Result.success(Unit))
            },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
            clockSyncDelay = { _ -> },
        )

        controller.setNetworkRole(SessionNetworkRole.CLIENT)
        controller.setSessionStage(SessionStage.LOBBY)
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "ep-1",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.updateClockState(
            hostSensorMinusElapsedNanos = 500L,
            localSensorMinusElapsedNanos = 200L,
            localGpsUtcOffsetNanos = 1_000L,
            localGpsFixAgeNanos = 1_000_000_000L,
            hostGpsUtcOffsetNanos = 900L,
            hostGpsFixAgeNanos = 1_000_000_000L,
        )

        Thread.sleep(2500)
        assertEquals(0, sentClockSyncRequests.get())
    }

    @Test
    fun `auto ticker starts NTP burst when GPS lock is unavailable and clock lock is stale`() {
        val sentClockSyncRequests = AtomicInteger(0)
        val firstRequestSent = CountDownLatch(1)
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            sendClockSyncPayload = { _, _, onComplete ->
                sentClockSyncRequests.incrementAndGet()
                firstRequestSent.countDown()
                onComplete(Result.success(Unit))
            },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
            clockSyncDelay = { _ -> },
        )

        controller.setNetworkRole(SessionNetworkRole.CLIENT)
        controller.setSessionStage(SessionStage.LOBBY)
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "ep-1",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )

        assertTrue(firstRequestSent.await(3, TimeUnit.SECONDS))
        assertTrue(sentClockSyncRequests.get() >= 1)
    }

    @Test
    fun `in-progress NTP burst eventually completes when samples never return`() {
        val sentClockSyncRequests = AtomicInteger(0)
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            sendClockSyncPayload = { _, _, onComplete ->
                sentClockSyncRequests.incrementAndGet()
                onComplete(Result.success(Unit))
            },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
            clockSyncDelay = { _ -> },
        )

        controller.setNetworkRole(SessionNetworkRole.CLIENT)
        controller.setSessionStage(SessionStage.LOBBY)
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "ep-1",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )

        controller.startClockSyncBurst(endpointId = "ep-1", sampleCount = 3)
        assertTrue(controller.uiState.value.clockSyncInProgress)
        assertEquals(3, sentClockSyncRequests.get())

        controller.updateClockState(
            hostSensorMinusElapsedNanos = 500L,
            localSensorMinusElapsedNanos = 200L,
            localGpsUtcOffsetNanos = 1_000L,
            localGpsFixAgeNanos = 1_000_000_000L,
            hostGpsUtcOffsetNanos = 900L,
            hostGpsFixAgeNanos = 1_000_000_000L,
        )

        Thread.sleep(2500)
        assertFalse(controller.uiState.value.clockSyncInProgress)
        assertEquals(3, sentClockSyncRequests.get())
    }

    @Test
    fun `split trigger is accepted only after start and before stop`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.ingestLocalTrigger("split", splitIndex = 0, triggerSensorNanos = 900L, broadcast = false)
        assertTrue(controller.uiState.value.timeline.hostSplitSensorNanos.isEmpty())

        controller.ingestLocalTrigger("start", splitIndex = 0, triggerSensorNanos = 1_000L, broadcast = false)
        controller.ingestLocalTrigger("split", splitIndex = 0, triggerSensorNanos = 1_400L, broadcast = false)
        controller.ingestLocalTrigger("stop", splitIndex = 0, triggerSensorNanos = 2_000L, broadcast = false)
        controller.ingestLocalTrigger("split", splitIndex = 0, triggerSensorNanos = 2_200L, broadcast = false)

        assertEquals(listOf(1_400L), controller.uiState.value.timeline.hostSplitSensorNanos)
    }

    @Test
    fun `multiple splits append in order and do not finish run`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.ingestLocalTrigger("start", splitIndex = 0, triggerSensorNanos = 1_000L, broadcast = false)
        controller.ingestLocalTrigger("split", splitIndex = 0, triggerSensorNanos = 1_200L, broadcast = false)
        controller.ingestLocalTrigger("split", splitIndex = 0, triggerSensorNanos = 1_700L, broadcast = false)

        val timeline = controller.uiState.value.timeline
        assertEquals(listOf(1_200L, 1_700L), timeline.hostSplitSensorNanos)
        assertNull(timeline.hostStopSensorNanos)
    }

    @Test
    fun `timeline snapshot maps split timestamps in client mode`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.CLIENT)
        controller.updateClockState(
            hostMinusClientElapsedNanos = 100L,
            hostSensorMinusElapsedNanos = 500L,
            localSensorMinusElapsedNanos = 200L,
        )

        val snapshot = SessionTimelineSnapshotMessage(
            hostStartSensorNanos = 1_000L,
            hostStopSensorNanos = 2_000L,
            hostSplitSensorNanos = listOf(1_300L, 1_600L),
            sentElapsedNanos = 10L,
        )

        controller.onNearbyEvent(
            NearbyEvent.PayloadReceived(
                endpointId = "ep-1",
                message = snapshot.toJsonString(),
            ),
        )

        assertEquals(listOf(900L, 1_200L), controller.uiState.value.timeline.hostSplitSensorNanos)
    }

    @Test
    fun `start monitoring gate remains start plus stop without split role`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.HOST)
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "ep-1",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.assignRole("local-device", SessionDeviceRole.START)
        controller.assignRole("ep-1", SessionDeviceRole.STOP)

        assertTrue(controller.canStartMonitoring())
    }

    @Test
    fun `split role assignment is non-exclusive across devices`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.HOST)
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "ep-1",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.assignRole("local-device", SessionDeviceRole.SPLIT)
        controller.assignRole("ep-1", SessionDeviceRole.SPLIT)

        val rolesById = controller.uiState.value.devices.associate { it.id to it.role }
        assertEquals(SessionDeviceRole.SPLIT, rolesById["local-device"])
        assertEquals(SessionDeviceRole.SPLIT, rolesById["ep-1"])
    }

    @Test
    fun `display role assignment is exclusive across devices`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.HOST)
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "ep-1",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.assignRole("local-device", SessionDeviceRole.DISPLAY)
        controller.assignRole("ep-1", SessionDeviceRole.DISPLAY)

        val rolesById = controller.uiState.value.devices.associate { it.id to it.role }
        assertEquals(SessionDeviceRole.UNASSIGNED, rolesById["local-device"])
        assertEquals(SessionDeviceRole.DISPLAY, rolesById["ep-1"])
    }

    @Test
    fun `local motion trigger is ignored when local role is display`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.HOST)
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "ep-1",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.assignRole("local-device", SessionDeviceRole.START)
        controller.assignRole("ep-1", SessionDeviceRole.STOP)
        assertTrue(controller.startMonitoring())
        controller.assignRole("local-device", SessionDeviceRole.DISPLAY)

        controller.onLocalMotionTrigger("motion", splitIndex = 0, triggerSensorNanos = 1_000L)

        assertNull(controller.uiState.value.timeline.hostStartSensorNanos)
        assertNull(controller.uiState.value.timeline.hostStopSensorNanos)
        assertTrue(controller.uiState.value.timeline.hostSplitSensorNanos.isEmpty())
    }

    @Test
    fun `display client applies snapshot timeline without sync mapping`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.CLIENT)
        controller.onNearbyEvent(
            NearbyEvent.PayloadReceived(
                endpointId = "ep-1",
                message = SessionSnapshotMessage(
                    stage = SessionStage.MONITORING,
                    monitoringActive = true,
                    devices = listOf(
                        SessionDevice(
                            id = "local-device",
                            name = "Local",
                            role = SessionDeviceRole.DISPLAY,
                            isLocal = true,
                        ),
                    ),
                    hostStartSensorNanos = 5_000L,
                    hostStopSensorNanos = 9_000L,
                    hostSplitSensorNanos = listOf(6_000L, 8_000L),
                    runId = "run-1",
                    hostSensorMinusElapsedNanos = null,
                    hostGpsUtcOffsetNanos = null,
                    hostGpsFixAgeNanos = null,
                    selfDeviceId = "local-device",
                ).toJsonString(),
            ),
        )

        assertEquals(5_000L, controller.uiState.value.timeline.hostStartSensorNanos)
        assertEquals(9_000L, controller.uiState.value.timeline.hostStopSensorNanos)
        assertEquals(listOf(6_000L, 8_000L), controller.uiState.value.timeline.hostSplitSensorNanos)
        assertEquals("snapshot_applied", controller.uiState.value.lastEvent)
    }

    @Test
    fun `display client accepts timeline snapshot without sync mapping`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.CLIENT)
        controller.onNearbyEvent(
            NearbyEvent.PayloadReceived(
                endpointId = "ep-1",
                message = SessionSnapshotMessage(
                    stage = SessionStage.MONITORING,
                    monitoringActive = true,
                    devices = listOf(
                        SessionDevice(
                            id = "local-device",
                            name = "Local",
                            role = SessionDeviceRole.DISPLAY,
                            isLocal = true,
                        ),
                    ),
                    hostStartSensorNanos = null,
                    hostStopSensorNanos = null,
                    hostSplitSensorNanos = emptyList(),
                    runId = "run-1",
                    hostSensorMinusElapsedNanos = null,
                    hostGpsUtcOffsetNanos = null,
                    hostGpsFixAgeNanos = null,
                    selfDeviceId = "local-device",
                ).toJsonString(),
            ),
        )
        controller.updateClockState(
            hostMinusClientElapsedNanos = null,
            hostSensorMinusElapsedNanos = null,
            localSensorMinusElapsedNanos = null,
            hostGpsUtcOffsetNanos = null,
            localGpsUtcOffsetNanos = null,
        )

        controller.onNearbyEvent(
            NearbyEvent.PayloadReceived(
                endpointId = "ep-1",
                message = SessionTimelineSnapshotMessage(
                    hostStartSensorNanos = 11_000L,
                    hostStopSensorNanos = 17_000L,
                    hostSplitSensorNanos = listOf(13_000L, 15_000L),
                    sentElapsedNanos = 42L,
                ).toJsonString(),
            ),
        )

        assertEquals(11_000L, controller.uiState.value.timeline.hostStartSensorNanos)
        assertEquals(17_000L, controller.uiState.value.timeline.hostStopSensorNanos)
        assertEquals(listOf(13_000L, 15_000L), controller.uiState.value.timeline.hostSplitSensorNanos)
        assertEquals("timeline_snapshot", controller.uiState.value.lastEvent)
    }

    @Test
    fun `host connection results accumulate multiple connected endpoints`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )
        controller.setNetworkRole(SessionNetworkRole.HOST)

        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "ep-1",
                endpointName = "Runner 1",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "ep-2",
                endpointName = "Runner 2",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )

        assertEquals(setOf("ep-1", "ep-2"), controller.uiState.value.connectedEndpoints)
    }

    @Test
    fun `host auto assigns split to extra remote devices after start and stop`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )
        controller.setNetworkRole(SessionNetworkRole.HOST)

        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "ep-1",
                endpointName = "Runner 1",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "ep-2",
                endpointName = "Runner 2",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "ep-3",
                endpointName = "Runner 3",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )

        val rolesById = controller.uiState.value.devices.associate { it.id to it.role }
        assertEquals(SessionDeviceRole.START, rolesById["ep-1"])
        assertEquals(SessionDeviceRole.STOP, rolesById["ep-2"])
        assertEquals(SessionDeviceRole.SPLIT, rolesById["ep-3"])
    }
}
