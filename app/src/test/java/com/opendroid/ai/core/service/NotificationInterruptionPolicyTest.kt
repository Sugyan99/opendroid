package com.opendroid.ai.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

class NotificationInterruptionPolicyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun notification(
        packageName: String = "com.example.chat",
        priority: Int = Notification.PRIORITY_DEFAULT,
        flags: Int = 0
    ): StatusBarNotification {
        val channelId = "policy-test"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Policy test", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val builder = Notification.Builder(context, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) channelId else "")
            .setContentTitle("Test message")
            .setContentText("Hello")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(priority)
        val built = builder.build().apply { this.flags = flags }
        return Robolectric.buildService(StubNotificationListener::class.java).get()
            .let {
                StatusBarNotification(
                    packageName,
                    packageName,
                    1,
                    null,
                    android.os.Process.myUid(),
                    0,
                    built,
                    android.os.UserHandle.getUserHandleForUid(android.os.Process.myUid()),
                    null,
                    System.currentTimeMillis()
                )
            }
    }

    @Test
    fun disabledPolicyIgnoresNotification() {
        assertEquals(
            NotificationInterruptionPolicy.Decision.IGNORE,
            NotificationInterruptionPolicy.evaluate(
                notification(),
                NotificationInterruptionPolicy.Settings(enabled = false),
                assistantIsBusy = true
            )
        )
    }

    @Test
    fun explicitlyImportantPackageInterrupts() {
        assertEquals(
            NotificationInterruptionPolicy.Decision.INTERRUPT,
            NotificationInterruptionPolicy.evaluate(
                notification(),
                NotificationInterruptionPolicy.Settings(importantPackages = setOf("com.example.chat")),
                assistantIsBusy = true
            )
        )
    }

    @Test
    fun normalMessageIsQueuedWithoutInterrupting() {
        assertEquals(
            NotificationInterruptionPolicy.Decision.QUEUE,
            NotificationInterruptionPolicy.evaluate(
                notification(),
                NotificationInterruptionPolicy.Settings(),
                assistantIsBusy = true
            )
        )
    }

    @Test
    fun mutedPackageIsIgnoredEvenWhenHighPriority() {
        assertEquals(
            NotificationInterruptionPolicy.Decision.IGNORE,
            NotificationInterruptionPolicy.evaluate(
                notification(priority = Notification.PRIORITY_MAX),
                NotificationInterruptionPolicy.Settings(mutedPackages = setOf("com.example.chat")),
                assistantIsBusy = true
            )
        )
    }

    @Test
    fun ongoingNotificationIsIgnored() {
        assertEquals(
            NotificationInterruptionPolicy.Decision.IGNORE,
            NotificationInterruptionPolicy.evaluate(
                notification(flags = Notification.FLAG_ONGOING_EVENT),
                NotificationInterruptionPolicy.Settings(),
                assistantIsBusy = false
            )
        )
    }

    /** Only used to provide a Robolectric Android service context for notification construction. */
    class StubNotificationListener : android.service.notification.NotificationListenerService()
}
