package com.paul.sprintsync.core.models

import org.json.JSONException
import org.json.JSONObject

data class LastRunResult(
    val startedSensorNanos: Long,
    val stoppedSensorNanos: Long,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("startedSensorNanos", startedSensorNanos)
            .put("stoppedSensorNanos", stoppedSensorNanos)
            .toString()
    }

    companion object {
        fun fromJsonString(raw: String): LastRunResult? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (!decoded.has("startedSensorNanos") || !decoded.has("stoppedSensorNanos")) {
                return null
            }
            val startedSensorNanos = decoded.optLong("startedSensorNanos", Long.MIN_VALUE)
            val stoppedSensorNanos = decoded.optLong("stoppedSensorNanos", Long.MIN_VALUE)
            if (startedSensorNanos == Long.MIN_VALUE || stoppedSensorNanos == Long.MIN_VALUE) {
                return null
            }
            return LastRunResult(
                startedSensorNanos = startedSensorNanos,
                stoppedSensorNanos = stoppedSensorNanos,
            )
        }
    }
}
