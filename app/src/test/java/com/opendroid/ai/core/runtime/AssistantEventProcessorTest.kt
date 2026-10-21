package com.opendroid.ai.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AssistantEventProcessorTest {

    @Test
    fun countsEventsWithoutRetainingCommandOrNotificationBody() {
        val processor = AssistantEventProcessor()
        processor.process(
            AssistantEvent.NotificationReceived(
                eventId = "notification:1",
                timestampMs = 10L,
                packageName = "com.example.chat",
                title = "Friend",
                category = "MESSAGE",
                priority = 0
            )
        )
        processor.process(
            AssistantEvent.UserCommandReceived(
                eventId = "command:2",
                timestampMs = 20L,
                command = "secret voice command"
            )
        )

        val snapshot = processor.snapshot.value
        assertEquals(2L, snapshot.totalEvents)
        assertEquals(1L, snapshot.notificationEvents)
        assertEquals(1L, snapshot.voiceCommandEvents)
        assertEquals("com.example.chat", snapshot.lastNotificationPackage)
        assertFalse(snapshot.toString().contains("secret voice command"))
    }

    @Test
    fun storesLatestSensorSampleInMemory() {
        val processor = AssistantEventProcessor()
        processor.process(
            AssistantEvent.SensorSample(
                eventId = "sensor:1:1",
                timestampMs = 1000L,
                sensorType = 1,
                values = listOf(0.0f, 0.0f, 9.8f)
            )
        )

        assertEquals(1L, processor.snapshot.value.sensorEvents)
        assertEquals(listOf(0.0f, 0.0f, 9.8f), processor.snapshot.value.latestSensorValues[1])
    }
}
