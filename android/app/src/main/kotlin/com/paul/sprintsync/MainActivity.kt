package com.paul.sprintsync

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.paul.sprintsync.core.repositories.LocalRepository
import com.paul.sprintsync.core.services.NearbyEvent
import com.paul.sprintsync.core.services.NearbyTransportStrategy
import com.paul.sprintsync.core.services.SessionConnectionsManager
import com.paul.sprintsync.core.services.TcpConnectionsManager
import com.paul.sprintsync.features.motion_detection.MotionCameraFacing
import com.paul.sprintsync.features.motion_detection.MotionDetectionController
import com.paul.sprintsync.features.race_session.RaceSessionController
import com.paul.sprintsync.features.race_session.SessionCameraFacing
import com.paul.sprintsync.features.race_session.SessionControlAction
import com.paul.sprintsync.features.race_session.SessionControlCommandMessage
import com.paul.sprintsync.features.race_session.SessionControllerIdentityMessage
import com.paul.sprintsync.features.race_session.SessionControllerTarget
import com.paul.sprintsync.features.race_session.SessionControllerTargetsMessage
import com.paul.sprintsync.features.race_session.SessionDeviceRole
import com.paul.sprintsync.features.race_session.SessionLapResultMessage
import com.paul.sprintsync.features.race_session.SessionNetworkRole
import com.paul.sprintsync.features.race_session.SessionOperatingMode
import com.paul.sprintsync.features.race_session.SessionStage
import com.paul.sprintsync.sensor_native.SensorNativeController
import com.paul.sprintsync.sensor_native.SensorNativeEvent
import com.paul.sprintsync.sensor_native.SensorNativePreviewViewFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), ActivityCompat.OnRequestPermissionsResultCallback {
    companion object {
        private const val DEFAULT_SERVICE_ID = "sync.sprint.nearby"
        private const val PERMISSIONS_REQUEST_CODE = 7301
        private const val SENSOR_ELAPSED_PROJECTION_MAX_AGE_NANOS = 3_000_000_000L
        private const val TIMER_REFRESH_INTERVAL_MS = 100L
        private const val TAG = "SprintSyncRuntime"
        private const val MAX_PENDING_LAPS = 100
        private const val GPS_LOCK_VALIDITY_NANOS = 10_000_000_000L
        private const val GPS_REACQUIRE_REQUEST_THROTTLE_NANOS = 5_000_000_000L
    }

    private lateinit var sensorNativeController: SensorNativeController
    private lateinit var connectionsManager: SessionConnectionsManager
    private lateinit var motionDetectionController: MotionDetectionController
    private lateinit var raceSessionController: RaceSessionController
    private lateinit var previewViewFactory: SensorNativePreviewViewFactory
    private val uiState = mutableStateOf(SprintSyncUiState())
    private var pendingPermissionAction: (() -> Unit)? = null
    private var timerRefreshJob: Job? = null
    private var isAppResumed: Boolean = false
    private var localCaptureStartPending: Boolean = false
    private var userMonitoringEnabled: Boolean = true
    private var displayDiscoveryActive: Boolean = false
    private var displayConnectedHostEndpointId: String? = null
    private var displayConnectedHostName: String? = null
    private val displayDiscoveredHosts = linkedMapOf<String, String>()
    private val controllerTargetDeviceNamesByEndpointId = linkedMapOf<String, String>()
    private val displayControllerEndpointIds = linkedSetOf<String>()
    private val displayHostDeviceNamesByEndpointId = linkedMapOf<String, String>()
    private val displayLatestLapByEndpointId = linkedMapOf<String, Long>()
    private val displayLimitMillisByEndpointId = linkedMapOf<String, Long>()
    private var lastRelayedStopSensorNanos: Long? = null
    private var displayReconnectionPending: Boolean = false
    private var lastGpsReacquireRequestElapsedNanos: Long? = null
    private val pendingLapResults = ArrayDeque<SessionLapResultMessage>()
    private var pendingPermissionScope: PermissionScope = PermissionScope.NETWORK_ONLY
    private var autoDisplayReconnectJob: Job? = null
    private var autoDisplayReconnectAttempts: Int = 0
    private val effectiveAutoStartRole: String = resolveEffectiveAutoStartRole(
        configuredRole = BuildConfig.AUTO_START_ROLE,
        flavorName = BuildConfig.FLAVOR,
    )
    private val setupActionProfile: SetupActionProfile = if (isOneplusControllerFlavor(BuildConfig.FLAVOR)) {
        SetupActionProfile.CONTROLLER_ONLY
    } else {
        resolveSetupActionProfile(effectiveAutoStartRole)
    }

    private enum class PermissionScope {
        NETWORK_ONLY,
        CAMERA_AND_NETWORK,
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensorNativeController = SensorNativeController(this)
        val localRepository = LocalRepository(this)
        val nativeClockSyncElapsedNanos: (Boolean) -> Long? = { requireSensorDomain ->
            sensorNativeController.currentClockSyncElapsedNanos(
                maxSensorSampleAgeNanos = SENSOR_ELAPSED_PROJECTION_MAX_AGE_NANOS,
                requireSensorDomain = requireSensorDomain,
            )
        }
        connectionsManager = TcpConnectionsManager(
            hostIpProvider = { resolveTcpHostAddress(activeWifiGatewayIp(), BuildConfig.FALLBACK_HOST_IP) },
            hostPort = BuildConfig.TCP_HOST_PORT,
            nowNativeClockSyncElapsedNanos = nativeClockSyncElapsedNanos,
        )
        motionDetectionController = MotionDetectionController(
            localRepository = localRepository,
            sensorNativeController = sensorNativeController,
        )
        previewViewFactory = SensorNativePreviewViewFactory(sensorNativeController)
        raceSessionController = RaceSessionController(
            loadLastRun = { localRepository.loadLastRun() },
            saveLastRun = { run -> localRepository.saveLastRun(run) },
            sendMessage = { endpointId, payload, onComplete ->
                connectionsManager.sendMessage(endpointId, payload, onComplete)
            },
            sendClockSyncPayload = { endpointId, payloadBytes, onComplete ->
                connectionsManager.sendClockSyncPayload(endpointId, payloadBytes, onComplete)
            },
        )
        raceSessionController.setLocalDeviceIdentity(localDeviceId(), localEndpointName())
        sensorNativeController.setEventListener(::onSensorEvent)
        connectionsManager.setEventListener(::onNearbyEvent)

        val denied = deniedPermissions(PermissionScope.NETWORK_ONLY)
        updateUiState {
            copy(
                permissionGranted = denied.isEmpty(),
                deniedPermissions = denied,
                networkSummary = "Ready",
            )
        }

        setContent {
            com.paul.sprintsync.ui.theme.SprintSyncTheme {
                SprintSyncApp(
                    uiState = uiState.value,
                    previewViewFactory = previewViewFactory,
                    setupActionProfile = setupActionProfile,
                    onRequestPermissions = {
                        if (uiState.value.setupBusy) return@SprintSyncApp
                        setSetupBusy(true)
                        requestPermissionsIfNeeded(PermissionScope.NETWORK_ONLY) {
                            setSetupBusy(false)
                        }
                    },
                    onStartSingleDevice = {
                        if (setupActionProfile == SetupActionProfile.CONTROLLER_ONLY) {
                            startControllerMode(enableAutoDisplayReconnect = true)
                        } else {
                            startSingleDeviceMode(enableAutoDisplayReconnect = true)
                        }
                    },
                    onStartDisplayHost = { startDisplayHostMode() },
                    onStartMonitoring = {
                        requestPermissionsIfNeeded(PermissionScope.CAMERA_AND_NETWORK) {
                            val started = raceSessionController.startMonitoring()
                            if (started) {
                                userMonitoringEnabled = true
                            }

                            logRuntimeDiagnostic(
                                "startMonitoring requested: started=$started role=${raceSessionController.localDeviceRole().name} " +
                                    "shouldRunLocal=${shouldRunLocalMonitoring()} resumed=$isAppResumed",
                            )
                            syncControllerSummaries()
                        }
                    },
                    onStartDisplayDiscovery = { startDisplayDiscovery(errorPrefix = "display discovery") },
                    onConnectDisplayHost = { endpointId ->
                        try {
                            connectionsManager.requestConnection(
                                endpointId = endpointId,
                                endpointName = localEndpointName(),
                            ) { result ->
                                result.exceptionOrNull()?.let { error ->
                                    appendEvent("display connect error: ${error.localizedMessage ?: "unknown"}")
                                }
                                syncControllerSummaries()
                            }
                        } catch (error: Throwable) {
                            appendEvent("display connect error: ${error.localizedMessage ?: "unknown"}")
                            syncControllerSummaries()
                        }
                    },
                    onResetDeviceTimer = { targetEndpointId ->
                        sendControllerCommandToDisplayHost(
                            action = SessionControlAction.RESET_TIMER,
                            targetEndpointId = targetEndpointId,
                            limitMillis = null,
                        )
                    },
                    onSetDisplayLimit = { targetEndpointId, limitMillis ->
                        sendControllerCommandToDisplayHost(
                            action = SessionControlAction.SET_DISPLAY_LIMIT,
                            targetEndpointId = targetEndpointId,
                            limitMillis = limitMillis,
                        )
                    },
                    onSetMonitoringEnabled = { enabled ->
                        userMonitoringEnabled = enabled
                        if (!enabled) {
                            localCaptureStartPending = false
                            motionDetectionController.stopMonitoring()
                        }
                        syncControllerSummaries()
                    },
                    onStopMonitoring = {
                        logRuntimeDiagnostic("stopMonitoring requested")
                        when (uiState.value.operatingMode) {
                            SessionOperatingMode.SINGLE_DEVICE -> {
                                raceSessionController.stopSingleDeviceMonitoring()
                                stopAutoDisplayReconnectLoop()
                                connectionsManager.stopAll()
                                connectionsManager.configureNativeClockSyncHost(
                                    enabled = false,
                                    requireSensorDomainClock = false,
                                )
                                clearDisplayRelayReconnectionState()
                                displayDiscoveryActive = false
                                displayConnectedHostEndpointId = null
                                displayConnectedHostName = null
                                displayDiscoveredHosts.clear()
                                controllerTargetDeviceNamesByEndpointId.clear()
                            }
                            SessionOperatingMode.DISPLAY_HOST -> {
                                raceSessionController.stopDisplayHostMode()
                                stopAutoDisplayReconnectLoop()
                                connectionsManager.stopAll()
                                connectionsManager.configureNativeClockSyncHost(
                                    enabled = false,
                                    requireSensorDomainClock = false,
                                )
                                clearDisplayRelayReconnectionState()
                                clearDisplayHostLapState()
                                controllerTargetDeviceNamesByEndpointId.clear()
                            }
                        }
                        syncControllerSummaries()
                    },
                    onResetRun = {
                        raceSessionController.resetRun()
                        syncControllerSummaries()
                    },
                    onAssignRole = { deviceId, role ->
                        raceSessionController.assignRole(deviceId, role)
                        syncControllerSummaries()
                    },
                    onAssignCameraFacing = { deviceId, facing ->
                        raceSessionController.assignCameraFacing(deviceId, facing)
                        if (
                            shouldApplyLiveLocalCameraFacingUpdate(
                                isLocalMotionMonitoring = motionDetectionController.uiState.value.monitoring,
                                assignedDeviceId = deviceId,
                                localDeviceId = localDeviceId(),
                            )
                        ) {
                            applyLocalMonitoringConfigFromSession()
                        }
                        syncControllerSummaries()
                    },
                    onUpdateThreshold = { value ->
                        motionDetectionController.updateThreshold(value)
                        syncControllerSummaries()
                    },
                    onUpdateRoiCenter = { value ->
                        motionDetectionController.updateRoiCenter(value)
                        syncControllerSummaries()
                    },
                    onUpdateRoiWidth = { value ->
                        motionDetectionController.updateRoiWidth(value)
                        syncControllerSummaries()
                    },
                    onUpdateCooldown = { value ->
                        motionDetectionController.updateCooldown(value)
                        syncControllerSummaries()
                    },
                    onStopHosting = {
                        if (uiState.value.operatingMode == SessionOperatingMode.DISPLAY_HOST) {
                            raceSessionController.stopDisplayHostMode()
                            clearDisplayHostLapState()
                        } else {
                            raceSessionController.stopHostingAndReturnToSetup()
                        }
                        stopAutoDisplayReconnectLoop()
                        connectionsManager.stopAll()
                        if (motionDetectionController.uiState.value.monitoring) {
                            motionDetectionController.stopMonitoring()
                        }
                        displayDiscoveryActive = false
                        displayConnectedHostEndpointId = null
                        displayConnectedHostName = null
                        displayDiscoveredHosts.clear()
                        controllerTargetDeviceNamesByEndpointId.clear()
                        updateUiState { copy(networkSummary = "Stopped") }
                        appendEvent("hosting stopped")
                        syncControllerSummaries()
                    },
                )
            }
        }
        maybeAutoStartFlavorMode()
    }

    override fun onPause() {
        isAppResumed = false
        stopTimerRefreshLoop()
        logRuntimeDiagnostic("host paused")
        sensorNativeController.onHostPaused()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        isAppResumed = true
        logRuntimeDiagnostic("host resumed")
        sensorNativeController.onHostResumed()
        syncControllerSummaries()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            return
        }
        applySystemUiForMode(raceSessionController.uiState.value.operatingMode)
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopTimerRefreshLoop()
        stopAutoDisplayReconnectLoop()
        connectionsManager.stopAll()
        connectionsManager.setEventListener(null)
        sensorNativeController.setEventListener(null)
        sensorNativeController.dispose()
        super.onDestroy()
    }

    private fun requestPermissionsIfNeeded(scope: PermissionScope, onGranted: () -> Unit) {
        val denied = deniedPermissions(scope)
        if (denied.isEmpty()) {
            updateUiState { copy(permissionGranted = true, deniedPermissions = emptyList()) }
            onGranted()
            return
        }
        pendingPermissionScope = scope
        pendingPermissionAction = onGranted
        ActivityCompat.requestPermissions(this, denied.toTypedArray(), PERMISSIONS_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSIONS_REQUEST_CODE) {
            return
        }
        val denied = deniedPermissions(pendingPermissionScope)
        val granted = denied.isEmpty()
        updateUiState {
            copy(
                permissionGranted = granted,
                deniedPermissions = denied,
            )
        }
        if (granted) {
            pendingPermissionAction?.invoke()
        } else {
            setSetupBusy(false)
            appendEvent("permissions denied: ${denied.joinToString()}")
        }
        pendingPermissionAction = null
        pendingPermissionScope = PermissionScope.NETWORK_ONLY
    }

    private fun setSetupBusy(busy: Boolean) {
        updateUiState { copy(setupBusy = busy) }
    }

    private fun maybeAutoStartFlavorMode() {
        when (resolveAutoStartRole(effectiveAutoStartRole)) {
            AutoStartRole.NONE -> Unit
            AutoStartRole.DISPLAY -> {
                lifecycleScope.launch {
                    delay(250)
                    startDisplayHostMode()
                }
            }
            AutoStartRole.CONTROLLER -> {
                lifecycleScope.launch {
                    delay(250)
                    startControllerMode(enableAutoDisplayReconnect = true)
                }
            }
            AutoStartRole.SINGLE -> {
                lifecycleScope.launch {
                    delay(250)
                    startSingleDeviceMode(enableAutoDisplayReconnect = true)
                }
            }
        }
    }

    private fun startSingleDeviceMode(enableAutoDisplayReconnect: Boolean) {
        if (uiState.value.setupBusy) return
        setSetupBusy(true)
        requestPermissionsIfNeeded(PermissionScope.CAMERA_AND_NETWORK) {
            clearDisplayRelayReconnectionState()
            connectionsManager.stopAll()
            connectionsManager.configureNativeClockSyncHost(
                enabled = false,
                requireSensorDomainClock = false,
            )
            displayDiscoveryActive = false
            displayConnectedHostEndpointId = null
            displayConnectedHostName = null
            displayDiscoveredHosts.clear()
            controllerTargetDeviceNamesByEndpointId.clear()
            lastRelayedStopSensorNanos = null
            raceSessionController.startSingleDeviceMonitoring()
            userMonitoringEnabled = true
            if (enableAutoDisplayReconnect) {
                displayReconnectionPending = true
                startAutoDisplayReconnectLoop()
            } else {
                stopAutoDisplayReconnectLoop()
            }
            setSetupBusy(false)
            syncControllerSummaries()
        }
    }

    private fun startControllerMode(enableAutoDisplayReconnect: Boolean) {
        if (uiState.value.setupBusy) return
        setSetupBusy(true)
        requestPermissionsIfNeeded(PermissionScope.NETWORK_ONLY) {
            clearDisplayRelayReconnectionState()
            connectionsManager.stopAll()
            connectionsManager.configureNativeClockSyncHost(
                enabled = false,
                requireSensorDomainClock = false,
            )
            displayDiscoveryActive = false
            displayConnectedHostEndpointId = null
            displayConnectedHostName = null
            displayDiscoveredHosts.clear()
            controllerTargetDeviceNamesByEndpointId.clear()
            raceSessionController.setNetworkRole(SessionNetworkRole.CLIENT)
            raceSessionController.setSessionStage(controllerInitialStage())
            userMonitoringEnabled = false
            if (enableAutoDisplayReconnect) {
                displayReconnectionPending = true
                startAutoDisplayReconnectLoop()
            } else {
                stopAutoDisplayReconnectLoop()
            }
            setSetupBusy(false)
            syncControllerSummaries()
        }
    }

    private fun startDisplayHostMode() {
        if (uiState.value.setupBusy) return
        setSetupBusy(true)
        requestPermissionsIfNeeded(PermissionScope.NETWORK_ONLY) {
            stopAutoDisplayReconnectLoop()
            clearDisplayRelayReconnectionState()
            raceSessionController.startDisplayHostMode()
            clearDisplayHostLapState()
            displayDiscoveryActive = false
            displayConnectedHostEndpointId = null
            displayConnectedHostName = null
            displayDiscoveredHosts.clear()
            controllerTargetDeviceNamesByEndpointId.clear()
            connectionsManager.configureNativeClockSyncHost(
                enabled = false,
                requireSensorDomainClock = false,
            )
            try {
                connectionsManager.startHosting(
                    serviceId = DEFAULT_SERVICE_ID,
                    endpointName = localEndpointName(),
                    strategy = NearbyTransportStrategy.POINT_TO_STAR,
                ) { result ->
                    result.exceptionOrNull()?.let { error ->
                        appendEvent("display host error: ${error.localizedMessage ?: "unknown"}")
                    }
                    setSetupBusy(false)
                    syncControllerSummaries()
                }
            } catch (error: Throwable) {
                appendEvent("display host error: ${error.localizedMessage ?: "unknown"}")
                setSetupBusy(false)
                syncControllerSummaries()
            }
        }
    }

    private fun startDisplayDiscovery(errorPrefix: String) {
        requestPermissionsIfNeeded(PermissionScope.NETWORK_ONLY) {
            displayDiscoveryActive = true
            displayDiscoveredHosts.clear()
            try {
                connectionsManager.startDiscovery(
                    serviceId = DEFAULT_SERVICE_ID,
                    strategy = NearbyTransportStrategy.POINT_TO_STAR,
                ) { result ->
                    result.exceptionOrNull()?.let { error ->
                        appendEvent("$errorPrefix error: ${error.localizedMessage ?: "unknown"}")
                        displayDiscoveryActive = false
                    }
                    syncControllerSummaries()
                }
            } catch (error: Throwable) {
                displayDiscoveryActive = false
                appendEvent("$errorPrefix error: ${error.localizedMessage ?: "unknown"}")
                syncControllerSummaries()
            }
        }
    }

    private fun startAutoDisplayReconnectLoop() {
        if (autoDisplayReconnectJob?.isActive == true) return
        autoDisplayReconnectJob = lifecycleScope.launch {
            while (isActive) {
                if (raceSessionController.uiState.value.operatingMode != SessionOperatingMode.SINGLE_DEVICE) {
                    break
                }
                if (displayConnectedHostEndpointId == null) {
                    displayReconnectionPending = true
                    startDisplayDiscovery(errorPrefix = "reconnect discovery")
                    val nextDelay = reconnectDelayMillis(autoDisplayReconnectAttempts)
                    autoDisplayReconnectAttempts += 1
                    delay(nextDelay)
                } else {
                    autoDisplayReconnectAttempts = 0
                    delay(1000)
                }
            }
        }
    }

    private fun stopAutoDisplayReconnectLoop() {
        autoDisplayReconnectJob?.cancel()
        autoDisplayReconnectJob = null
        autoDisplayReconnectAttempts = 0
    }

    private fun onNearbyEvent(event: NearbyEvent) {
        when (uiState.value.operatingMode) {
            SessionOperatingMode.SINGLE_DEVICE -> {
                when (event) {
                    is NearbyEvent.EndpointFound -> {
                        displayDiscoveredHosts[event.endpointId] = event.endpointName
                        if (setupActionProfile == SetupActionProfile.CONTROLLER_ONLY) {
                            raceSessionController.onNearbyEvent(event)
                        }
                        // Auto-connect if not connected or if reconnection is pending
                        if (displayConnectedHostEndpointId == null || displayReconnectionPending) {
                            try {
                                connectionsManager.requestConnection(
                                    endpointId = event.endpointId,
                                    endpointName = localEndpointName(),
                                ) { result ->
                                    result.exceptionOrNull()?.let { error ->
                                        appendEvent(
                                            "auto-display-connect error: ${error.localizedMessage ?: "unknown"}",
                                        )
                                    }
                                }
                            } catch (error: Throwable) {
                                appendEvent("auto-display-connect error: ${error.localizedMessage ?: "unknown"}")
                            }
                        }
                    }
                    is NearbyEvent.EndpointLost -> {
                        displayDiscoveredHosts.remove(event.endpointId)
                        if (setupActionProfile == SetupActionProfile.CONTROLLER_ONLY) {
                            raceSessionController.onNearbyEvent(event)
                        }
                    }
                    is NearbyEvent.ConnectionResult -> {
                        if (event.connected) {
                            displayConnectedHostEndpointId = event.endpointId
                            displayConnectedHostName = event.endpointName ?: displayDiscoveredHosts[event.endpointId]
                            displayDiscoveryActive = false
                            autoDisplayReconnectAttempts = 0
                            // Clear reconnection flag and flush any pending laps
                            if (displayReconnectionPending) {
                                displayReconnectionPending = false
                                flushPendingLapResults()
                            }
                            if (setupActionProfile == SetupActionProfile.CONTROLLER_ONLY) {
                                val payload = SessionControllerIdentityMessage(
                                    senderDeviceName = localEndpointName(),
                                ).toJsonString()
                                connectionsManager.sendMessage(event.endpointId, payload) { result ->
                                    result.exceptionOrNull()?.let { error ->
                                        appendEvent("controller identity error: ${error.localizedMessage ?: "unknown"}")
                                    }
                                }
                            }
                            startAutoDisplayReconnectLoop()
                        } else if (displayConnectedHostEndpointId == event.endpointId) {
                            displayConnectedHostEndpointId = null
                            displayConnectedHostName = null
                            controllerTargetDeviceNamesByEndpointId.clear()
                            displayReconnectionPending = true
                            startAutoDisplayReconnectLoop()
                        }
                        if (setupActionProfile == SetupActionProfile.CONTROLLER_ONLY) {
                            raceSessionController.onNearbyEvent(event)
                        }
                    }
                    is NearbyEvent.EndpointDisconnected -> {
                        if (displayConnectedHostEndpointId == event.endpointId) {
                            displayConnectedHostEndpointId = null
                            displayConnectedHostName = null
                            controllerTargetDeviceNamesByEndpointId.clear()
                            displayReconnectionPending = true
                            startAutoDisplayReconnectLoop()
                        }
                        if (setupActionProfile == SetupActionProfile.CONTROLLER_ONLY) {
                            raceSessionController.onNearbyEvent(event)
                        }
                    }
                    is NearbyEvent.PayloadReceived -> {
                        SessionControlCommandMessage.tryParse(event.message)?.let { command ->
                            if (command.action == SessionControlAction.RESET_TIMER) {
                                raceSessionController.resetRun()
                                appendEvent("remote reset from ${command.senderDeviceName}")
                            }
                        }
                        SessionControllerTargetsMessage.tryParse(event.message)?.let { snapshot ->
                            val hostEndpoint = displayConnectedHostEndpointId
                            if (hostEndpoint != null && event.endpointId == hostEndpoint) {
                                controllerTargetDeviceNamesByEndpointId.clear()
                                snapshot.targets.forEach { target ->
                                    controllerTargetDeviceNamesByEndpointId[target.endpointId] = target.deviceName
                                }
                            }
                        }
                        if (setupActionProfile == SetupActionProfile.CONTROLLER_ONLY) {
                            raceSessionController.onNearbyEvent(event)
                        }
                    }
                    is NearbyEvent.ClockSyncSampleReceived, is NearbyEvent.Error -> Unit
                }
            }
            SessionOperatingMode.DISPLAY_HOST -> {
                when (event) {
                    is NearbyEvent.EndpointFound -> {
                        if (event.endpointName.isNotBlank()) {
                            displayHostDeviceNamesByEndpointId[event.endpointId] = event.endpointName
                        }
                    }
                    is NearbyEvent.EndpointLost -> {
                        displayHostDeviceNamesByEndpointId.remove(event.endpointId)
                        displayControllerEndpointIds.remove(event.endpointId)
                        displayLatestLapByEndpointId.remove(event.endpointId)
                        displayLimitMillisByEndpointId.remove(event.endpointId)
                        broadcastControllerTargetsSnapshotToConnectedEndpoints()
                    }
                    is NearbyEvent.ConnectionResult -> {
                        if (event.connected) {
                            val endpointName = event.endpointName?.trim().orEmpty()
                            if (endpointName.isNotEmpty()) {
                                displayHostDeviceNamesByEndpointId[event.endpointId] = endpointName
                            }
                        } else {
                            displayHostDeviceNamesByEndpointId.remove(event.endpointId)
                            displayControllerEndpointIds.remove(event.endpointId)
                            displayLatestLapByEndpointId.remove(event.endpointId)
                            displayLimitMillisByEndpointId.remove(event.endpointId)
                        }
                        broadcastControllerTargetsSnapshotToConnectedEndpoints()
                    }
                    is NearbyEvent.EndpointDisconnected -> {
                        displayHostDeviceNamesByEndpointId.remove(event.endpointId)
                        displayControllerEndpointIds.remove(event.endpointId)
                        displayLatestLapByEndpointId.remove(event.endpointId)
                        displayLimitMillisByEndpointId.remove(event.endpointId)
                        broadcastControllerTargetsSnapshotToConnectedEndpoints()
                    }
                    is NearbyEvent.PayloadReceived -> {
                        var handledControllerIdentity = false
                        var handledControl = false
                        SessionControllerIdentityMessage.tryParse(event.message)?.let { identity ->
                            handledControllerIdentity = true
                            displayControllerEndpointIds.add(event.endpointId)
                            if (identity.senderDeviceName.isNotBlank()) {
                                displayHostDeviceNamesByEndpointId[event.endpointId] = identity.senderDeviceName
                            }
                            broadcastControllerTargetsSnapshotToConnectedEndpoints()
                        }
                        if (!handledControllerIdentity) {
                            SessionControlCommandMessage.tryParse(event.message)?.let { command ->
                            handledControl = true
                            when (command.action) {
                                SessionControlAction.RESET_TIMER -> {
                                    displayLatestLapByEndpointId.remove(command.targetEndpointId)
                                    if (connectionsManager.connectedEndpoints().contains(command.targetEndpointId)) {
                                        connectionsManager.sendMessage(command.targetEndpointId, command.toJsonString()) { result ->
                                            result.exceptionOrNull()?.let { error ->
                                                appendEvent("reset route error: ${error.localizedMessage ?: "unknown"}")
                                            }
                                        }
                                    } else {
                                        appendEvent("reset route skipped: target not connected")
                                    }
                                }
                                SessionControlAction.SET_DISPLAY_LIMIT -> {
                                    val limitMillis = command.limitMillis
                                    if (limitMillis != null && limitMillis > 0L) {
                                        displayLimitMillisByEndpointId[command.targetEndpointId] = limitMillis
                                    } else {
                                        displayLimitMillisByEndpointId.remove(command.targetEndpointId)
                                    }
                                }
                            }
                        }
                        }
                        if (!handledControllerIdentity && !handledControl) {
                            SessionLapResultMessage.tryParse(event.message)?.let { result ->
                                val elapsedNanos = result.stoppedSensorNanos - result.startedSensorNanos
                                val senderDeviceName = result.senderDeviceName.trim()
                                if (senderDeviceName.isNotEmpty()) {
                                    displayHostDeviceNamesByEndpointId[event.endpointId] = senderDeviceName
                                }
                                displayLatestLapByEndpointId[event.endpointId] = elapsedNanos
                            }
                        }
                        broadcastControllerTargetsSnapshotToConnectedEndpoints()
                    }
                    is NearbyEvent.ClockSyncSampleReceived,
                    is NearbyEvent.Error,
                    -> Unit
                }
            }
        }

        val type = when (event) {
            is NearbyEvent.EndpointFound -> "endpoint_found"
            is NearbyEvent.EndpointLost -> "endpoint_lost"
            is NearbyEvent.ConnectionResult -> "connection_result"
            is NearbyEvent.EndpointDisconnected -> "endpoint_disconnected"
            is NearbyEvent.PayloadReceived -> "payload_received"
            is NearbyEvent.ClockSyncSampleReceived -> "clock_sync_sample_received"
            is NearbyEvent.Error -> "error"
        }
        val connectedCount = connectionsManager.connectedEndpoints().size
        val role = connectionsManager.currentRole().name.lowercase()
        updateUiState {
            copy(
                networkSummary = "$role mode, $connectedCount connected",
                lastNearbyEvent = type,
            )
        }
        syncControllerSummaries()
        appendEvent("transport:$type")
    }

    private fun onSensorEvent(event: SensorNativeEvent) {
        if (event is SensorNativeEvent.State || event is SensorNativeEvent.Error) {
            localCaptureStartPending = false
        }
        motionDetectionController.handleSensorEvent(event)
        val localOffsetNanos = when (event) {
            is SensorNativeEvent.FrameStats -> event.hostSensorMinusElapsedNanos
            is SensorNativeEvent.State -> event.hostSensorMinusElapsedNanos
            is SensorNativeEvent.Trigger -> null
            is SensorNativeEvent.Diagnostic -> null
            is SensorNativeEvent.Error -> null
        }
        val localGpsUtcOffsetNanos = when (event) {
            is SensorNativeEvent.FrameStats -> event.gpsUtcOffsetNanos
            is SensorNativeEvent.State -> event.gpsUtcOffsetNanos
            is SensorNativeEvent.Trigger -> null
            is SensorNativeEvent.Diagnostic -> null
            is SensorNativeEvent.Error -> null
        }
        val localGpsFixAgeNanos = when (event) {
            is SensorNativeEvent.FrameStats ->
                raceSessionController.computeGpsFixAgeNanos(event.gpsFixElapsedRealtimeNanos)
            is SensorNativeEvent.State ->
                raceSessionController.computeGpsFixAgeNanos(event.gpsFixElapsedRealtimeNanos)
            is SensorNativeEvent.Trigger -> null
            is SensorNativeEvent.Diagnostic -> null
            is SensorNativeEvent.Error -> null
        }
        val localGpsFixElapsedRealtimeNanos = when (event) {
            is SensorNativeEvent.FrameStats -> event.gpsFixElapsedRealtimeNanos
            is SensorNativeEvent.State -> event.gpsFixElapsedRealtimeNanos
            is SensorNativeEvent.Trigger -> null
            is SensorNativeEvent.Diagnostic -> null
            is SensorNativeEvent.Error -> null
        }
        val raceStage = raceSessionController.uiState.value.stage
        val isGpsRelevantStage = raceStage == SessionStage.LOBBY || raceStage == SessionStage.MONITORING
        val shouldForceGpsReacquire = isGpsRelevantStage &&
            localGpsFixElapsedRealtimeNanos != null &&
            (localGpsFixAgeNanos == null || localGpsFixAgeNanos > GPS_LOCK_VALIDITY_NANOS)
        if (shouldForceGpsReacquire) {
            val nowElapsedNanos = SystemClock.elapsedRealtimeNanos()
            val lastRequestElapsedNanos = lastGpsReacquireRequestElapsedNanos
            if (lastRequestElapsedNanos == null ||
                nowElapsedNanos - lastRequestElapsedNanos >= GPS_REACQUIRE_REQUEST_THROTTLE_NANOS
            ) {
                sensorNativeController.warmupGpsSync(forceRestart = true)
                lastGpsReacquireRequestElapsedNanos = nowElapsedNanos
            }
        }
        if (localOffsetNanos != null) {
            val isHost = raceSessionController.uiState.value.networkRole == SessionNetworkRole.HOST
            raceSessionController.updateClockState(
                localSensorMinusElapsedNanos = localOffsetNanos,
                hostSensorMinusElapsedNanos = if (isHost) localOffsetNanos else raceSessionController.clockState.value.hostSensorMinusElapsedNanos,
                localGpsUtcOffsetNanos = localGpsUtcOffsetNanos
                    ?: raceSessionController.clockState.value.localGpsUtcOffsetNanos,
                localGpsFixAgeNanos = localGpsFixAgeNanos
                    ?: raceSessionController.clockState.value.localGpsFixAgeNanos,
                hostGpsUtcOffsetNanos = if (isHost) {
                    localGpsUtcOffsetNanos ?: raceSessionController.clockState.value.hostGpsUtcOffsetNanos
                } else {
                    raceSessionController.clockState.value.hostGpsUtcOffsetNanos
                },
                hostGpsFixAgeNanos = if (isHost) {
                    localGpsFixAgeNanos ?: raceSessionController.clockState.value.hostGpsFixAgeNanos
                } else {
                    raceSessionController.clockState.value.hostGpsFixAgeNanos
                },
            )
        } else if (localGpsUtcOffsetNanos != null || localGpsFixAgeNanos != null) {
            raceSessionController.updateClockState(
                localGpsUtcOffsetNanos = localGpsUtcOffsetNanos
                    ?: raceSessionController.clockState.value.localGpsUtcOffsetNanos,
                localGpsFixAgeNanos = localGpsFixAgeNanos
                    ?: raceSessionController.clockState.value.localGpsFixAgeNanos,
            )
        }
        if (event is SensorNativeEvent.Trigger) {
            raceSessionController.onLocalMotionTrigger(
                triggerType = event.trigger.triggerType,
                splitIndex = 0,
                triggerSensorNanos = event.trigger.triggerSensorNanos,
            )
        }
        val type = when (event) {
            is SensorNativeEvent.FrameStats -> "native_frame_stats"
            is SensorNativeEvent.Trigger -> "native_trigger"
            is SensorNativeEvent.State -> "native_state"
            is SensorNativeEvent.Diagnostic -> "native_diagnostic"
            is SensorNativeEvent.Error -> "native_error"
        }
        updateUiState { copy(lastSensorEvent = type) }
        syncControllerSummaries()
        appendEvent("sensor:$type")
    }

    private fun firstConnectedEndpointId(): String? {
        return connectionsManager.connectedEndpoints().firstOrNull()
    }

    private fun syncControllerSummaries() {
        val raceState = raceSessionController.uiState.value
        val clockState = raceSessionController.clockState.value
        val motionBefore = motionDetectionController.uiState.value
        val mode = raceState.operatingMode
        val localRole = raceSessionController.localDeviceRole()
        val isPassiveDisplayClient = shouldUsePassiveDisplayClientMode(
            mode = mode,
            networkRole = raceState.networkRole,
            localRole = localRole,
        )
        applyRequestedOrientationForMode(mode)
        val shouldRunLocalCapture = shouldRunLocalMonitoring()

        if (raceState.stage == SessionStage.LOBBY || raceState.stage == SessionStage.MONITORING) {
            sensorNativeController.warmupGpsSync()
        }

        when (
            resolveLocalCaptureAction(
                monitoringActive = raceState.monitoringActive &&
                    mode != SessionOperatingMode.DISPLAY_HOST &&
                    !isPassiveDisplayClient,
                isAppResumed = isAppResumed,
                shouldRunLocalCapture = shouldRunLocalCapture,
                isLocalMotionMonitoring = motionBefore.monitoring,
                localCaptureStartPending = localCaptureStartPending,
            )
        ) {
            LocalCaptureAction.START -> {
                localCaptureStartPending = true
                logRuntimeDiagnostic(
                    "local capture start: role=${raceSessionController.localDeviceRole().name} stage=${raceState.stage.name}",
                )
                applyLocalMonitoringConfigFromSession()
                motionDetectionController.startMonitoring()
            }

            LocalCaptureAction.STOP -> {
                localCaptureStartPending = false
                logRuntimeDiagnostic(
                    "local capture stop: role=${raceSessionController.localDeviceRole().name} stage=${raceState.stage.name}",
                )
                motionDetectionController.stopMonitoring()
            }

            LocalCaptureAction.NONE -> Unit
        }

        if (
            shouldKeepTimerRefreshActive(
                monitoringActive = raceState.monitoringActive &&
                    mode != SessionOperatingMode.DISPLAY_HOST &&
                    !isPassiveDisplayClient,
                isAppResumed = isAppResumed,
                hasStopSensor = raceState.timeline.hostStopSensorNanos != null,
            )
        ) {
            startTimerRefreshLoop()
        } else {
            stopTimerRefreshLoop()
        }

        val motionState = motionDetectionController.uiState.value

        val monitoringSummary = if (motionState.monitoring) {
            "Monitoring"
        } else {
            "Idle"
        }
        val isHost = raceState.networkRole == SessionNetworkRole.HOST || mode == SessionOperatingMode.DISPLAY_HOST
        val isClient = raceState.networkRole == SessionNetworkRole.CLIENT
        val liveConnectedEndpoints = when (mode) {
            SessionOperatingMode.SINGLE_DEVICE -> setOfNotNull(displayConnectedHostEndpointId)
            SessionOperatingMode.DISPLAY_HOST -> connectionsManager.connectedEndpoints()
        }
        val hasPeers = liveConnectedEndpoints.isNotEmpty()
        val timelineForUi = if (
            mode == SessionOperatingMode.SINGLE_DEVICE &&
            raceState.timeline.hostStartSensorNanos == null &&
            raceState.latestCompletedTimeline != null
        ) {
            raceState.latestCompletedTimeline
        } else {
            raceState.timeline
        }
        if (mode == SessionOperatingMode.SINGLE_DEVICE) {
            val completed = raceState.latestCompletedTimeline
            val stopNanos = completed?.hostStopSensorNanos
            val startNanos = completed?.hostStartSensorNanos
            val hostEndpoint = displayConnectedHostEndpointId
            if (
                startNanos != null &&
                stopNanos != null &&
                stopNanos != lastRelayedStopSensorNanos
            ) {
                val lapMessage = SessionLapResultMessage(
                    senderDeviceName = localEndpointName(),
                    startedSensorNanos = startNanos,
                    stoppedSensorNanos = stopNanos,
                )

                if (hostEndpoint != null) {
                    // Connected - send immediately
                    connectionsManager.sendMessage(hostEndpoint, lapMessage.toJsonString()) { result ->
                        result.exceptionOrNull()?.let { error ->
                            appendEvent("lap relay error: ${error.localizedMessage ?: "unknown"}")
                        }
                    }
                    lastRelayedStopSensorNanos = stopNanos
                } else if (displayReconnectionPending) {
                    // Disconnected but trying to reconnect - cache for later
                    if (pendingLapResults.size >= MAX_PENDING_LAPS) {
                        pendingLapResults.removeFirst() // Drop oldest to make room
                    }
                    pendingLapResults.addLast(lapMessage)
                    lastRelayedStopSensorNanos = stopNanos
                }
                // If disconnected and NOT trying to reconnect, don't cache (original behavior)
            }
        }

        val monitoringSyncMode = when {
            !isClient || !hasPeers || raceState.stage == SessionStage.SETUP -> "-"
            raceSessionController.hasFreshGpsLock() -> "GPS"
            raceSessionController.hasFreshClockLock() -> "NTP"
            else -> "-"
        }
        val monitoringLatencyMs = if (
            isClient &&
            hasPeers &&
            monitoringSyncMode == "NTP" &&
            clockState.hostClockRoundTripNanos != null
        ) {
            (clockState.hostClockRoundTripNanos.toDouble() / 1_000_000.0).roundToInt()
        } else {
            null
        }

        val clockLockWarningText = if (
            isClient &&
            !isPassiveDisplayClient &&
            raceState.monitoringActive &&
            hasPeers &&
            localRole != SessionDeviceRole.UNASSIGNED &&
            !raceSessionController.hasFreshAnyClockLock()
        ) {
            "Clock sync lock is invalid. Triggers from this device are being dropped until sync recovers."
        } else {
            null
        }

        val runStatusLabel = when {
            timelineForUi.hostStartSensorNanos == null -> "Ready"
            timelineForUi.hostStopSensorNanos != null -> "Finished"
            raceState.monitoringActive -> "Running"
            else -> "Armed"
        }
        val marksCount = timelineForUi.hostSplitSensorNanos.size + if (timelineForUi.hostStopSensorNanos != null) 1 else 0

        val elapsedDisplay = formatElapsedDisplay(
            startedSensorNanos = timelineForUi.hostStartSensorNanos,
            stoppedSensorNanos = timelineForUi.hostStopSensorNanos,
            monitoringActive = raceState.monitoringActive,
        )

        val cameraModeLabel = if (isPassiveDisplayClient) {
            "-"
        } else if (motionState.observedFps == null) {
            "INIT"
        } else {
            "NORMAL"
        }
        val triggerHistory = if (isPassiveDisplayClient) {
            emptyList()
        } else {
            motionState.triggerHistory.map { trigger ->
                val roleLabel = when (trigger.triggerType.lowercase()) {
                    "start" -> "START"
                    "stop" -> "STOP"
                    else -> trigger.triggerType.uppercase()
                }
                "$roleLabel at ${trigger.triggerSensorNanos}ns (score ${"%.4f".format(trigger.score)})"
            }
        }
        val splitHistory = buildSplitHistoryForTimeline(
            startedSensorNanos = timelineForUi.hostStartSensorNanos,
            splitSensorNanos = timelineForUi.hostSplitSensorNanos,
        )

        val clockSummary = when {
            raceSessionController.hasFreshClockLock() && clockState.hostMinusClientElapsedNanos != null -> {
                "Locked ${clockState.hostMinusClientElapsedNanos}ns"
            }

            clockState.hostMinusClientElapsedNanos != null -> {
                "Stale ${clockState.hostMinusClientElapsedNanos}ns"
            }

            else -> "Unlocked"
        }
        val displayEndpointIdsForRows = if (mode == SessionOperatingMode.DISPLAY_HOST) {
            connectionsManager.connectedEndpoints().filterNot { endpointId ->
                displayControllerEndpointIds.contains(endpointId) ||
                    isControllerEndpointName(displayHostDeviceNamesByEndpointId[endpointId])
            }.toSet()
        } else {
            connectionsManager.connectedEndpoints()
        }
        val displayLapRows = buildDisplayLapRowsForConnectedDevices(
            connectedEndpointIds = displayEndpointIdsForRows,
            deviceNamesByEndpointId = displayHostDeviceNamesByEndpointId,
            elapsedByEndpointId = displayLatestLapByEndpointId,
            limitMillisByEndpointId = displayLimitMillisByEndpointId,
            hostStartSensorNanos = timelineForUi.hostStartSensorNanos,
            hostStopSensorNanos = timelineForUi.hostStopSensorNanos,
            monitoringActive = raceState.monitoringActive,
            nowSensorNanos = raceSessionController.estimateLocalSensorNanosNow(),
        )
        updateUiState {
            copy(
                stage = raceState.stage,
                operatingMode = mode,
                networkRole = raceState.networkRole,
                sessionSummary = raceState.stage.name.lowercase(),
                monitoringSummary = monitoringSummary,
                userMonitoringEnabled = userMonitoringEnabled,
                clockSummary = clockSummary,
                startedSensorNanos = timelineForUi.hostStartSensorNanos,
                stoppedSensorNanos = timelineForUi.hostStopSensorNanos,
                devices = raceState.devices,
                canStartMonitoring = mode == SessionOperatingMode.SINGLE_DEVICE && raceSessionController.canStartMonitoring(),
                isHost = isHost,
                localRole = localRole,
                monitoringConnectionTypeLabel = if (hasPeers) {
                    "TCP (${resolveTcpHostAddress(activeWifiGatewayIp(), BuildConfig.FALLBACK_HOST_IP)}:${BuildConfig.TCP_HOST_PORT})"
                } else {
                    "-"
                },
                monitoringSyncModeLabel = monitoringSyncMode,
                monitoringLatencyMs = monitoringLatencyMs,
                hasConnectedPeers = hasPeers,
                clockLockWarningText = clockLockWarningText,
                runStatusLabel = runStatusLabel,
                runMarksCount = marksCount,
                elapsedDisplay = elapsedDisplay,
                threshold = motionState.config.threshold,
                roiCenterX = motionState.config.roiCenterX,
                roiWidth = motionState.config.roiWidth,
                cooldownMs = motionState.config.cooldownMs,
                processEveryNFrames = motionState.config.processEveryNFrames,
                observedFps = motionState.observedFps,
                cameraFpsModeLabel = cameraModeLabel,
                targetFpsUpper = motionState.targetFpsUpper,
                rawScore = motionState.rawScore,
                baseline = motionState.baseline,
                effectiveScore = motionState.effectiveScore,
                frameSensorNanos = motionState.lastFrameSensorNanos,
                streamFrameCount = motionState.streamFrameCount,
                processedFrameCount = motionState.processedFrameCount,
                triggerHistory = triggerHistory,
                splitHistory = splitHistory,
                discoveredEndpoints = if (mode == SessionOperatingMode.SINGLE_DEVICE) {
                    displayDiscoveredHosts.toMap()
                } else {
                    raceState.discoveredEndpoints
                },
                connectedEndpoints = liveConnectedEndpoints,
                networkSummary = "${connectionsManager.currentRole().name.lowercase()} mode, ${liveConnectedEndpoints.size} connected",
                displayLapRows = displayLapRows,
                displayConnectedHostName = displayConnectedHostName,
                displayConnectedHostEndpointId = displayConnectedHostEndpointId,
                displayDiscoveryActive = displayDiscoveryActive,
                controllerTargetEndpoints = controllerTargetDeviceNamesByEndpointId.toMap(),
            )
        }
    }

    private fun appendEvent(message: String) {
        val previous = uiState.value.recentEvents
        val updated = (listOf(message) + previous).take(10)
        updateUiState { copy(recentEvents = updated) }
    }

    private fun deniedPermissions(scope: PermissionScope): List<String> {
        return requiredPermissions(scope).filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(scope: PermissionScope): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (scope == PermissionScope.CAMERA_AND_NETWORK) {
            permissions += Manifest.permission.CAMERA
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.BLUETOOTH_SCAN
        }
        return permissions.distinct()
    }

    private fun localEndpointName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        val baseName = when {
            model.isNotEmpty() -> model
            Build.DEVICE?.trim().orEmpty().isNotEmpty() -> Build.DEVICE.trim()
            else -> "Android Device"
        }
        return if (setupActionProfile == SetupActionProfile.CONTROLLER_ONLY) {
            if (baseName.contains("controller", ignoreCase = true)) {
                baseName
            } else {
                "$baseName (Controller)"
            }
        } else {
            baseName
        }
    }

    private fun localDeviceId(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?.trim()
            .orEmpty()
        if (androidId.isNotEmpty()) {
            return "android-$androidId"
        }
        return "local-${Build.DEVICE.orEmpty()}"
    }

    private fun activeWifiGatewayIp(): String? {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork) ?: return null
        val routeGateway = linkProperties.routes
            .firstOrNull { it.isDefaultRoute }
            ?.gateway
            ?.hostAddress
        if (!routeGateway.isNullOrBlank()) {
            return routeGateway
        }

        // Some OEM stacks (observed on OnePlus) omit default routes while still exposing DHCP gateway.
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val dhcpGateway = wifiManager.dhcpInfo?.gateway ?: 0
        return ipv4FromLittleEndianInt(dhcpGateway)
    }

    private fun shouldRunLocalMonitoring(): Boolean {
        return shouldRunLocalMonitoring(
            mode = raceSessionController.uiState.value.operatingMode,
            userMonitoringEnabled = userMonitoringEnabled,
            localRole = raceSessionController.localDeviceRole(),
        )
    }

    private fun applyLocalMonitoringConfigFromSession() {
        val current = motionDetectionController.uiState.value.config
        val cameraFacing = when (raceSessionController.localCameraFacing()) {
            SessionCameraFacing.FRONT -> MotionCameraFacing.FRONT
            SessionCameraFacing.REAR -> MotionCameraFacing.REAR
        }
        val next = current.copy(
            cameraFacing = cameraFacing,
        )
        motionDetectionController.updateConfig(next)
    }

    private fun formatElapsedDisplay(
        startedSensorNanos: Long?,
        stoppedSensorNanos: Long?,
        monitoringActive: Boolean,
    ): String {
        val started = startedSensorNanos ?: return "00.00"
        val terminal = stoppedSensorNanos ?: if (monitoringActive) {
            raceSessionController.estimateLocalSensorNanosNow()
        } else {
            started
        }
        val elapsedNanos = (terminal - started).coerceAtLeast(0L)
        val totalMillis = elapsedNanos / 1_000_000L
        return formatElapsedTimerDisplay(totalMillis)
    }

    private fun formatElapsedDuration(durationNanos: Long): String {
        val totalMillis = (durationNanos / 1_000_000L).coerceAtLeast(0L)
        return formatElapsedTimerDisplay(totalMillis)
    }

    private fun updateUiState(update: SprintSyncUiState.() -> SprintSyncUiState) {
        uiState.value = uiState.value.update()
    }

    private fun startTimerRefreshLoop() {
        if (timerRefreshJob?.isActive == true) {
            return
        }
        logRuntimeDiagnostic("timer refresh loop started")
        timerRefreshJob = lifecycleScope.launch {
            try {
                while (isActive) {
                    val raceState = raceSessionController.uiState.value
                    val mode = uiState.value.operatingMode
                    val isDisplayRole = mode == SessionOperatingMode.DISPLAY_HOST ||
                        raceState.deviceRole == SessionDeviceRole.DISPLAY
                    val connectedEndpointIds = connectionsManager.connectedEndpoints()
                    val hasPendingDisplayFinals = connectedEndpointIds.isNotEmpty() &&
                        connectedEndpointIds.any { endpointId ->
                            displayLatestLapByEndpointId[endpointId] == null
                        }
                    val shouldRefreshForDisplayRows = isDisplayRole &&
                        raceState.timeline.hostStartSensorNanos != null &&
                        hasPendingDisplayFinals
                    if (!isAppResumed || (!raceState.monitoringActive && !shouldRefreshForDisplayRows)) {
                        break
                    }
                    if ((raceState.timeline.hostStartSensorNanos != null &&
                            raceState.timeline.hostStopSensorNanos == null) ||
                        shouldRefreshForDisplayRows
                    ) {
                        syncControllerSummaries()
                    }
                    delay(TIMER_REFRESH_INTERVAL_MS)
                }
            } finally {
                logRuntimeDiagnostic("timer refresh loop stopped")
                timerRefreshJob = null
            }
        }
    }

    private fun stopTimerRefreshLoop() {
        timerRefreshJob?.cancel()
        timerRefreshJob = null
    }

    private fun applyRequestedOrientationForMode(mode: SessionOperatingMode) {
        val targetOrientation = requestedOrientationForMode(mode)
        if (requestedOrientation != targetOrientation) {
            requestedOrientation = targetOrientation
        }
        applySystemUiForMode(mode)
    }

    private fun applySystemUiForMode(mode: SessionOperatingMode) {
        val immersive = shouldUseImmersiveModeForMode(mode)
        WindowCompat.setDecorFitsSystemWindows(window, !immersive)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (immersive) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun flushPendingLapResults() {
        val hostEndpoint = displayConnectedHostEndpointId ?: return

        while (pendingLapResults.isNotEmpty()) {
            val lapMessage = pendingLapResults.removeFirst()
            connectionsManager.sendMessage(hostEndpoint, lapMessage.toJsonString()) { result ->
                if (result.isFailure) {
                    // Re-queue at front if send fails, will retry on next flush
                    pendingLapResults.addFirst(lapMessage)
                }
            }
        }
    }

    private fun sendControllerCommandToDisplayHost(
        action: SessionControlAction,
        targetEndpointId: String,
        limitMillis: Long?,
    ) {
        val hostEndpoint = displayConnectedHostEndpointId
        if (hostEndpoint.isNullOrBlank()) {
            appendEvent("controller not connected to display host")
            return
        }
        val payload = SessionControlCommandMessage(
            action = action,
            targetEndpointId = targetEndpointId,
            senderDeviceName = localEndpointName(),
            limitMillis = limitMillis,
        ).toJsonString()
        connectionsManager.sendMessage(hostEndpoint, payload) { result ->
            result.exceptionOrNull()?.let { error ->
                appendEvent("controller command error: ${error.localizedMessage ?: "unknown"}")
            }
        }
    }

    private fun broadcastControllerTargetsSnapshotToConnectedEndpoints() {
        if (uiState.value.operatingMode != SessionOperatingMode.DISPLAY_HOST) {
            return
        }
        val connectedEndpoints = connectionsManager.connectedEndpoints()
        if (connectedEndpoints.isEmpty()) {
            return
        }
        connectedEndpoints.forEach { endpointId ->
            if (!displayControllerEndpointIds.contains(endpointId)) {
                return@forEach
            }
            val targets = connectedEndpoints
                .asSequence()
                .filter { candidateEndpoint -> candidateEndpoint != endpointId }
                .filter { candidateEndpoint -> !displayControllerEndpointIds.contains(candidateEndpoint) }
                .filter { candidateEndpoint ->
                    val candidateName = displayHostDeviceNamesByEndpointId[candidateEndpoint].orEmpty()
                    !candidateName.contains("controller", ignoreCase = true)
                }
                .map { candidateEndpoint ->
                    SessionControllerTarget(
                        endpointId = candidateEndpoint,
                        deviceName = displayHostDeviceNamesByEndpointId[candidateEndpoint]
                            ?.takeIf { it.isNotBlank() }
                            ?: candidateEndpoint,
                    )
                }
                .toList()
            val payload = SessionControllerTargetsMessage(
                senderDeviceName = localEndpointName(),
                targets = targets,
            ).toJsonString()
            connectionsManager.sendMessage(endpointId, payload) { result ->
                result.exceptionOrNull()?.let { error ->
                    appendEvent("target snapshot error: ${error.localizedMessage ?: "unknown"}")
                }
            }
        }
    }

    private fun clearDisplayRelayReconnectionState() {
        displayReconnectionPending = false
        pendingLapResults.clear()
    }

    private fun clearDisplayHostLapState() {
        displayControllerEndpointIds.clear()
        displayHostDeviceNamesByEndpointId.clear()
        displayLatestLapByEndpointId.clear()
        displayLimitMillisByEndpointId.clear()
    }

    private fun logRuntimeDiagnostic(message: String) {
        Log.d(TAG, "diag: $message")
    }
}

internal enum class LocalCaptureAction {
    START,
    STOP,
    NONE,
}

internal enum class AutoStartRole {
    NONE,
    DISPLAY,
    CONTROLLER,
    SINGLE,
}

internal fun resolveAutoStartRole(configuredRole: String): AutoStartRole {
    return when (configuredRole.trim().lowercase()) {
        "display", "host" -> AutoStartRole.DISPLAY
        "controller" -> AutoStartRole.CONTROLLER
        "single", "client" -> AutoStartRole.SINGLE
        else -> AutoStartRole.NONE
    }
}

internal fun resolveEffectiveAutoStartRole(configuredRole: String, flavorName: String): String {
    if (isOneplusControllerFlavor(flavorName)) {
        return "controller"
    }
    val normalizedRole = configuredRole.trim().lowercase()
    return if (normalizedRole.isNotBlank()) normalizedRole else "none"
}

internal fun isOneplusControllerFlavor(flavorName: String): Boolean =
    flavorName.trim().equals("oneplusSingle", ignoreCase = true)

internal fun resolveTcpHostAddress(gatewayIp: String?, fallbackIp: String = "192.168.43.1"): String {
    val gateway = gatewayIp?.trim().orEmpty()
    return gateway.ifBlank { fallbackIp }
}

internal fun reconnectDelayMillis(attempt: Int): Long {
    val clamped = attempt.coerceAtLeast(0).coerceAtMost(6)
    val delay = 500L shl clamped
    return delay.coerceAtMost(5_000L)
}

internal fun ipv4FromLittleEndianInt(value: Int): String? {
    if (value == 0) {
        return null
    }
    val b1 = value and 0xFF
    val b2 = (value shr 8) and 0xFF
    val b3 = (value shr 16) and 0xFF
    val b4 = (value shr 24) and 0xFF
    return "$b1.$b2.$b3.$b4"
}

internal fun resolveLocalCaptureAction(
    monitoringActive: Boolean,
    isAppResumed: Boolean,
    shouldRunLocalCapture: Boolean,
    isLocalMotionMonitoring: Boolean,
    localCaptureStartPending: Boolean,
): LocalCaptureAction {
    if (
        monitoringActive &&
        isAppResumed &&
        shouldRunLocalCapture &&
        !isLocalMotionMonitoring &&
        !localCaptureStartPending
    ) {
        return LocalCaptureAction.START
    }
    if (
        (isLocalMotionMonitoring || localCaptureStartPending) &&
        (!monitoringActive || !isAppResumed || !shouldRunLocalCapture)
    ) {
        return LocalCaptureAction.STOP
    }
    return LocalCaptureAction.NONE
}

internal fun shouldKeepTimerRefreshActive(
    monitoringActive: Boolean,
    isAppResumed: Boolean,
    hasStopSensor: Boolean,
): Boolean {
    return monitoringActive && isAppResumed && !hasStopSensor
}

internal fun shouldUseLandscapeForMode(mode: SessionOperatingMode): Boolean = mode == SessionOperatingMode.DISPLAY_HOST

internal fun shouldUseImmersiveModeForMode(mode: SessionOperatingMode): Boolean =
    mode == SessionOperatingMode.DISPLAY_HOST

internal fun shouldApplyLiveLocalCameraFacingUpdate(
    isLocalMotionMonitoring: Boolean,
    assignedDeviceId: String,
    localDeviceId: String,
): Boolean {
    return isLocalMotionMonitoring && assignedDeviceId == localDeviceId
}

internal fun shouldUsePassiveDisplayClientMode(
    mode: SessionOperatingMode,
    networkRole: SessionNetworkRole,
    localRole: SessionDeviceRole,
): Boolean {
    return mode == SessionOperatingMode.SINGLE_DEVICE &&
        networkRole == SessionNetworkRole.CLIENT &&
        localRole == SessionDeviceRole.DISPLAY
}

internal fun shouldRunLocalMonitoring(
    mode: SessionOperatingMode,
    userMonitoringEnabled: Boolean,
    localRole: SessionDeviceRole,
): Boolean {
    if (mode == SessionOperatingMode.DISPLAY_HOST || localRole == SessionDeviceRole.DISPLAY) {
        return false
    }
    return userMonitoringEnabled && localRole != SessionDeviceRole.UNASSIGNED
}

internal fun controllerInitialStage(): SessionStage = SessionStage.MONITORING

internal fun isControllerEndpointName(deviceName: String?): Boolean {
    return deviceName?.contains("controller", ignoreCase = true) == true
}

internal fun buildDisplayLapRowsForConnectedDevices(
    connectedEndpointIds: Set<String>,
    deviceNamesByEndpointId: Map<String, String>,
    elapsedByEndpointId: Map<String, Long>,
    limitMillisByEndpointId: Map<String, Long>,
    hostStartSensorNanos: Long?,
    hostStopSensorNanos: Long?,
    monitoringActive: Boolean,
    nowSensorNanos: Long,
): List<DisplayLapRow> {
    val shouldShowLiveElapsed = hostStartSensorNanos != null &&
        (monitoringActive || hostStopSensorNanos != null)
    val liveElapsedNanos = if (shouldShowLiveElapsed) {
        (nowSensorNanos - (hostStartSensorNanos ?: 0L)).coerceAtLeast(0L)
    } else {
        null
    }
    return connectedEndpointIds.map { endpointId ->
        val deviceName = deviceNamesByEndpointId[endpointId]?.takeIf { it.isNotBlank() } ?: endpointId
        val elapsedNanos = elapsedByEndpointId[endpointId] ?: liveElapsedNanos
        val lapTimeLabel = elapsedNanos?.let { elapsed ->
            val totalMillis = (elapsed / 1_000_000L).coerceAtLeast(0L)
            formatElapsedTimerDisplay(totalMillis)
        } ?: "READY"
        val limitMillis = limitMillisByEndpointId[endpointId]
        val limitNanos = limitMillis?.times(1_000_000L)
        val isOverLimit = limitNanos != null && elapsedNanos != null && elapsedNanos > limitNanos
        val isUnderLimit = limitNanos != null && elapsedNanos != null && elapsedNanos <= limitNanos
        DisplayLapRow(
            deviceName = deviceName,
            lapTimeLabel = lapTimeLabel,
            limitLabel = limitMillis?.let(::formatDisplayLimitLabel),
            isOverLimit = isOverLimit,
            isUnderLimit = isUnderLimit,
        )
    }
}

internal fun formatDisplayLimitLabel(limitMillis: Long): String = "Limit ${limitMillis} ms"

internal fun formatElapsedTimerDisplay(totalMillis: Long): String {
    val clamped = totalMillis.coerceAtLeast(0L)
    val totalSeconds = clamped / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val centiseconds = (clamped % 1_000L) / 10L
    return if (minutes > 0L) {
        String.format("%02d:%02d.%02d", minutes, seconds, centiseconds)
    } else {
        String.format("%02d.%02d", seconds, centiseconds)
    }
}

internal fun buildSplitHistoryForTimeline(
    startedSensorNanos: Long?,
    splitSensorNanos: List<Long>,
): List<String> {
    val started = startedSensorNanos ?: return emptyList()
    return splitSensorNanos.mapIndexedNotNull { index, splitSensor ->
        if (splitSensor <= started) {
            null
        } else {
            val elapsedMillis = (splitSensor - started) / 1_000_000L
            "Split ${index + 1}: ${formatElapsedTimerDisplay(elapsedMillis)}"
        }
    }
}

internal fun requestedOrientationForMode(mode: SessionOperatingMode): Int = if (shouldUseLandscapeForMode(mode)) {
    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
} else {
    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}
