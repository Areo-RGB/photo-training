package com.paul.sprintsync.features.race_session

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class SessionStage {
    SETUP,
    LOBBY,
    MONITORING,
}

enum class SessionOperatingMode {
    SINGLE_DEVICE,
    DISPLAY_HOST,
}

enum class SessionNetworkRole {
    NONE,
    HOST,
    CLIENT,
}

enum class SessionDeviceRole {
    UNASSIGNED,
    START,
    SPLIT,
    STOP,
    DISPLAY,
}

enum class SessionCameraFacing {
    REAR,
    FRONT,
}

data class SessionDevice(
    val id: String,
    val name: String,
    val role: SessionDeviceRole,
    val cameraFacing: SessionCameraFacing = SessionCameraFacing.REAR,
    val isLocal: Boolean,
) {
    fun toJsonObject(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("role", role.name.lowercase())
            .put("cameraFacing", cameraFacing.name.lowercase())
            .put("isLocal", isLocal)
    }

    companion object {
        fun fromJsonObject(decoded: JSONObject): SessionDevice? {
            val id = decoded.optString("id", "").trim()
            val name = decoded.optString("name", "").trim()
            val role = sessionDeviceRoleFromName(decoded.readOptionalString("role"))
            val cameraFacing = sessionCameraFacingFromName(decoded.readOptionalString("cameraFacing"))
                ?: SessionCameraFacing.REAR
            if (id.isEmpty() || name.isEmpty() || role == null) {
                return null
            }
            return SessionDevice(
                id = id,
                name = name,
                role = role,
                cameraFacing = cameraFacing,
                isLocal = decoded.optBoolean("isLocal", false),
            )
        }
    }
}

data class SessionSnapshotMessage(
    val stage: SessionStage,
    val monitoringActive: Boolean,
    val devices: List<SessionDevice>,
    val hostStartSensorNanos: Long?,
    val hostStopSensorNanos: Long?,
    val hostSplitSensorNanos: List<Long> = emptyList(),
    val runId: String?,
    val hostSensorMinusElapsedNanos: Long?,
    val hostGpsUtcOffsetNanos: Long?,
    val hostGpsFixAgeNanos: Long?,
    val selfDeviceId: String?,
) {
    fun toJsonString(): String {
        val devicesArray = JSONArray()
        devices.forEach { devicesArray.put(it.toJsonObject()) }
        val timeline = JSONObject()
            .put("hostStartSensorNanos", hostStartSensorNanos ?: JSONObject.NULL)
            .put("hostStopSensorNanos", hostStopSensorNanos ?: JSONObject.NULL)
            .put("hostSplitSensorNanos", hostSplitSensorNanos.toJsonArray())
        return JSONObject()
            .put("type", TYPE)
            .put("stage", stage.name.lowercase())
            .put("monitoringActive", monitoringActive)
            .put("devices", devicesArray)
            .put("timeline", timeline)
            .put("runId", runId ?: JSONObject.NULL)
            .put("hostSensorMinusElapsedNanos", hostSensorMinusElapsedNanos ?: JSONObject.NULL)
            .put("hostGpsUtcOffsetNanos", hostGpsUtcOffsetNanos ?: JSONObject.NULL)
            .put("hostGpsFixAgeNanos", hostGpsFixAgeNanos ?: JSONObject.NULL)
            .put("selfDeviceId", selfDeviceId ?: JSONObject.NULL)
            .toString()
    }

    companion object {
        const val TYPE = "snapshot"

        fun tryParse(raw: String): SessionSnapshotMessage? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (decoded.optString("type") != TYPE) {
                return null
            }
            val stage = sessionStageFromName(decoded.readOptionalString("stage")) ?: return null
            val devicesRaw = decoded.optJSONArray("devices") ?: return null
            val parsedDevices = mutableListOf<SessionDevice>()
            for (index in 0 until devicesRaw.length()) {
                val item = devicesRaw.optJSONObject(index) ?: continue
                val parsed = SessionDevice.fromJsonObject(item) ?: continue
                parsedDevices += parsed
            }
            if (parsedDevices.isEmpty()) {
                return null
            }
            val timeline = decoded.optJSONObject("timeline") ?: JSONObject()
            return SessionSnapshotMessage(
                stage = stage,
                monitoringActive = decoded.optBoolean("monitoringActive", false),
                devices = parsedDevices,
                hostStartSensorNanos = timeline.readOptionalLong("hostStartSensorNanos"),
                hostStopSensorNanos = timeline.readOptionalLong("hostStopSensorNanos"),
                hostSplitSensorNanos = timeline.readOptionalLongArray("hostSplitSensorNanos"),
                runId = decoded.optString("runId", "").ifBlank { null },
                hostSensorMinusElapsedNanos = decoded.readOptionalLong("hostSensorMinusElapsedNanos"),
                hostGpsUtcOffsetNanos = decoded.readOptionalLong("hostGpsUtcOffsetNanos"),
                hostGpsFixAgeNanos = decoded.readOptionalLong("hostGpsFixAgeNanos"),
                selfDeviceId = decoded.optString("selfDeviceId", "").ifBlank { null },
            )
        }
    }
}

data class SessionTriggerRequestMessage(
    val role: SessionDeviceRole,
    val triggerSensorNanos: Long,
    val mappedHostSensorNanos: Long?,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("type", TYPE)
            .put("role", role.name.lowercase())
            .put("triggerSensorNanos", triggerSensorNanos)
            .put("mappedHostSensorNanos", mappedHostSensorNanos ?: JSONObject.NULL)
            .toString()
    }

    companion object {
        const val TYPE = "trigger_request"

        fun tryParse(raw: String): SessionTriggerRequestMessage? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (decoded.optString("type") != TYPE) {
                return null
            }
            val role = sessionDeviceRoleFromName(decoded.readOptionalString("role")) ?: return null
            val triggerSensorNanos = decoded.optLong("triggerSensorNanos", Long.MIN_VALUE)
            if (triggerSensorNanos == Long.MIN_VALUE) {
                return null
            }
            return SessionTriggerRequestMessage(
                role = role,
                triggerSensorNanos = triggerSensorNanos,
                mappedHostSensorNanos = decoded.readOptionalLong("mappedHostSensorNanos"),
            )
        }
    }
}

data class SessionTriggerRefinementMessage(
    val runId: String,
    val role: SessionDeviceRole,
    val provisionalHostSensorNanos: Long,
    val refinedHostSensorNanos: Long,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("type", TYPE)
            .put("runId", runId)
            .put("role", role.name.lowercase())
            .put("provisionalHostSensorNanos", provisionalHostSensorNanos)
            .put("refinedHostSensorNanos", refinedHostSensorNanos)
            .toString()
    }

    companion object {
        const val TYPE = "trigger_refinement"

        fun tryParse(raw: String): SessionTriggerRefinementMessage? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (decoded.optString("type") != TYPE) {
                return null
            }
            val runId = decoded.optString("runId", "").trim()
            val role = sessionDeviceRoleFromName(decoded.readOptionalString("role"))
            val provisional = decoded.optLong("provisionalHostSensorNanos", Long.MIN_VALUE)
            val refined = decoded.optLong("refinedHostSensorNanos", Long.MIN_VALUE)
            if (runId.isEmpty() || role == null || provisional == Long.MIN_VALUE || refined == Long.MIN_VALUE) {
                return null
            }
            return SessionTriggerRefinementMessage(
                runId = runId,
                role = role,
                provisionalHostSensorNanos = provisional,
                refinedHostSensorNanos = refined,
            )
        }
    }
}

data class SessionTimelineSnapshotMessage(
    val hostStartSensorNanos: Long?,
    val hostStopSensorNanos: Long?,
    val hostSplitSensorNanos: List<Long> = emptyList(),
    val sentElapsedNanos: Long,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("type", TYPE)
            .put("hostStartSensorNanos", hostStartSensorNanos ?: JSONObject.NULL)
            .put("hostStopSensorNanos", hostStopSensorNanos ?: JSONObject.NULL)
            .put("hostSplitSensorNanos", hostSplitSensorNanos.toJsonArray())
            .put("sentElapsedNanos", sentElapsedNanos)
            .toString()
    }

    companion object {
        const val TYPE = "timeline_snapshot"

        fun tryParse(raw: String): SessionTimelineSnapshotMessage? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (decoded.optString("type") != TYPE) {
                return null
            }
            val sentElapsedNanos = decoded.optLong("sentElapsedNanos", Long.MIN_VALUE)
            if (sentElapsedNanos == Long.MIN_VALUE) {
                return null
            }
            val hostStartSensorNanos = decoded.readOptionalLong("hostStartSensorNanos")
            val hostStopSensorNanos = decoded.readOptionalLong("hostStopSensorNanos")
            val hostSplitSensorNanos = decoded.readOptionalLongArray("hostSplitSensorNanos")
            return SessionTimelineSnapshotMessage(
                hostStartSensorNanos = hostStartSensorNanos,
                hostStopSensorNanos = hostStopSensorNanos,
                hostSplitSensorNanos = hostSplitSensorNanos,
                sentElapsedNanos = sentElapsedNanos,
            )
        }
    }
}

data class SessionTriggerMessage(
    val triggerType: String,
    val triggerSensorNanos: Long,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("type", TYPE)
            .put("triggerType", triggerType)
            .put("triggerSensorNanos", triggerSensorNanos)
            .toString()
    }

    companion object {
        const val TYPE = "session_trigger"

        fun tryParse(raw: String): SessionTriggerMessage? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (decoded.optString("type") != TYPE) {
                return null
            }
            val triggerType = decoded.optString("triggerType", "").trim()
            val triggerSensorNanos = decoded.optLong("triggerSensorNanos", Long.MIN_VALUE)
            if (triggerType.isEmpty() || triggerSensorNanos == Long.MIN_VALUE) {
                return null
            }
            return SessionTriggerMessage(
                triggerType = triggerType,
                triggerSensorNanos = triggerSensorNanos,
            )
        }
    }
}

data class SessionDeviceIdentityMessage(
    val stableDeviceId: String,
    val deviceName: String,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("type", TYPE)
            .put("stableDeviceId", stableDeviceId)
            .put("deviceName", deviceName)
            .toString()
    }

    companion object {
        const val TYPE = "device_identity"

        fun tryParse(raw: String): SessionDeviceIdentityMessage? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (decoded.optString("type") != TYPE) {
                return null
            }
            val stableDeviceId = decoded.optString("stableDeviceId", "").trim()
            val deviceName = decoded.optString("deviceName", "").trim()
            if (stableDeviceId.isEmpty() || deviceName.isEmpty()) {
                return null
            }
            return SessionDeviceIdentityMessage(
                stableDeviceId = stableDeviceId,
                deviceName = deviceName,
            )
        }
    }
}

data class SessionLapResultMessage(
    val senderDeviceName: String,
    val startedSensorNanos: Long,
    val stoppedSensorNanos: Long,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("type", TYPE)
            .put("senderDeviceName", senderDeviceName)
            .put("startedSensorNanos", startedSensorNanos)
            .put("stoppedSensorNanos", stoppedSensorNanos)
            .toString()
    }

    companion object {
        const val TYPE = "lap_result"

        fun tryParse(raw: String): SessionLapResultMessage? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (decoded.optString("type") != TYPE) {
                return null
            }
            val senderDeviceName = decoded.optString("senderDeviceName", "").trim()
            val startedSensorNanos = decoded.optLong("startedSensorNanos", Long.MIN_VALUE)
            val stoppedSensorNanos = decoded.optLong("stoppedSensorNanos", Long.MIN_VALUE)
            if (
                senderDeviceName.isEmpty() ||
                startedSensorNanos == Long.MIN_VALUE ||
                stoppedSensorNanos == Long.MIN_VALUE ||
                stoppedSensorNanos <= startedSensorNanos
            ) {
                return null
            }
            return SessionLapResultMessage(
                senderDeviceName = senderDeviceName,
                startedSensorNanos = startedSensorNanos,
                stoppedSensorNanos = stoppedSensorNanos,
            )
        }
    }
}

enum class SessionControlAction {
    RESET_TIMER,
    SET_DISPLAY_LIMIT,
    SET_MOTION_SENSITIVITY,
}

data class SessionControlCommandMessage(
    val action: SessionControlAction,
    val targetEndpointId: String,
    val senderDeviceName: String,
    val limitMillis: Long?,
    val sensitivityPercent: Int?,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("type", TYPE)
            .put("action", action.name.lowercase())
            .put("targetEndpointId", targetEndpointId)
            .put("senderDeviceName", senderDeviceName)
            .put("limitMillis", limitMillis ?: JSONObject.NULL)
            .put("sensitivityPercent", sensitivityPercent ?: JSONObject.NULL)
            .toString()
    }

    companion object {
        const val TYPE = "control_command"

        fun tryParse(raw: String): SessionControlCommandMessage? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (decoded.optString("type") != TYPE) {
                return null
            }
            val action = sessionControlActionFromName(decoded.readOptionalString("action")) ?: return null
            val targetEndpointId = decoded.optString("targetEndpointId", "").trim()
            val senderDeviceName = decoded.optString("senderDeviceName", "").trim()
            val limitMillis = decoded.readOptionalLong("limitMillis")
                ?: decoded.readOptionalLong("limitSeconds")?.times(1_000L)
            val sensitivityPercent = decoded.readOptionalInt("sensitivityPercent")
            if (targetEndpointId.isEmpty() || senderDeviceName.isEmpty()) {
                return null
            }
            if (action == SessionControlAction.SET_DISPLAY_LIMIT && (limitMillis == null || limitMillis <= 0L)) {
                return null
            }
            if (
                action == SessionControlAction.SET_MOTION_SENSITIVITY &&
                (sensitivityPercent == null || sensitivityPercent !in 0..100)
            ) {
                return null
            }
            return SessionControlCommandMessage(
                action = action,
                targetEndpointId = targetEndpointId,
                senderDeviceName = senderDeviceName,
                limitMillis = limitMillis,
                sensitivityPercent = sensitivityPercent,
            )
        }
    }
}

data class SessionControllerTarget(
    val endpointId: String,
    val deviceName: String,
)

data class SessionControllerTargetsMessage(
    val senderDeviceName: String,
    val targets: List<SessionControllerTarget>,
) {
    fun toJsonString(): String {
        val targetsJson = JSONArray()
        targets.forEach { target ->
            targetsJson.put(
                JSONObject()
                    .put("endpointId", target.endpointId)
                    .put("deviceName", target.deviceName),
            )
        }
        return JSONObject()
            .put("type", TYPE)
            .put("senderDeviceName", senderDeviceName)
            .put("targets", targetsJson)
            .toString()
    }

    companion object {
        const val TYPE = "controller_targets"

        fun tryParse(raw: String): SessionControllerTargetsMessage? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (decoded.optString("type") != TYPE) {
                return null
            }
            val senderDeviceName = decoded.optString("senderDeviceName", "").trim()
            if (senderDeviceName.isEmpty()) {
                return null
            }
            val rawTargets = decoded.optJSONArray("targets") ?: return null
            val targets = mutableListOf<SessionControllerTarget>()
            for (index in 0 until rawTargets.length()) {
                val item = rawTargets.optJSONObject(index) ?: continue
                val endpointId = item.optString("endpointId", "").trim()
                val deviceName = item.optString("deviceName", "").trim()
                if (endpointId.isEmpty() || deviceName.isEmpty()) {
                    continue
                }
                targets += SessionControllerTarget(
                    endpointId = endpointId,
                    deviceName = deviceName,
                )
            }
            return SessionControllerTargetsMessage(
                senderDeviceName = senderDeviceName,
                targets = targets,
            )
        }
    }
}

data class SessionControllerIdentityMessage(
    val senderDeviceName: String,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("type", TYPE)
            .put("senderDeviceName", senderDeviceName)
            .toString()
    }

    companion object {
        const val TYPE = "controller_identity"

        fun tryParse(raw: String): SessionControllerIdentityMessage? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (decoded.optString("type") != TYPE) {
                return null
            }
            val senderDeviceName = decoded.optString("senderDeviceName", "").trim()
            if (senderDeviceName.isEmpty()) {
                return null
            }
            return SessionControllerIdentityMessage(senderDeviceName = senderDeviceName)
        }
    }
}

fun sessionStageFromName(name: String?): SessionStage? {
    if (name == null) {
        return null
    }
    return SessionStage.values().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
}

fun sessionDeviceRoleFromName(name: String?): SessionDeviceRole? {
    if (name == null) {
        return null
    }
    return SessionDeviceRole.values().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
}

fun sessionCameraFacingFromName(name: String?): SessionCameraFacing? {
    if (name == null) {
        return null
    }
    return SessionCameraFacing.values().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
}

fun sessionDeviceRoleLabel(role: SessionDeviceRole): String {
    return when (role) {
        SessionDeviceRole.UNASSIGNED -> "Unassigned"
        SessionDeviceRole.START -> "Start"
        SessionDeviceRole.SPLIT -> "Split"
        SessionDeviceRole.STOP -> "Stop"
        SessionDeviceRole.DISPLAY -> "Display"
    }
}

fun sessionControlActionFromName(name: String?): SessionControlAction? {
    if (name == null) {
        return null
    }
    return SessionControlAction.values().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
}

fun sessionCameraFacingLabel(facing: SessionCameraFacing): String {
    return when (facing) {
        SessionCameraFacing.REAR -> "Rear"
        SessionCameraFacing.FRONT -> "Front"
    }
}

private fun JSONObject.readOptionalLong(key: String): Long? {
    if (!has(key) || isNull(key)) {
        return null
    }
    val value = optLong(key, Long.MIN_VALUE)
    return value.takeIf { it != Long.MIN_VALUE }
}

private fun JSONObject.readOptionalString(key: String): String? {
    if (!has(key) || isNull(key)) {
        return null
    }
    return optString(key, "").ifBlank { null }
}

private fun JSONObject.readOptionalInt(key: String): Int? {
    if (!has(key) || isNull(key)) {
        return null
    }
    val value = optInt(key, Int.MIN_VALUE)
    return value.takeIf { it != Int.MIN_VALUE }
}

private fun JSONObject.readOptionalLongArray(key: String): List<Long> {
    val raw = optJSONArray(key) ?: return emptyList()
    val values = mutableListOf<Long>()
    for (index in 0 until raw.length()) {
        val value = raw.optLong(index, Long.MIN_VALUE)
        if (value != Long.MIN_VALUE) {
            values += value
        }
    }
    return values
}

private fun List<Long>.toJsonArray(): JSONArray {
    val result = JSONArray()
    forEach { value -> result.put(value) }
    return result
}
