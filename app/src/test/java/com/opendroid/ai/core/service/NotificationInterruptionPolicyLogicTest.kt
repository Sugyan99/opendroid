package com.opendroid.ai.core.service

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationInterruptionPolicyLogicTest {

    @Test
    fun disabledMonitoringIgnoresEvents() {
        assertEquals(
            NotificationInterruptionPolicy.Decision.IGNORE,
            NotificationInterruptionPolicy.evaluate(
                NotificationInterruptionPolicy.Input(enabled = false, packageName = "com.example.chat")
            )
        )
    }

    @Test
    fun userSelectedImportantPackageInterrupts() {
        assertEquals(
            NotificationInterruptionPolicy.Decision.INTERRUPT,
            NotificationInterruptionPolicy.evaluate(
                NotificationInterruptionPolicy.Input(
                    packageName = "com.example.chat",
                    importantPackages = setOf("com.example.chat")
                )
            )
        )
    }

    @Test
    fun ordinaryNotificationIsQueued() {
        assertEquals(
            NotificationInterruptionPolicy.Decision.QUEUE,
            NotificationInterruptionPolicy.evaluate(
                NotificationInterruptionPolicy.Input(packageName = "com.example.chat")
            )
        )
    }

    @Test
    fun mutedPackageWinsOverPriority() {
        assertEquals(
            NotificationInterruptionPolicy.Decision.IGNORE,
            NotificationInterruptionPolicy.evaluate(
                NotificationInterruptionPolicy.Input(
                    packageName = "com.example.chat",
                    priority = Notification.PRIORITY_MAX,
                    mutedPackages = setOf("com.example.chat")
                )
            )
        )
    }

    @Test
    fun platformPriorityCanBeDisabled() {
        assertEquals(
            NotificationInterruptionPolicy.Decision.QUEUE,
            NotificationInterruptionPolicy.evaluate(
                NotificationInterruptionPolicy.Input(
                    packageName = "com.example.chat",
                    priority = Notification.PRIORITY_MAX,
                    interruptForHighPriority = false
                )
            )
        )
    }

    @Test
    fun ongoingNotificationsAreIgnored() {
        assertEquals(
            NotificationInterruptionPolicy.Decision.IGNORE,
            NotificationInterruptionPolicy.evaluate(
                NotificationInterruptionPolicy.Input(
                    packageName = "com.example.chat",
                    flags = Notification.FLAG_ONGOING_EVENT
                )
            )
        )
    }
}
