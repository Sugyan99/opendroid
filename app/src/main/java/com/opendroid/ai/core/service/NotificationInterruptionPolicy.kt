package com.opendroid.ai.core.service

import android.app.Notification
import android.service.notification.StatusBarNotification

/**
 * Deterministic notification interruption rules. Normal notifications are queued;
 * only explicitly important packages or Android high-priority notifications interrupt.
 */
internal object NotificationInterruptionPolicy {

    enum class Decision { IGNORE, QUEUE, INTERRUPT }

    data class Settings(
        val enabled: Boolean = true,
        val interruptForHighPriority: Boolean = true,
        val importantPackages: Set<String> = emptySet(),
        val mutedPackages: Set<String> = emptySet()
    )

    fun evaluate(
        notification: StatusBarNotification,
        settings: Settings,
        assistantIsBusy: Boolean
    ): Decision {
        if (!settings.enabled) return Decision.IGNORE
        if (notification.packageName in settings.mutedPackages) return Decision.IGNORE

        val data = notification.notification ?: return Decision.IGNORE
        if (data.flags and Notification.FLAG_ONGOING_EVENT != 0) return Decision.IGNORE
        if (data.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return Decision.IGNORE

        val explicitlyImportant = notification.packageName in settings.importantPackages
        val platformHighPriority = settings.interruptForHighPriority &&
            data.priority >= Notification.PRIORITY_HIGH
        if (explicitlyImportant || platformHighPriority) return Decision.INTERRUPT

        // Deliberately do not interrupt for ordinary notifications, even when idle.
        // The caller may later surface them in a digest or assistant inbox.
        @Suppress("UNUSED_VARIABLE")
        val currentSessionIsBusy = assistantIsBusy
        return Decision.QUEUE
    }
}
