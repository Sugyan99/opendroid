package com.opendroid.ai.core.runtime

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Redacted in-memory snapshot updated from live events.
 *
 * Raw voice commands, notification bodies, and audio are never retained here.
 * Selected sensor samples are capped by sensor type and are not persisted to disk.
 */
@Singleton
class AssistantEventProcessor @Inject constructor() {

    data class Snapshot(
        val totalEvents: Long = 0,
        val notificationEvents: Long = 0,
        val wakeWordEvents: Long = 0,
        val voiceCommandEvents: Long = 0,
        val sensorEvents: Long = 0,
        val lastEventKind: String? = null,
        val lastEventAtMs: Long? = null,
        val lastNotificationPackage: String? = null,
        val latestSensorValues: Map<Int, List<Float>> = emptyMap()
    )

    private val mutableSnapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = mutableSnapshot.asStateFlow()

    fun process(event: AssistantEvent) {
        mutableSnapshot.update { previous ->
            val common = previous.copy(
                totalEvents = previous.totalEvents + 1,
                lastEventAtMs = event.timestampMs
            )
            when (event) {
                is AssistantEvent.NotificationReceived -> common.copy(
                    notificationEvents = previous.notificationEvents + 1,
                    lastEventKind = "notification",
                    lastNotificationPackage = event.packageName
                )
                is AssistantEvent.WakeWordDetected -> common.copy(
                    wakeWordEvents = previous.wakeWordEvents + 1,
                    lastEventKind = "wake_word"
                )
                is AssistantEvent.UserCommandReceived -> common.copy(
                    voiceCommandEvents = previous.voiceCommandEvents + 1,
                    lastEventKind = "voice_command"
                )
                is AssistantEvent.SensorSample -> common.copy(
                    sensorEvents = previous.sensorEvents + 1,
                    lastEventKind = "sensor",
                    latestSensorValues = (previous.latestSensorValues +
                        (event.sensorType to event.values.toList()))
                        .entries
                        .toList()
                        .takeLast(MAX_TRACKED_SENSOR_TYPES)
                        .associate { entry -> entry.key to entry.value }
                )
            }
        }
    }

    companion object {
        private const val MAX_TRACKED_SENSOR_TYPES = 12
    }
}
