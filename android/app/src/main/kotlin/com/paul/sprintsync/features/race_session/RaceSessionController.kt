package com.paul.sprintsync.features.race_session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paul.sprintsync.core.clock.ClockDomain
import com.paul.sprintsync.core.models.LastRunResult
import com.paul.sprintsync.core.repositories.LocalRepository
import com.paul.sprintsync.core.services.NearbyEvent
import com.paul.sprintsync.core.services.SessionConnectionsManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

typealias RaceSessionLoadLastRun = suspend () -> LastRunResult?
typealias RaceSessionSaveLastRun = suspend (LastRunResult) -> Unit
typealias RaceSessionSendMessage = (endpointId: String, messageJson: String, onComplete: (Result<Unit>) -> Unit) -> Unit
typealias RaceSessionSendClockSyncPayload = (
    endpointId: String,
    payloadBytes: ByteArray,
    onComplete: (Result<Unit>) -> Unit,
) -> Unit
typealias RaceSessionClockSyncDelay = suspend (delayMillis: Long) -> Unit

private data class AcceptedClockSyncSample(
    val offsetNanos: Long,
    val roundTripNanos: Long,
    val acceptOrder: Int,
)

data class SessionRaceTimeline(
    val hostStartSensorNanos: Long? = null,
    val hostStopSensorNanos: Long? = null,
    val hostSplitSensorNanos: List<Long> = emptyList(),
)

data class RaceSessionClockState(
    val hostMinusClientElapsedNanos: Long? = null,
    val hostSensorMinusElapsedNanos: Long? = null,
    val localSensorMinusElapsedNanos: Long? = null,
    val localGpsUtcOffsetNanos: Long? = null,
    val localGpsFixAgeNanos: Long? = null,
    val hostGpsUtcOffsetNanos: Long? = null,
    val hostGpsFixAgeNanos: Long? = null,
    val lastClockSyncElapsedNanos: Long? = null,
    val hostClockRoundTripNanos: Long? = null,
)

data class RaceSessionUiState(
    val stage: SessionStage = SessionStage.SETUP,
    val operatingMode: SessionOperatingMode = SessionOperatingMode.SINGLE_DEVICE,
    val networkRole: SessionNetworkRole = SessionNetworkRole.NONE,
    val deviceRole: SessionDeviceRole = SessionDeviceRole.UNASSIGNED,
    val monitoringActive: Boolean = false,
    val runId: String? = null,
    val timeline: SessionRaceTimeline = SessionRaceTimeline(),
    val latestCompletedTimeline: SessionRaceTimeline? = null,
    val devices: List<SessionDevice> = emptyList(),
    val discoveredEndpoints: Map<String, String> = emptyMap(),
    val connectedEndpoints: Set<String> = emptySet(),
    val clockSyncInProgress: Boolean = false,
    val lastError: String? = null,
    val lastEvent: String? = null,
)

class RaceSessionController(
    private val loadLastRun: RaceSessionLoadLastRun,
    private val saveLastRun: RaceSessionSaveLastRun,
    private val sendMessage: RaceSessionSendMessage,
    private val sendClockSyncPayload: RaceSessionSendClockSyncPayload,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowElapsedNanos: () -> Long = { ClockDomain.nowElapsedNanos() },
    private val clockSyncDelay: RaceSessionClockSyncDelay = { delayMillis -> kotlinx.coroutines.delay(delayMillis) },
) : ViewModel() {
    companion object {
        private const val MAX_ACCEPTED_ROUND_TRIP_NANOS = 120_000_000L
        private const val DEFAULT_CLOCK_SYNC_SAMPLE_COUNT = 8
        private const val CLOCK_SYNC_BURST_STAGGER_MILLIS = 50L
        private const val CLOCK_SYNC_SAMPLE_EXPIRY_MILLIS = 1_500L
        private const val CLOCK_LOCK_VALIDITY_NANOS = 6_000_000_000L
        private const val GPS_LOCK_VALIDITY_NANOS = 10_000_000_000L
        private const val DEFAULT_LOCAL_DEVICE_ID = "local-device"
        private const val DEFAULT_LOCAL_DEVICE_NAME = "This Device"
    }

    constructor(
        loadLastRun: RaceSessionLoadLastRun,
        saveLastRun: RaceSessionSaveLastRun,
        sendMessage: RaceSessionSendMessage,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        nowElapsedNanos: () -> Long = { ClockDomain.nowElapsedNanos() },
        clockSyncDelay: RaceSessionClockSyncDelay = { delayMillis -> kotlinx.coroutines.delay(delayMillis) },
    ) : this(
        loadLastRun = loadLastRun,
        saveLastRun = saveLastRun,
        sendMessage = sendMessage,
        sendClockSyncPayload = { endpointId, payloadBytes, onComplete ->
            val payload = String(payloadBytes, StandardCharsets.ISO_8859_1)
            sendMessage(endpointId, payload, onComplete)
        },
        ioDispatcher = ioDispatcher,
        nowElapsedNanos = nowElapsedNanos,
        clockSyncDelay = clockSyncDelay,
    )

    constructor(
        localRepository: LocalRepository,
        connectionsManager: SessionConnectionsManager,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        nowElapsedNanos: () -> Long = { ClockDomain.nowElapsedNanos() },
        clockSyncDelay: RaceSessionClockSyncDelay = { delayMillis -> kotlinx.coroutines.delay(delayMillis) },
    ) : this(
        loadLastRun = { localRepository.loadLastRun() },
        saveLastRun = { run -> localRepository.saveLastRun(run) },
        sendMessage = { endpointId, messageJson, onComplete ->
            connectionsManager.sendMessage(endpointId, messageJson, onComplete)
        },
        sendClockSyncPayload = { endpointId, payloadBytes, onComplete ->
            connectionsManager.sendClockSyncPayload(endpointId, payloadBytes, onComplete)
        },
        ioDispatcher = ioDispatcher,
        nowElapsedNanos = nowElapsedNanos,
        clockSyncDelay = clockSyncDelay,
    )

    private val _uiState = MutableStateFlow(
        RaceSessionUiState(
            devices = listOf(
                SessionDevice(
                    id = DEFAULT_LOCAL_DEVICE_ID,
                    name = DEFAULT_LOCAL_DEVICE_NAME,
                    role = SessionDeviceRole.UNASSIGNED,
                    isLocal = true,
                ),
            ),
        ),
    )
    val uiState: StateFlow<RaceSessionUiState> = _uiState.asStateFlow()

    private val _clockState = MutableStateFlow(RaceSessionClockState())
    val clockState: StateFlow<RaceSessionClockState> = _clockState.asStateFlow()

    private val pendingClockSyncSamplesByClientSendNanos = ConcurrentHashMap<Long, Long>()
    private val acceptedClockSyncSamples = mutableListOf<AcceptedClockSyncSample>()
    private val endpointIdByStableDeviceId = mutableMapOf<String, String>()
    private val stableDeviceIdByEndpointId = mutableMapOf<String, String>()
    private var clockSyncBurstDispatchCompleted = false
    private var acceptedClockSampleCounter = 0

    private var localDeviceId = DEFAULT_LOCAL_DEVICE_ID

    init {
        viewModelScope.launch(ioDispatcher) {
            val persisted = loadLastRun() ?: return@launch
            val persistedTimeline = SessionRaceTimeline(
                hostStartSensorNanos = persisted.startedSensorNanos,
                hostStopSensorNanos = persisted.stoppedSensorNanos,
            )
            _uiState.value = _uiState.value.copy(timeline = persistedTimeline)
        }

        viewModelScope.launch(ioDispatcher) {
            while (true) {
                kotlinx.coroutines.delay(2000)
                val state = _uiState.value
                if (state.networkRole == SessionNetworkRole.CLIENT &&
                    (state.stage == SessionStage.LOBBY || state.stage == SessionStage.MONITORING)
                ) {
                    val endpointId = state.connectedEndpoints.firstOrNull()
                    if (endpointId != null &&
                        !state.clockSyncInProgress &&
                        !hasFreshGpsLock() &&
                        !hasFreshClockLock(CLOCK_LOCK_VALIDITY_NANOS / 2)
                    ) {
                        startClockSyncBurst(endpointId)
                    }
                }
            }
        }
    }

    fun setLocalDeviceIdentity(deviceId: String, deviceName: String) {
        if (deviceId.isBlank() || deviceName.isBlank()) {
            return
        }
        localDeviceId = deviceId
        _uiState.value = _uiState.value.copy(
            devices = _uiState.value.devices
                .filterNot { it.isLocal }
                .plus(
                    SessionDevice(
                        id = deviceId,
                        name = deviceName,
                        role = localDeviceRole(),
                        cameraFacing = localCameraFacing(),
                        isLocal = true,
                    ),
                )
                .distinctBy { it.id },
            deviceRole = localDeviceRole(),
        )
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        }
    }

    fun setSessionStage(stage: SessionStage) {
        _uiState.value = _uiState.value.copy(stage = stage)
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        }
    }

    fun setNetworkRole(role: SessionNetworkRole) {
        endpointIdByStableDeviceId.clear()
        stableDeviceIdByEndpointId.clear()
        val local = ensureLocalDevice(
            SessionDevice(
                id = localDeviceId,
                name = localDeviceName(),
                role = SessionDeviceRole.UNASSIGNED,
                isLocal = true,
            ),
            current = _uiState.value.devices,
        )
        val initialStage = if (role == SessionNetworkRole.HOST) SessionStage.LOBBY else SessionStage.SETUP
        _uiState.value = _uiState.value.copy(
            operatingMode = SessionOperatingMode.SINGLE_DEVICE,
            networkRole = role,
            stage = initialStage,
            monitoringActive = false,
            runId = null,
            timeline = SessionRaceTimeline(),
            latestCompletedTimeline = null,
            devices = local,
            connectedEndpoints = emptySet(),
            deviceRole = localDeviceRole(),
            lastError = null,
        )
    }

    fun startSingleDeviceMonitoring() {
        endpointIdByStableDeviceId.clear()
        stableDeviceIdByEndpointId.clear()
        val local = SessionDevice(
            id = localDeviceId,
            name = localDeviceName(),
            role = SessionDeviceRole.START,
            cameraFacing = SessionCameraFacing.FRONT,
            isLocal = true,
        )
        _uiState.value = _uiState.value.copy(
            operatingMode = SessionOperatingMode.SINGLE_DEVICE,
            networkRole = SessionNetworkRole.NONE,
            stage = SessionStage.MONITORING,
            monitoringActive = true,
            runId = UUID.randomUUID().toString(),
            timeline = SessionRaceTimeline(),
            devices = ensureLocalDevice(local, _uiState.value.devices),
            discoveredEndpoints = emptyMap(),
            connectedEndpoints = emptySet(),
            deviceRole = SessionDeviceRole.START,
            lastError = null,
            lastEvent = "single_device_started",
        )
    }

    fun stopSingleDeviceMonitoring() {
        val local = ensureLocalDevice(
            SessionDevice(
                id = localDeviceId,
                name = localDeviceName(),
                role = SessionDeviceRole.UNASSIGNED,
                isLocal = true,
            ),
            _uiState.value.devices,
        )
        _uiState.value = _uiState.value.copy(
            operatingMode = SessionOperatingMode.SINGLE_DEVICE,
            networkRole = SessionNetworkRole.NONE,
            stage = SessionStage.SETUP,
            monitoringActive = false,
            runId = null,
            timeline = SessionRaceTimeline(),
            devices = local,
            discoveredEndpoints = emptyMap(),
            connectedEndpoints = emptySet(),
            deviceRole = SessionDeviceRole.UNASSIGNED,
            lastError = null,
            lastEvent = "single_device_stopped",
        )
    }

    fun startDisplayHostMode() {
        endpointIdByStableDeviceId.clear()
        stableDeviceIdByEndpointId.clear()
        val local = ensureLocalDevice(
            SessionDevice(
                id = localDeviceId,
                name = localDeviceName(),
                role = SessionDeviceRole.UNASSIGNED,
                isLocal = true,
            ),
            _uiState.value.devices,
        )
        _uiState.value = _uiState.value.copy(
            operatingMode = SessionOperatingMode.DISPLAY_HOST,
            networkRole = SessionNetworkRole.NONE,
            stage = SessionStage.MONITORING,
            monitoringActive = false,
            runId = null,
            timeline = SessionRaceTimeline(),
            devices = local,
            discoveredEndpoints = emptyMap(),
            connectedEndpoints = emptySet(),
            deviceRole = SessionDeviceRole.UNASSIGNED,
            lastError = null,
            lastEvent = "display_host_started",
        )
    }

    fun stopDisplayHostMode() {
        _uiState.value = _uiState.value.copy(
            stage = SessionStage.SETUP,
            monitoringActive = false,
            discoveredEndpoints = emptyMap(),
            connectedEndpoints = emptySet(),
            lastError = null,
            lastEvent = "display_host_stopped",
        )
    }

    fun setDeviceRole(role: SessionDeviceRole) {
        assignRole(localDeviceId, role)
    }

    fun onNearbyEvent(event: NearbyEvent) {
        when (event) {
            is NearbyEvent.EndpointFound -> {
                _uiState.value = _uiState.value.copy(
                    discoveredEndpoints = _uiState.value.discoveredEndpoints + (event.endpointId to event.endpointName),
                    lastEvent = "endpoint_found",
                )
            }

            is NearbyEvent.EndpointLost -> {
                _uiState.value = _uiState.value.copy(
                    discoveredEndpoints = _uiState.value.discoveredEndpoints - event.endpointId,
                    lastEvent = "endpoint_lost",
                )
            }

            is NearbyEvent.ConnectionResult -> {
                handleConnectionResult(event)
            }

            is NearbyEvent.EndpointDisconnected -> {
                clearIdentityMappingForEndpoint(event.endpointId)
                val nextConnected = _uiState.value.connectedEndpoints - event.endpointId
                val nextDevices = ensureLocalDevice(
                    localDeviceFromState(),
                    pruneOrphanedNonLocalDevices(
                        devices = _uiState.value.devices,
                        connectedEndpoints = nextConnected,
                    ),
                )

                var nextStage = _uiState.value.stage
                var nextRole = _uiState.value.networkRole

                if (_uiState.value.networkRole == SessionNetworkRole.CLIENT && nextConnected.isEmpty()) {
                    nextStage = SessionStage.SETUP
                    nextRole = SessionNetworkRole.NONE
                }

                _uiState.value = _uiState.value.copy(
                    connectedEndpoints = nextConnected,
                    devices = nextDevices,
                    stage = nextStage,
                    networkRole = nextRole,
                    deviceRole = localDeviceRole(),
                    lastEvent = "endpoint_disconnected",
                )
                if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
                    broadcastSnapshotIfHost()
                }
            }

            is NearbyEvent.PayloadReceived -> {
                handleIncomingPayload(endpointId = event.endpointId, rawMessage = event.message)
            }

            is NearbyEvent.ClockSyncSampleReceived -> {
                onClockSyncSampleReceived(endpointId = event.endpointId, sample = event.sample)
            }

            is NearbyEvent.Error -> {
                _uiState.value = _uiState.value.copy(lastError = event.message, lastEvent = "error")
            }
        }
    }

    fun onClockSyncSampleReceived(endpointId: String, sample: SessionClockSyncBinaryResponse) {
        if (!_uiState.value.connectedEndpoints.contains(endpointId)) {
            return
        }
        handleClockSyncResponseSample(sample)
    }

    fun assignRole(deviceId: String, role: SessionDeviceRole) {
        var nextDevices = _uiState.value.devices
        if (
            role == SessionDeviceRole.START ||
            role == SessionDeviceRole.STOP ||
            role == SessionDeviceRole.DISPLAY
        ) {
            nextDevices = nextDevices.map { existing ->
                if (existing.id != deviceId && existing.role == role) {
                    existing.copy(role = SessionDeviceRole.UNASSIGNED)
                } else {
                    existing
                }
            }
        }
        nextDevices = nextDevices.map { existing ->
            if (existing.id == deviceId) {
                existing.copy(role = role)
            } else {
                existing
            }
        }
        _uiState.value = _uiState.value.copy(
            devices = nextDevices,
            deviceRole = localDeviceRole(),
            lastEvent = "role_assigned",
        )
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        }
    }

    fun assignCameraFacing(deviceId: String, facing: SessionCameraFacing) {
        val nextDevices = _uiState.value.devices.map { existing ->
            if (existing.id == deviceId) {
                existing.copy(cameraFacing = facing)
            } else {
                existing
            }
        }
        _uiState.value = _uiState.value.copy(devices = nextDevices)
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        }
    }

    fun startMonitoring(): Boolean {
        if (_uiState.value.networkRole == SessionNetworkRole.HOST && !canStartMonitoring()) {
            _uiState.value = _uiState.value.copy(lastError = "Assign start and stop devices before monitoring")
            return false
        }

        val nextRunId = UUID.randomUUID().toString()
        val hostOffset = if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            _clockState.value.hostSensorMinusElapsedNanos ?: _clockState.value.localSensorMinusElapsedNanos ?: 0L
        } else {
            _clockState.value.hostSensorMinusElapsedNanos
        }
        _clockState.value = _clockState.value.copy(hostSensorMinusElapsedNanos = hostOffset)
        _uiState.value = _uiState.value.copy(
            stage = SessionStage.MONITORING,
            monitoringActive = true,
            runId = nextRunId,
            timeline = SessionRaceTimeline(),
            lastError = null,
        )

        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        }
        return true
    }

    fun stopMonitoring() {
        _uiState.value = _uiState.value.copy(
            stage = SessionStage.LOBBY,
            monitoringActive = false,
            lastError = null,
        )
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        }
    }

    fun stopHostingAndReturnToSetup() {
        if (_uiState.value.networkRole != SessionNetworkRole.HOST) {
            return
        }
        if (_uiState.value.monitoringActive) {
            stopMonitoring()
        }
        setNetworkRole(SessionNetworkRole.NONE)
    }

    fun resetRun() {
        val nextRunId = if (_uiState.value.monitoringActive) UUID.randomUUID().toString() else null
        _uiState.value = _uiState.value.copy(
            timeline = SessionRaceTimeline(),
            latestCompletedTimeline = null,
            runId = nextRunId,
            lastEvent = "run_reset",
        )
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        }
    }

    fun onLocalMotionTrigger(triggerType: String, splitIndex: Int, triggerSensorNanos: Long) {
        if (!_uiState.value.monitoringActive) {
            return
        }

        if (_uiState.value.operatingMode == SessionOperatingMode.SINGLE_DEVICE) {
            handleSingleDeviceTrigger(triggerSensorNanos)
            return
        }

        val role = localDeviceRole()
        if (role == SessionDeviceRole.UNASSIGNED) {
            return
        }
        if (role == SessionDeviceRole.DISPLAY) {
            return
        }

        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            val mappedType = roleToTriggerType(role)
            if (mappedType == null) {
                return
            }
            ingestLocalTrigger(
                triggerType = mappedType,
                splitIndex = 0,
                triggerSensorNanos = triggerSensorNanos,
                broadcast = true,
            )
            broadcastSnapshotIfHost()
            return
        }

        if (_uiState.value.networkRole == SessionNetworkRole.CLIENT) {
            val request = SessionTriggerRequestMessage(
                role = role,
                triggerSensorNanos = triggerSensorNanos,
                mappedHostSensorNanos = mapClientSensorToHostSensor(triggerSensorNanos),
            ).toJsonString()
            sendToHost(request)
        }
    }

    fun totalDeviceCount(): Int {
        return _uiState.value.devices.size
    }

    fun canShowSplitControls(): Boolean {
        val timeline = _uiState.value.timeline
        if (!_uiState.value.monitoringActive) {
            return false
        }
        if (timeline.hostStartSensorNanos == null || timeline.hostStopSensorNanos != null) {
            return false
        }
        return localDeviceRole() == SessionDeviceRole.SPLIT || _uiState.value.devices.any { it.role == SessionDeviceRole.SPLIT }
    }

    fun canStartMonitoring(): Boolean {
        if (_uiState.value.networkRole != SessionNetworkRole.HOST) {
            return false
        }
        val roles = _uiState.value.devices.map { it.role }
        return roles.contains(SessionDeviceRole.START) && roles.contains(SessionDeviceRole.STOP)
    }

    fun localDeviceRole(): SessionDeviceRole {
        return localDeviceFromState().role
    }

    fun localCameraFacing(): SessionCameraFacing {
        return localDeviceFromState().cameraFacing
    }

    fun startClockSyncBurst(endpointId: String, sampleCount: Int = DEFAULT_CLOCK_SYNC_SAMPLE_COUNT) {
        if (!_uiState.value.connectedEndpoints.contains(endpointId)) {
            _uiState.value = _uiState.value.copy(lastError = "Clock sync ignored: endpoint not connected")
            return
        }
        _uiState.value = _uiState.value.copy(clockSyncInProgress = true, lastError = null)
        pendingClockSyncSamplesByClientSendNanos.clear()
        acceptedClockSyncSamples.clear()
        clockSyncBurstDispatchCompleted = false
        acceptedClockSampleCounter = 0

        val totalSamples = sampleCount.coerceAtLeast(3)
        viewModelScope.launch(ioDispatcher) {
            repeat(totalSamples) { sampleIndex ->
                if (sampleIndex > 0) {
                    clockSyncDelay(CLOCK_SYNC_BURST_STAGGER_MILLIS)
                }
                sendClockSyncRequest(endpointId)
            }
            clockSyncBurstDispatchCompleted = true
            maybeFinishClockSyncBurst()
        }
    }

    private fun sendClockSyncRequest(endpointId: String) {
        val sendElapsedNanos = nextUniqueClientSendElapsedNanos()
        pendingClockSyncSamplesByClientSendNanos[sendElapsedNanos] = sendElapsedNanos
        scheduleClockSyncSampleExpiry(sendElapsedNanos)
        val requestBytes = SessionClockSyncBinaryCodec.encodeRequest(
            SessionClockSyncBinaryRequest(clientSendElapsedNanos = sendElapsedNanos),
        )
        sendClockSyncPayload(endpointId, requestBytes) { result ->
            result.exceptionOrNull()?.let { error ->
                pendingClockSyncSamplesByClientSendNanos.remove(sendElapsedNanos)
                _uiState.value = _uiState.value.copy(
                    lastError = "Clock sync send failed: ${error.localizedMessage ?: "unknown"}",
                )
                maybeFinishClockSyncBurst()
            }
        }
    }

    private fun scheduleClockSyncSampleExpiry(sendElapsedNanos: Long) {
        viewModelScope.launch(ioDispatcher) {
            kotlinx.coroutines.delay(CLOCK_SYNC_SAMPLE_EXPIRY_MILLIS)
            val removed = pendingClockSyncSamplesByClientSendNanos.remove(sendElapsedNanos)
            if (removed != null) {
                maybeFinishClockSyncBurst()
            }
        }
    }

    private fun nextUniqueClientSendElapsedNanos(): Long {
        var candidate = nowElapsedNanos()
        while (pendingClockSyncSamplesByClientSendNanos.containsKey(candidate)) {
            candidate += 1L
        }
        return candidate
    }

    fun ingestLocalTrigger(triggerType: String, splitIndex: Int, triggerSensorNanos: Long, broadcast: Boolean = true) {
        val updated = applyTrigger(
            timeline = _uiState.value.timeline,
            triggerType = triggerType,
            splitIndex = splitIndex,
            triggerSensorNanos = triggerSensorNanos,
        ) ?: return

        _uiState.value = _uiState.value.copy(
            timeline = updated,
            lastEvent = "local_trigger",
        )

        maybePersistCompletedRun(updated)

        if (!broadcast) {
            return
        }
        val message = SessionTriggerMessage(
            triggerType = triggerType,
            triggerSensorNanos = triggerSensorNanos,
        ).toJsonString()
        broadcastToConnected(message)
        broadcastTimelineSnapshot(updated)
    }

    fun updateClockState(
        hostMinusClientElapsedNanos: Long? = _clockState.value.hostMinusClientElapsedNanos,
        hostSensorMinusElapsedNanos: Long? = _clockState.value.hostSensorMinusElapsedNanos,
        localSensorMinusElapsedNanos: Long? = _clockState.value.localSensorMinusElapsedNanos,
        localGpsUtcOffsetNanos: Long? = _clockState.value.localGpsUtcOffsetNanos,
        localGpsFixAgeNanos: Long? = _clockState.value.localGpsFixAgeNanos,
        hostGpsUtcOffsetNanos: Long? = _clockState.value.hostGpsUtcOffsetNanos,
        hostGpsFixAgeNanos: Long? = _clockState.value.hostGpsFixAgeNanos,
        lastClockSyncElapsedNanos: Long? = _clockState.value.lastClockSyncElapsedNanos,
        hostClockRoundTripNanos: Long? = _clockState.value.hostClockRoundTripNanos,
    ) {
        val previousHostOffset = _clockState.value.hostSensorMinusElapsedNanos

        _clockState.value = RaceSessionClockState(
            hostMinusClientElapsedNanos = hostMinusClientElapsedNanos,
            hostSensorMinusElapsedNanos = hostSensorMinusElapsedNanos,
            localSensorMinusElapsedNanos = localSensorMinusElapsedNanos,
            localGpsUtcOffsetNanos = localGpsUtcOffsetNanos,
            localGpsFixAgeNanos = localGpsFixAgeNanos,
            hostGpsUtcOffsetNanos = hostGpsUtcOffsetNanos,
            hostGpsFixAgeNanos = hostGpsFixAgeNanos,
            lastClockSyncElapsedNanos = lastClockSyncElapsedNanos,
            hostClockRoundTripNanos = hostClockRoundTripNanos,
        )

        // If we are the host and our camera just booted or heavily drifted/restarted, inform clients so they can map.
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            val oldVal = previousHostOffset ?: 0L
            val newVal = hostSensorMinusElapsedNanos ?: 0L
            if (previousHostOffset == null && hostSensorMinusElapsedNanos != null) {
                broadcastSnapshotIfHost()
            } else if (previousHostOffset != null && hostSensorMinusElapsedNanos != null) {
                if (kotlin.math.abs(newVal - oldVal) > 50_000_000L) {
                    broadcastSnapshotIfHost()
                }
            }
        }
    }

    fun mapClientSensorToHostSensor(clientSensorNanos: Long): Long? {
        val state = _clockState.value
        val hostSensorMinusElapsedNanos = state.hostSensorMinusElapsedNanos ?: return null
        val hostMinusClientElapsedNanos = currentHostMinusClientElapsedNanos() ?: return null
        val localSensorMinusElapsedNanos = state.localSensorMinusElapsedNanos ?: return null

        val clientElapsedNanos = ClockDomain.sensorToElapsedNanos(
            sensorNanos = clientSensorNanos,
            sensorMinusElapsedNanos = localSensorMinusElapsedNanos,
        )
        val hostElapsedNanos = clientElapsedNanos + hostMinusClientElapsedNanos
        return ClockDomain.elapsedToSensorNanos(
            elapsedNanos = hostElapsedNanos,
            sensorMinusElapsedNanos = hostSensorMinusElapsedNanos,
        )
    }

    fun mapHostSensorToLocalSensor(hostSensorNanos: Long): Long? {
        val state = _clockState.value
        val hostSensorMinusElapsedNanos = state.hostSensorMinusElapsedNanos ?: return null
        val hostMinusClientElapsedNanos = currentHostMinusClientElapsedNanos() ?: return null
        val localSensorMinusElapsedNanos = state.localSensorMinusElapsedNanos ?: return null

        val hostElapsedNanos = ClockDomain.sensorToElapsedNanos(
            sensorNanos = hostSensorNanos,
            sensorMinusElapsedNanos = hostSensorMinusElapsedNanos,
        )
        val clientElapsedNanos = hostElapsedNanos - hostMinusClientElapsedNanos
        return ClockDomain.elapsedToSensorNanos(
            elapsedNanos = clientElapsedNanos,
            sensorMinusElapsedNanos = localSensorMinusElapsedNanos,
        )
    }

    fun computeGpsFixAgeNanos(gpsFixElapsedRealtimeNanos: Long?): Long? {
        return ClockDomain.computeGpsFixAgeNanos(gpsFixElapsedRealtimeNanos)
    }

    fun estimateLocalSensorNanosNow(): Long {
        val now = ClockDomain.nowElapsedNanos()
        val localSensorMinusElapsedNanos = _clockState.value.localSensorMinusElapsedNanos
            ?: return now
        return ClockDomain.elapsedToSensorNanos(
            elapsedNanos = now,
            sensorMinusElapsedNanos = localSensorMinusElapsedNanos,
        )
    }

    fun hasFreshClockLock(maxAgeNanos: Long = CLOCK_LOCK_VALIDITY_NANOS): Boolean {
        val lockAt = _clockState.value.lastClockSyncElapsedNanos ?: return false
        return nowElapsedNanos() - lockAt <= maxAgeNanos
    }

    fun hasFreshGpsLock(maxAgeNanos: Long = GPS_LOCK_VALIDITY_NANOS): Boolean {
        val state = _clockState.value
        if (state.localSensorMinusElapsedNanos == null || state.hostSensorMinusElapsedNanos == null) {
            return false
        }
        if (state.localGpsUtcOffsetNanos == null || state.hostGpsUtcOffsetNanos == null) {
            return false
        }
        val localFixAge = state.localGpsFixAgeNanos ?: return false
        val hostFixAge = state.hostGpsFixAgeNanos ?: return false
        if (localFixAge < 0L || localFixAge > maxAgeNanos) {
            return false
        }
        if (hostFixAge < 0L || hostFixAge > maxAgeNanos) {
            return false
        }
        return true
    }

    fun hasFreshAnyClockLock(): Boolean {
        return hasFreshGpsLock() || hasFreshClockLock()
    }

    private fun gpsHostMinusClientElapsedNanosIfFresh(): Long? {
        if (!hasFreshGpsLock()) {
            return null
        }
        val state = _clockState.value
        val localGpsUtcOffsetNanos = state.localGpsUtcOffsetNanos ?: return null
        val hostGpsUtcOffsetNanos = state.hostGpsUtcOffsetNanos ?: return null
        return localGpsUtcOffsetNanos - hostGpsUtcOffsetNanos
    }

    private fun currentHostMinusClientElapsedNanos(): Long? {
        return gpsHostMinusClientElapsedNanosIfFresh()
            ?: _clockState.value.hostMinusClientElapsedNanos
    }

    private fun handleIncomingPayload(endpointId: String, rawMessage: String) {
        SessionDeviceIdentityMessage.tryParse(rawMessage)?.let { identity ->
            handleDeviceIdentity(endpointId, identity)
            return
        }

        SessionSnapshotMessage.tryParse(rawMessage)?.let { snapshot ->
            applySnapshot(snapshot)
            return
        }

        SessionTriggerRequestMessage.tryParse(rawMessage)?.let { request ->
            handleTriggerRequest(request)
            return
        }

        SessionTriggerMessage.tryParse(rawMessage)?.let { trigger ->
            val triggerSensorNanos = if (_uiState.value.networkRole == SessionNetworkRole.CLIENT) {
                val mapped = mapHostSensorToLocalSensor(trigger.triggerSensorNanos)
                if (mapped == null) {
                    _uiState.value = _uiState.value.copy(lastEvent = "trigger_dropped_unsynced")
                    return
                }
                mapped
            } else {
                trigger.triggerSensorNanos
            }
            ingestLocalTrigger(
                triggerType = trigger.triggerType,
                splitIndex = 0,
                triggerSensorNanos = triggerSensorNanos,
                broadcast = false,
            )
            return
        }

        SessionTimelineSnapshotMessage.tryParse(rawMessage)?.let { snapshot ->
            ingestTimelineSnapshot(snapshot)
            return
        }
    }

    private fun handleConnectionResult(event: NearbyEvent.ConnectionResult) {
        val nextConnected = if (event.connected) {
            _uiState.value.connectedEndpoints + event.endpointId
        } else {
            _uiState.value.connectedEndpoints - event.endpointId
        }
        if (!event.connected) {
            clearIdentityMappingForEndpoint(event.endpointId)
        }
        val nextDevices = if (event.connected) {
            val endpointName = event.endpointName
                ?: _uiState.value.discoveredEndpoints[event.endpointId]
                ?: event.endpointId
            val knownStableDeviceId = stableDeviceIdByEndpointId[event.endpointId]
            val stableEndpoint = knownStableDeviceId?.let { endpointIdByStableDeviceId[it] }
            val stableEntry = stableEndpoint?.let { stableId ->
                _uiState.value.devices.firstOrNull { existing -> !existing.isLocal && existing.id == stableId }
            }
            val existingForEndpoint = _uiState.value.devices.firstOrNull { existing ->
                !existing.isLocal && existing.id == event.endpointId
            }
            val preserved = stableEntry ?: existingForEndpoint
            val reconciled = (
                preserved ?: SessionDevice(
                    id = event.endpointId,
                    name = endpointName,
                    role = SessionDeviceRole.UNASSIGNED,
                    isLocal = false,
                )
                ).copy(
                id = event.endpointId,
                name = endpointName,
                isLocal = false,
            )
            val dedupedDevices = _uiState.value.devices.filterNot { existing ->
                !existing.isLocal && (
                    existing.id == event.endpointId ||
                        (stableEndpoint != null && stableEndpoint != event.endpointId && existing.id == stableEndpoint)
                    )
            } + reconciled
            ensureLocalDevice(
                localDeviceFromState(),
                pruneOrphanedNonLocalDevices(
                    devices = dedupedDevices,
                    connectedEndpoints = nextConnected,
                ),
            )
        } else {
            ensureLocalDevice(
                localDeviceFromState(),
                pruneOrphanedNonLocalDevices(
                    devices = _uiState.value.devices,
                    connectedEndpoints = nextConnected,
                ),
            )
        }

        val devicesWithDefaults = if (event.connected && _uiState.value.networkRole == SessionNetworkRole.HOST) {
            applyJoinOrderAutoRoleDefaults(nextDevices)
        } else {
            nextDevices
        }

        _uiState.value = _uiState.value.copy(
            connectedEndpoints = nextConnected,
            devices = devicesWithDefaults,
            deviceRole = localDeviceRole(),
            lastError = if (event.connected) null else (event.statusMessage ?: "Connection failed"),
            lastEvent = "connection_result",
        )

        if (event.connected) {
            sendIdentityHandshake(event.endpointId)
        }
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        }
    }

    private fun handleTriggerRequest(request: SessionTriggerRequestMessage) {
        if (_uiState.value.networkRole != SessionNetworkRole.HOST || !_uiState.value.monitoringActive) {
            return
        }
        val mappedType = roleToTriggerType(request.role)
        if (mappedType == null) {
            return
        }
        val hostSensorNanos = request.mappedHostSensorNanos ?: request.triggerSensorNanos
        ingestLocalTrigger(
            triggerType = mappedType,
            splitIndex = 0,
            triggerSensorNanos = hostSensorNanos,
            broadcast = true,
        )
        broadcastSnapshotIfHost()
    }

    private fun applySnapshot(snapshot: SessionSnapshotMessage) {
        if (_uiState.value.networkRole != SessionNetworkRole.CLIENT) {
            return
        }

        updateClockState(
            hostSensorMinusElapsedNanos = snapshot.hostSensorMinusElapsedNanos
                ?: _clockState.value.hostSensorMinusElapsedNanos,
            hostGpsUtcOffsetNanos = snapshot.hostGpsUtcOffsetNanos
                ?: _clockState.value.hostGpsUtcOffsetNanos,
            hostGpsFixAgeNanos = snapshot.hostGpsFixAgeNanos
                ?: _clockState.value.hostGpsFixAgeNanos,
        )

        val resolvedSelfId = snapshot.selfDeviceId ?: localDeviceId
        localDeviceId = resolvedSelfId
        val mappedDevices = snapshot.devices.map { device ->
            device.copy(isLocal = device.id == resolvedSelfId)
        }

        val localRoleFromSnapshot =
            mappedDevices.firstOrNull { it.id == resolvedSelfId }?.role ?: SessionDeviceRole.UNASSIGNED
        val shouldUseHostTimelineDirectly = localRoleFromSnapshot == SessionDeviceRole.DISPLAY
        val mappedStart = if (shouldUseHostTimelineDirectly) {
            snapshot.hostStartSensorNanos
        } else {
            snapshot.hostStartSensorNanos?.let { mapHostSensorToLocalSensor(it) }
        }
        val mappedStop = if (shouldUseHostTimelineDirectly) {
            snapshot.hostStopSensorNanos
        } else {
            snapshot.hostStopSensorNanos?.let { mapHostSensorToLocalSensor(it) }
        }
        val mappedSplits = if (shouldUseHostTimelineDirectly) {
            snapshot.hostSplitSensorNanos
        } else {
            snapshot.hostSplitSensorNanos.mapNotNull { hostSplit ->
                mapHostSensorToLocalSensor(hostSplit)
            }
        }
        val mappingAvailable = if (shouldUseHostTimelineDirectly) {
            true
        } else {
            (snapshot.hostStartSensorNanos == null || mappedStart != null) &&
                (snapshot.hostStopSensorNanos == null || mappedStop != null) &&
                (snapshot.hostSplitSensorNanos.size == mappedSplits.size)
        }
        val timeline = if (mappingAvailable) {
            SessionRaceTimeline(
                hostStartSensorNanos = mappedStart,
                hostStopSensorNanos = mappedStop,
                hostSplitSensorNanos = mappedSplits,
            )
        } else {
            _uiState.value.timeline
        }
        val snapshotEvent = if (mappingAvailable) {
            "snapshot_applied"
        } else {
            "snapshot_applied_unsynced_timeline_ignored"
        }

        _uiState.value = _uiState.value.copy(
            stage = snapshot.stage,
            monitoringActive = snapshot.monitoringActive,
            runId = snapshot.runId,
            devices = ensureLocalDevice(
                SessionDevice(
                    id = resolvedSelfId,
                    name = mappedDevices.firstOrNull { it.id == resolvedSelfId }?.name ?: localDeviceName(),
                    role = mappedDevices.firstOrNull { it.id == resolvedSelfId }?.role ?: SessionDeviceRole.UNASSIGNED,
                    cameraFacing = mappedDevices.firstOrNull { it.id == resolvedSelfId }?.cameraFacing ?: SessionCameraFacing.REAR,
                    isLocal = true,
                ),
                mappedDevices,
            ),
            deviceRole = localRoleFromSnapshot,
            timeline = timeline,
            lastEvent = snapshotEvent,
            lastError = null,
        )

        maybePersistCompletedRun(timeline)
    }

    private fun handleClockSyncResponseSample(response: SessionClockSyncBinaryResponse) {
        val receiveElapsedNanos = nowElapsedNanos()
        val sentElapsedNanos = pendingClockSyncSamplesByClientSendNanos.remove(response.clientSendElapsedNanos)
            ?: return
        val roundTripNanos = receiveElapsedNanos - sentElapsedNanos
        if (roundTripNanos > MAX_ACCEPTED_ROUND_TRIP_NANOS) {
            maybeFinishClockSyncBurst()
            return
        }
        val offset = (
            (response.hostReceiveElapsedNanos - response.clientSendElapsedNanos) +
                (response.hostSendElapsedNanos - receiveElapsedNanos)
            ) / 2L
        acceptedClockSyncSamples += AcceptedClockSyncSample(
            offsetNanos = offset,
            roundTripNanos = roundTripNanos,
            acceptOrder = acceptedClockSampleCounter++,
        )
        maybeFinishClockSyncBurst()
    }

    private fun maybeFinishClockSyncBurst() {
        if (!clockSyncBurstDispatchCompleted) {
            return
        }
        if (pendingClockSyncSamplesByClientSendNanos.isNotEmpty()) {
            return
        }
        val selectedSample = acceptedClockSyncSamples.minWithOrNull(
            compareBy<AcceptedClockSyncSample> { it.roundTripNanos }
                .thenBy { it.acceptOrder },
        )
        if (selectedSample != null) {
            updateClockState(
                hostMinusClientElapsedNanos = selectedSample.offsetNanos,
                hostClockRoundTripNanos = selectedSample.roundTripNanos,
                lastClockSyncElapsedNanos = nowElapsedNanos(),
            )
            _uiState.value = _uiState.value.copy(clockSyncInProgress = false, lastEvent = "clock_sync_complete")
        } else {
            _uiState.value = _uiState.value.copy(
                clockSyncInProgress = false,
                lastError = "Clock sync failed: no acceptable samples",
            )
        }
        acceptedClockSyncSamples.clear()
        clockSyncBurstDispatchCompleted = false
    }

    private fun ingestTimelineSnapshot(snapshot: SessionTimelineSnapshotMessage) {
        val localTimeline = if (_uiState.value.networkRole == SessionNetworkRole.CLIENT) {
            if (localDeviceRole() == SessionDeviceRole.DISPLAY) {
                SessionRaceTimeline(
                    hostStartSensorNanos = snapshot.hostStartSensorNanos,
                    hostStopSensorNanos = snapshot.hostStopSensorNanos,
                    hostSplitSensorNanos = snapshot.hostSplitSensorNanos,
                )
            } else {
            val localStart = snapshot.hostStartSensorNanos?.let { hostStart ->
                mapHostSensorToLocalSensor(hostStart)
            }
            if (snapshot.hostStartSensorNanos != null && localStart == null) {
                _uiState.value = _uiState.value.copy(lastEvent = "timeline_snapshot_dropped_unsynced")
                return
            }
            val localStop = snapshot.hostStopSensorNanos?.let { hostStop ->
                mapHostSensorToLocalSensor(hostStop)
            }
            if (snapshot.hostStopSensorNanos != null && localStop == null) {
                _uiState.value = _uiState.value.copy(lastEvent = "timeline_snapshot_dropped_unsynced")
                return
            }
            val localSplits = mutableListOf<Long>()
            for (hostSplit in snapshot.hostSplitSensorNanos) {
                val mapped = mapHostSensorToLocalSensor(hostSplit)
                if (mapped == null) {
                    _uiState.value = _uiState.value.copy(lastEvent = "timeline_snapshot_dropped_unsynced")
                    return
                }
                localSplits += mapped
            }
            SessionRaceTimeline(
                hostStartSensorNanos = localStart,
                hostStopSensorNanos = localStop,
                hostSplitSensorNanos = localSplits,
            )
            }
        } else {
            SessionRaceTimeline(
                hostStartSensorNanos = snapshot.hostStartSensorNanos,
                hostStopSensorNanos = snapshot.hostStopSensorNanos,
                hostSplitSensorNanos = snapshot.hostSplitSensorNanos,
            )
        }
        _uiState.value = _uiState.value.copy(timeline = localTimeline, lastEvent = "timeline_snapshot")
        maybePersistCompletedRun(localTimeline)
    }

    private fun applyTrigger(
        timeline: SessionRaceTimeline,
        triggerType: String,
        splitIndex: Int,
        triggerSensorNanos: Long,
    ): SessionRaceTimeline? {
        return when (triggerType.lowercase()) {
            "start" -> {
                if (timeline.hostStartSensorNanos != null) {
                    null
                } else {
                    timeline.copy(hostStartSensorNanos = triggerSensorNanos)
                }
            }

            "stop" -> {
                if (timeline.hostStartSensorNanos == null || timeline.hostStopSensorNanos != null) {
                    null
                } else {
                    timeline.copy(hostStopSensorNanos = triggerSensorNanos)
                }
            }

            "split" -> {
                if (timeline.hostStartSensorNanos == null || timeline.hostStopSensorNanos != null) {
                    null
                } else {
                    val lastMarker = timeline.hostSplitSensorNanos.lastOrNull() ?: timeline.hostStartSensorNanos
                    if (triggerSensorNanos <= lastMarker) {
                        null
                    } else {
                        timeline.copy(hostSplitSensorNanos = timeline.hostSplitSensorNanos + triggerSensorNanos)
                    }
                }
            }

            else -> null
        }
    }

    private fun handleSingleDeviceTrigger(triggerSensorNanos: Long) {
        val current = _uiState.value.timeline
        if (current.hostStartSensorNanos == null) {
            _uiState.value = _uiState.value.copy(
                timeline = current.copy(hostStartSensorNanos = triggerSensorNanos),
                runId = UUID.randomUUID().toString(),
                lastEvent = "single_device_start",
            )
            return
        }
        if (current.hostStopSensorNanos != null) {
            return
        }
        if (triggerSensorNanos <= current.hostStartSensorNanos) {
            return
        }
        val completed = current.copy(hostStopSensorNanos = triggerSensorNanos)
        maybePersistCompletedRun(completed)
        _uiState.value = _uiState.value.copy(
            timeline = SessionRaceTimeline(),
            latestCompletedTimeline = completed,
            runId = UUID.randomUUID().toString(),
            lastEvent = "single_device_stop",
        )
    }

    private fun maybePersistCompletedRun(timeline: SessionRaceTimeline) {
        val started = timeline.hostStartSensorNanos ?: return
        val stopped = timeline.hostStopSensorNanos ?: return
        if (stopped <= started) {
            return
        }
        val run = LastRunResult(
            startedSensorNanos = started,
            stoppedSensorNanos = stopped,
        )
        viewModelScope.launch(ioDispatcher) {
            saveLastRun(run)
        }
    }

    private fun broadcastTimelineSnapshot(timeline: SessionRaceTimeline) {
        val payload = SessionTimelineSnapshotMessage(
            hostStartSensorNanos = timeline.hostStartSensorNanos,
            hostStopSensorNanos = timeline.hostStopSensorNanos,
            hostSplitSensorNanos = timeline.hostSplitSensorNanos,
            sentElapsedNanos = nowElapsedNanos(),
        ).toJsonString()
        broadcastToConnected(payload)
    }

    private fun broadcastSnapshotIfHost() {
        if (_uiState.value.networkRole != SessionNetworkRole.HOST) {
            return
        }
        val targetEndpoints = _uiState.value.connectedEndpoints
        val canonicalDevices = ensureLocalDevice(
            localDeviceFromState(),
            pruneOrphanedNonLocalDevices(
                devices = _uiState.value.devices,
                connectedEndpoints = targetEndpoints,
            ),
        )
        if (canonicalDevices != _uiState.value.devices) {
            _uiState.value = _uiState.value.copy(
                devices = canonicalDevices,
                deviceRole = localDeviceRole(),
            )
        }
        val devicesForSnapshot = _uiState.value.devices
        targetEndpoints.forEach { endpointId ->
            val payload = SessionSnapshotMessage(
                stage = _uiState.value.stage,
                monitoringActive = _uiState.value.monitoringActive,
                devices = devicesForSnapshot,
                hostStartSensorNanos = _uiState.value.timeline.hostStartSensorNanos,
                hostStopSensorNanos = _uiState.value.timeline.hostStopSensorNanos,
                hostSplitSensorNanos = _uiState.value.timeline.hostSplitSensorNanos,
                runId = _uiState.value.runId,
                hostSensorMinusElapsedNanos = _clockState.value.hostSensorMinusElapsedNanos,
                hostGpsUtcOffsetNanos = _clockState.value.hostGpsUtcOffsetNanos,
                hostGpsFixAgeNanos = _clockState.value.hostGpsFixAgeNanos,
                selfDeviceId = endpointId,
            ).toJsonString()
            sendMessage(endpointId, payload) { result ->
                result.exceptionOrNull()?.let { error ->
                    _uiState.value = _uiState.value.copy(
                        lastError = "send failed ($endpointId): ${error.localizedMessage ?: "unknown"}",
                    )
                }
            }
        }
    }

    private fun broadcastToConnected(message: String) {
        _uiState.value.connectedEndpoints.forEach { endpointId ->
            sendMessage(endpointId, message) { result ->
                result.exceptionOrNull()?.let { error ->
                    _uiState.value = _uiState.value.copy(
                        lastError = "send failed ($endpointId): ${error.localizedMessage ?: "unknown"}",
                    )
                }
            }
        }
    }

    private fun sendToHost(message: String) {
        val hostEndpointId = _uiState.value.connectedEndpoints.firstOrNull() ?: return
        sendMessage(hostEndpointId, message) { result ->
            result.exceptionOrNull()?.let { error ->
                _uiState.value = _uiState.value.copy(
                    lastError = "send failed ($hostEndpointId): ${error.localizedMessage ?: "unknown"}",
                )
            }
        }
    }

    private fun sendIdentityHandshake(endpointId: String) {
        val payload = SessionDeviceIdentityMessage(
            stableDeviceId = localDeviceId,
            deviceName = localDeviceName(),
        ).toJsonString()
        sendMessage(endpointId, payload) { result ->
            result.exceptionOrNull()?.let { error ->
                _uiState.value = _uiState.value.copy(
                    lastError = "identity send failed ($endpointId): ${error.localizedMessage ?: "unknown"}",
                )
            }
        }
    }

    private fun handleDeviceIdentity(endpointId: String, identity: SessionDeviceIdentityMessage) {
        val previousEndpointId = endpointIdByStableDeviceId[identity.stableDeviceId]
        mapStableIdentityToEndpoint(identity.stableDeviceId, endpointId)

        val current = _uiState.value
        val preservedDevice = current.devices.firstOrNull { existing ->
            !existing.isLocal && (
                existing.id == endpointId ||
                    (previousEndpointId != null && previousEndpointId != endpointId && existing.id == previousEndpointId)
                )
        }
        val reconciledDevice = (
            preservedDevice ?: SessionDevice(
                id = endpointId,
                name = identity.deviceName,
                role = SessionDeviceRole.UNASSIGNED,
                isLocal = false,
            )
            ).copy(
            id = endpointId,
            name = identity.deviceName,
            isLocal = false,
        )
        val dedupedDevices = current.devices.filterNot { existing ->
            !existing.isLocal && (
                existing.id == endpointId ||
                    (previousEndpointId != null && previousEndpointId != endpointId && existing.id == previousEndpointId)
                )
        } + reconciledDevice
        val nextDevices = ensureLocalDevice(
            localDeviceFromState(),
            pruneOrphanedNonLocalDevices(
                devices = dedupedDevices,
                connectedEndpoints = current.connectedEndpoints,
            ),
        )
        val devicesWithDefaults = if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            applyJoinOrderAutoRoleDefaults(nextDevices)
        } else {
            nextDevices
        }
        _uiState.value = current.copy(
            devices = devicesWithDefaults,
            deviceRole = localDeviceRole(),
            lastEvent = "device_identity",
        )
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        }
    }

    private fun mapStableIdentityToEndpoint(stableDeviceId: String, endpointId: String) {
        val previousForStableDevice = endpointIdByStableDeviceId.put(stableDeviceId, endpointId)
        if (previousForStableDevice != null && previousForStableDevice != endpointId) {
            stableDeviceIdByEndpointId.remove(previousForStableDevice)
        }
        val previousStableForEndpoint = stableDeviceIdByEndpointId.put(endpointId, stableDeviceId)
        if (previousStableForEndpoint != null && previousStableForEndpoint != stableDeviceId) {
            endpointIdByStableDeviceId.remove(previousStableForEndpoint)
        }
    }

    private fun clearIdentityMappingForEndpoint(endpointId: String) {
        val stableDeviceId = stableDeviceIdByEndpointId.remove(endpointId) ?: return
        if (endpointIdByStableDeviceId[stableDeviceId] == endpointId) {
            endpointIdByStableDeviceId.remove(stableDeviceId)
        }
    }

    private fun pruneOrphanedNonLocalDevices(
        devices: List<SessionDevice>,
        connectedEndpoints: Set<String>,
    ): List<SessionDevice> {
        return devices.filter { device ->
            device.isLocal || connectedEndpoints.contains(device.id)
        }
    }

    private fun applyJoinOrderAutoRoleDefaults(devices: List<SessionDevice>): List<SessionDevice> {
        var nextDevices = devices
        val local = nextDevices.firstOrNull { it.isLocal } ?: return devices
        val remotes = nextDevices.filterNot { it.isLocal }
        val participantRemotes = remotes.filterNot { isControllerDevice(it) }
        if (remotes.isEmpty()) {
            return devices
        }

        if (nextDevices.none { it.role == SessionDeviceRole.START }) {
            val preferredStartId = participantRemotes.firstOrNull { it.role == SessionDeviceRole.UNASSIGNED }?.id
                ?: if (local.role == SessionDeviceRole.UNASSIGNED) local.id else null
            if (preferredStartId != null) {
                nextDevices = nextDevices.map { existing ->
                    if (existing.id == preferredStartId && existing.role == SessionDeviceRole.UNASSIGNED) {
                        existing.copy(role = SessionDeviceRole.START)
                    } else {
                        existing
                    }
                }
            }
        }

        if (nextDevices.none { it.role == SessionDeviceRole.STOP }) {
            val preferredStopId = nextDevices
                .filterNot { it.isLocal }
                .filterNot { isControllerDevice(it) }
                .firstOrNull { it.role == SessionDeviceRole.UNASSIGNED }
                ?.id
                ?: if (nextDevices.any { it.id == local.id && it.role == SessionDeviceRole.UNASSIGNED }) local.id else null
            if (preferredStopId != null) {
                nextDevices = nextDevices.map { existing ->
                    if (existing.id == preferredStopId && existing.role == SessionDeviceRole.UNASSIGNED) {
                        existing.copy(role = SessionDeviceRole.STOP)
                    } else {
                        existing
                    }
                }
            }
        }

        nextDevices = nextDevices.map { existing ->
            if (!existing.isLocal && !isControllerDevice(existing) && existing.role == SessionDeviceRole.UNASSIGNED) {
                existing.copy(role = SessionDeviceRole.SPLIT)
            } else {
                existing
            }
        }

        return nextDevices
    }

    private fun roleToTriggerType(role: SessionDeviceRole): String? {
        return when (role) {
            SessionDeviceRole.START -> "start"
            SessionDeviceRole.SPLIT -> "split"
            SessionDeviceRole.STOP -> "stop"
            SessionDeviceRole.UNASSIGNED -> null
            SessionDeviceRole.DISPLAY -> null
        }
    }

    private fun ensureLocalDevice(local: SessionDevice, current: List<SessionDevice>): List<SessionDevice> {
        val withoutLocal = current.filterNot { it.id == local.id || it.isLocal }
        return withoutLocal + local.copy(isLocal = true)
    }

    private fun localDeviceFromState(): SessionDevice {
        return _uiState.value.devices.firstOrNull { it.id == localDeviceId || it.isLocal }
            ?: SessionDevice(
                id = localDeviceId,
                name = DEFAULT_LOCAL_DEVICE_NAME,
                role = SessionDeviceRole.UNASSIGNED,
                isLocal = true,
            )
    }

    private fun localDeviceName(): String {
        return localDeviceFromState().name
    }

    private fun isControllerDevice(device: SessionDevice): Boolean {
        return device.name.contains("controller", ignoreCase = true)
    }
}
