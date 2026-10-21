package com.opendroid.ai.core.service

import android.app.Notification
import android.service.notification.StatusBarNotification

/**
 * Keeps incoming notification alerts deterministic and independent of LLM latency.
 *
 * A normal message is queued silently by default. Only notifications that match an
 * explicit user preference or an Android high-priority signal may interrupt voice
 * interaction. This policy does not trigger auto-replies or execute actions.
 */
internal object NotificationInterruptionPolicy {

    enum class Decision {
        IGNORE,
        QUEUE,
        INTERRUPT
    }

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

        val hasUserPriority = notification.packageName in settings.importantPackages
        val isHighPriority = settings.interruptForHighPriority &&
            data.priority >= Notification.PRIORITY_HIGH
        if (hasUserPriority || isHighPriority) return Decision.INTERRUPT

        // A notification may be surfaced in the UI later, but should not interrupt
        // an active command or spoken response by default.
        return if (assistantIsBusy) Decision.QUEUE else Decision.QUEUE
    }
}
