package com.paul.sprintsync.features.race_session

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SessionClockSyncBinaryRequest(
    val clientSendElapsedNanos: Long,
)

data class SessionClockSyncBinaryResponse(
    val clientSendElapsedNanos: Long,
    val hostReceiveElapsedNanos: Long,
    val hostSendElapsedNanos: Long,
)

object SessionClockSyncBinaryCodec {
    const val VERSION: Byte = 1
    const val TYPE_REQUEST: Byte = 1
    const val TYPE_RESPONSE: Byte = 2

    const val REQUEST_SIZE_BYTES: Int = 10
    const val RESPONSE_SIZE_BYTES: Int = 26

    fun encodeRequest(request: SessionClockSyncBinaryRequest): ByteArray {
        val buffer = ByteBuffer.allocate(REQUEST_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.put(VERSION)
        buffer.put(TYPE_REQUEST)
        buffer.putLong(request.clientSendElapsedNanos)
        return buffer.array()
    }

    fun encodeResponse(response: SessionClockSyncBinaryResponse): ByteArray {
        val buffer = ByteBuffer.allocate(RESPONSE_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.put(VERSION)
        buffer.put(TYPE_RESPONSE)
        buffer.putLong(response.clientSendElapsedNanos)
        buffer.putLong(response.hostReceiveElapsedNanos)
        buffer.putLong(response.hostSendElapsedNanos)
        return buffer.array()
    }

    fun decodeRequest(payload: ByteArray): SessionClockSyncBinaryRequest? {
        if (payload.size != REQUEST_SIZE_BYTES) {
            return null
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get()
        val type = buffer.get()
        if (version != VERSION || type != TYPE_REQUEST) {
            return null
        }
        return SessionClockSyncBinaryRequest(clientSendElapsedNanos = buffer.long)
    }

    fun decodeResponse(payload: ByteArray): SessionClockSyncBinaryResponse? {
        if (payload.size != RESPONSE_SIZE_BYTES) {
            return null
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get()
        val type = buffer.get()
        if (version != VERSION || type != TYPE_RESPONSE) {
            return null
        }
        return SessionClockSyncBinaryResponse(
            clientSendElapsedNanos = buffer.long,
            hostReceiveElapsedNanos = buffer.long,
            hostSendElapsedNanos = buffer.long,
        )
    }
}
