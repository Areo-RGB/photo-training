package com.paul.sprintsync.core.services

import com.paul.sprintsync.features.race_session.SessionClockSyncBinaryResponse

sealed interface NearbyEvent {
    data class EndpointFound(
        val endpointId: String,
        val endpointName: String,
        val serviceId: String,
    ) : NearbyEvent

    data class EndpointLost(
        val endpointId: String,
    ) : NearbyEvent

    data class ConnectionResult(
        val endpointId: String,
        val endpointName: String?,
        val connected: Boolean,
        val statusCode: Int,
        val statusMessage: String?,
    ) : NearbyEvent

    data class EndpointDisconnected(
        val endpointId: String,
    ) : NearbyEvent

    data class PayloadReceived(
        val endpointId: String,
        val message: String,
    ) : NearbyEvent

    data class ClockSyncSampleReceived(
        val endpointId: String,
        val sample: SessionClockSyncBinaryResponse,
    ) : NearbyEvent

    data class Error(
        val message: String,
    ) : NearbyEvent
}
