package com.paul.sprintsync.features.motion_detection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paul.sprintsync.core.repositories.LocalRepository
import com.paul.sprintsync.sensor_native.NativeCameraFacing
import com.paul.sprintsync.sensor_native.NativeCameraFpsMode
import com.paul.sprintsync.sensor_native.NativeMonitoringConfig
import com.paul.sprintsync.sensor_native.NativeTriggerEvent
import com.paul.sprintsync.sensor_native.SensorNativeController
import com.paul.sprintsync.sensor_native.SensorNativeEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MotionDetectionUiState(
    val config: MotionDetectionConfig = MotionDetectionConfig.defaults(),
    val monitoring: Boolean = false,
    val loadedFromStorage: Boolean = false,
    val lastError: String? = null,
    val lastDiagnostic: String? = null,
    val lastFrameSensorNanos: Long? = null,
    val lastTriggerSensorNanos: Long? = null,
    val streamFrameCount: Long = 0,
    val processedFrameCount: Long = 0,
    val observedFps: Double? = null,
    val cameraFpsMode: NativeCameraFpsMode = NativeCameraFpsMode.NORMAL,
    val targetFpsUpper: Int? = null,
    val rawScore: Double? = null,
    val baseline: Double? = null,
    val effectiveScore: Double? = null,
    val triggerHistory: List<NativeTriggerEvent> = emptyList(),
)

class MotionDetectionController(
    private val localRepository: LocalRepository,
    private val sensorNativeController: SensorNativeController,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MotionDetectionUiState())
    val uiState: StateFlow<MotionDetectionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(ioDispatcher) {
            val persisted = localRepository.loadMotionConfig()
            _uiState.value = _uiState.value.copy(
                config = persisted,
                loadedFromStorage = true,
            )
            sensorNativeController.updateNativeConfig(persisted.toNativeConfig())
        }
    }

    fun updateConfig(config: MotionDetectionConfig, persist: Boolean = true) {
        _uiState.value = _uiState.value.copy(config = config)
        sensorNativeController.updateNativeConfig(config.toNativeConfig())
        if (!persist) {
            return
        }
        viewModelScope.launch(ioDispatcher) {
            localRepository.saveMotionConfig(config)
        }
    }

    fun updateThreshold(value: Double) {
        updateConfig(_uiState.value.config.copy(threshold = value))
    }

    fun updateRoiCenter(value: Double) {
        updateConfig(_uiState.value.config.copy(roiCenterX = value))
    }

    fun updateRoiWidth(value: Double) {
        updateConfig(_uiState.value.config.copy(roiWidth = value))
    }

    fun updateCooldown(value: Int) {
        updateConfig(_uiState.value.config.copy(cooldownMs = value))
    }

    fun updateProcessEveryNFrames(value: Int) {
        updateConfig(_uiState.value.config.copy(processEveryNFrames = value))
    }

    fun startMonitoring() {
        sensorNativeController.startNativeMonitoring(_uiState.value.config.toNativeConfig()) { result ->
            val error = result.exceptionOrNull()
            if (error == null) {
                _uiState.value = _uiState.value.copy(monitoring = true, lastError = null)
            } else {
                _uiState.value = _uiState.value.copy(monitoring = false, lastError = error.localizedMessage)
            }
        }
    }

    fun stopMonitoring() {
        sensorNativeController.stopNativeMonitoring()
        _uiState.value = _uiState.value.copy(monitoring = false)
    }

    fun handleSensorEvent(event: SensorNativeEvent) {
        when (event) {
            is SensorNativeEvent.FrameStats -> {
                _uiState.value = _uiState.value.copy(
                    lastFrameSensorNanos = event.stats.frameSensorNanos,
                    streamFrameCount = event.streamFrameCount,
                    processedFrameCount = event.processedFrameCount,
                    observedFps = event.observedFps,
                    cameraFpsMode = event.cameraFpsMode,
                    targetFpsUpper = event.targetFpsUpper,
                    rawScore = event.stats.rawScore,
                    baseline = event.stats.baseline,
                    effectiveScore = event.stats.effectiveScore,
                )
            }

            is SensorNativeEvent.Trigger -> {
                val history = (listOf(event.trigger) + _uiState.value.triggerHistory).take(10)
                _uiState.value = _uiState.value.copy(
                    lastTriggerSensorNanos = event.trigger.triggerSensorNanos,
                    triggerHistory = history,
                )
            }

            is SensorNativeEvent.State -> {
                _uiState.value = _uiState.value.copy(monitoring = event.monitoring)
            }

            is SensorNativeEvent.Diagnostic -> {
                _uiState.value = _uiState.value.copy(lastDiagnostic = event.message)
            }

            is SensorNativeEvent.Error -> {
                _uiState.value = _uiState.value.copy(lastError = event.message)
            }
        }
    }

    private fun MotionDetectionConfig.toNativeConfig(): NativeMonitoringConfig {
        val nativeFacing = when (cameraFacing) {
            MotionCameraFacing.FRONT -> NativeCameraFacing.FRONT
            MotionCameraFacing.REAR -> NativeCameraFacing.REAR
        }
        return NativeMonitoringConfig(
            threshold = threshold,
            roiCenterX = roiCenterX,
            roiWidth = roiWidth,
            cooldownMs = cooldownMs,
            processEveryNFrames = processEveryNFrames,
            cameraFacing = nativeFacing,
            highSpeedEnabled = false,
        )
    }
}
