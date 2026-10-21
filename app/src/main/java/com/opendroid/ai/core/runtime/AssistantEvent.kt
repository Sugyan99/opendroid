package com.opendroid.ai.core.runtime

/**
 * Small, privacy-conscious event envelope for the always-on assistant.
 * Notification bodies and raw audio are intentionally excluded from this bus.
 */
sealed interface AssistantEvent {
    val eventId: String
    val timestampMs: Long

    data class NotificationReceived(
        override val eventId: String,
        override val timestampMs: Long,
        val packageName: String,
        val title: String,
        val category: String,
        val priority: Int
    ) : AssistantEvent

    data class WakeWordDetected(
        override val eventId: String,
        override val timestampMs: Long
    ) : AssistantEvent

    data class UserCommandReceived(
        override val eventId: String,
        override val timestampMs: Long,
        val command: String
    ) : AssistantEvent

    /**
     * A sensor event is emitted only by a monitor the user explicitly enabled.
     * Source adapters should down-sample continuous sensors before publishing.
     */
    data class SensorSample(
        override val eventId: String,
        override val timestampMs: Long,
        val sensorType: Int,
        val values: List<Float>
    ) : AssistantEvent
}
