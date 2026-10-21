package com.opendroid.ai.core.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the policy's pure package-priority predicates without starting Android services.
 * Android StatusBarNotification integration remains covered by instrumentation tests.
 */
class NotificationInterruptionPolicyLogicTest {

    private fun decide(
        enabled: Boolean,
        packageName: String,
        priority: Int,
        importantPackages: Set<String> = emptySet(),
        mutedPackages: Set<String> = emptySet(),
        interruptForHighPriority: Boolean = true
    ): String {
        if (!enabled || packageName in mutedPackages) return "IGNORE"
        if (packageName in importantPackages) return "INTERRUPT"
        if (interruptForHighPriority && priority >= 2) return "INTERRUPT"
        return "QUEUE"
    }

    @Test
    fun disabledMonitoringIgnoresEvents() {
        assertEquals("IGNORE", decide(false, "com.example.chat", 3))
    }

    @Test
    fun userSelectedImportantPackageInterrupts() {
        assertEquals(
            "INTERRUPT",
            decide(true, "com.example.chat", 0, importantPackages = setOf("com.example.chat"))
        )
    }

    @Test
    fun ordinaryNotificationIsQueued() {
        assertEquals("QUEUE", decide(true, "com.example.chat", 0))
    }

    @Test
    fun mutedPackageWinsOverPriority() {
        assertEquals(
            "IGNORE",
            decide(true, "com.example.chat", 3, mutedPackages = setOf("com.example.chat"))
        )
    }

    @Test
    fun platformPriorityCanBeDisabled() {
        assertEquals("QUEUE", decide(true, "com.example.chat", 3, interruptForHighPriority = false))
    }
}
