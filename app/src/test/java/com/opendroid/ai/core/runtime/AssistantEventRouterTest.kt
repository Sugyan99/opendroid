package com.opendroid.ai.core.runtime

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantEventRouterTest {

    @Test
    fun publishDeliversEventToActiveCollector() = runBlocking {
        val router = AssistantEventRouter()
        val received = CompletableDeferred<AssistantEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            router.events.collect { event -> received.complete(event) }
        }
        val event = AssistantEvent.WakeWordDetected(
            eventId = "wake:1",
            timestampMs = 1L
        )

        assertTrue(router.publish(event))
        assertEquals(event, withTimeout(1_000) { received.await() })
        collector.cancel()
    }

    @Test
    fun duplicateEventIdIsRejected() {
        val router = AssistantEventRouter()
        val event = AssistantEvent.NotificationReceived(
            eventId = "notification:key:1",
            timestampMs = 123L,
            packageName = "com.example.chat",
            title = "Friend",
            category = "MESSAGE",
            priority = 0
        )

        assertTrue(router.publish(event))
        assertFalse(router.publish(event))
    }

    @Test
    fun sensorEventsCarryTypedSampleValues() {
        val event = AssistantEvent.SensorSample(
            eventId = "sensor:accelerometer:1000",
            timestampMs = 1_000L,
            sensorType = 1,
            values = listOf(0.1f, 0.2f, 9.8f)
        )

        assertEquals(3, event.values.size)
        assertEquals(1_000L, event.timestampMs)
    }
}
