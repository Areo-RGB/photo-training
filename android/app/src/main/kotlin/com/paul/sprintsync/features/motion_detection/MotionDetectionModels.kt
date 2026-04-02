package com.paul.sprintsync.features.motion_detection

import org.json.JSONException
import org.json.JSONObject

enum class MotionCameraFacing(val wireName: String) {
    REAR("rear"),
    FRONT("front"),
    ;

    companion object {
        fun fromWireName(raw: String?): MotionCameraFacing {
            return when (raw?.trim()) {
                FRONT.wireName -> FRONT
                else -> REAR
            }
        }
    }
}

data class MotionDetectionConfig(
    val threshold: Double,
    val roiCenterX: Double,
    val roiWidth: Double,
    val cooldownMs: Int,
    val processEveryNFrames: Int,
    val cameraFacing: MotionCameraFacing,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("threshold", threshold)
            .put("roiCenterX", roiCenterX)
            .put("roiWidth", roiWidth)
            .put("cooldownMs", cooldownMs)
            .put("processEveryNFrames", processEveryNFrames)
            .put("cameraFacing", cameraFacing.wireName)
            .toString()
    }

    companion object {
        fun defaults(): MotionDetectionConfig {
            return MotionDetectionConfig(
                threshold = 0.006,
                roiCenterX = 0.5,
                roiWidth = 0.03,
                cooldownMs = 900,
                processEveryNFrames = 1,
                cameraFacing = MotionCameraFacing.REAR,
            )
        }

        fun fromJsonString(raw: String): MotionDetectionConfig {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return defaults()
            }
            return MotionDetectionConfig(
                threshold = decoded.optDouble("threshold", defaults().threshold),
                roiCenterX = decoded.optDouble("roiCenterX", defaults().roiCenterX),
                roiWidth = decoded.optDouble("roiWidth", defaults().roiWidth),
                cooldownMs = decoded.optInt("cooldownMs", defaults().cooldownMs),
                processEveryNFrames = decoded.optInt("processEveryNFrames", defaults().processEveryNFrames),
                cameraFacing = MotionCameraFacing.fromWireName(decoded.optString("cameraFacing")),
            )
        }
    }
}
