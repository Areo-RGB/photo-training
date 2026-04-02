package com.paul.sprintsync.core.services

enum class NearbyRole {
    NONE,
    HOST,
    CLIENT,
}

enum class NearbyTransportStrategy(
    val wireValue: String,
) {
    POINT_TO_POINT("point_to_point"),
    POINT_TO_STAR("point_to_star"),
}
