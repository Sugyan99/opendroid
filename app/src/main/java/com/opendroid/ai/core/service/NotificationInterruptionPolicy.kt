package com.opendroid.ai.core.service

import android.app.Notification

/**
 * Deterministic notification interruption rules. Normal notifications are queued;
 * only explicitly important packages or high-priority notification events interrupt.
 *
 * Android's legacy priority is used as a signal, not as proof that a message is urgent.
 */
internal object NotificationInterruptionPolicy {

    enum class Decision { IGNORE, QUEUE, INTERRUPT }

    data class Input(
        val enabled: Boolean = true,
        val packageName: String,
        val priority: Int = Notification.PRIORITY_DEFAULT,
        val flags: Int = 0,
        val importantPackages: Set<String> = emptySet(),
        val mutedPackages: Set<String> = emptySet(),
        val interruptForHighPriority: Boolean = true
    )

    fun evaluate(input: Input): Decision {
        if (!input.enabled || input.packageName in input.mutedPackages) return Decision.IGNORE
        if (input.flags and Notification.FLAG_ONGOING_EVENT != 0) return Decision.IGNORE
        if (input.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return Decision.IGNORE

        if (input.packageName in input.importantPackages) return Decision.INTERRUPT
        if (input.interruptForHighPriority && input.priority >= Notification.PRIORITY_HIGH) {
            return Decision.INTERRUPT
        }
        return Decision.QUEUE
    }
}
