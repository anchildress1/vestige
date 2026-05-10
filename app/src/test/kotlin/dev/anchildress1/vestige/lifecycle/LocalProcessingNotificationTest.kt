package dev.anchildress1.vestige.lifecycle

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.anchildress1.vestige.R
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// JVM unit tests run against android.jar stubs that drop NotificationChannel constructor args, so
// these tests pin the contract on constants + verify the call shape; on-device behavior is the
// manual check in the story.
class LocalProcessingNotificationTest {

    @Test
    fun `channel id and importance match the ADR-004 contract`() {
        assertEquals("vestige.local_processing", LocalProcessingNotification.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, LocalProcessingNotification.IMPORTANCE)
    }

    @Test
    fun `registerChannel forwards a non-null channel to NotificationManager`() {
        val manager = mockk<NotificationManager>(relaxed = true)
        val context = stubContext(manager)

        LocalProcessingNotification.registerChannel(context)

        verify(exactly = 1) { manager.createNotificationChannel(any<NotificationChannel>()) }
    }

    @Test
    fun `registerChannel is idempotent across cold starts`() {
        val manager = mockk<NotificationManager>(relaxed = true)
        val context = stubContext(manager)

        LocalProcessingNotification.registerChannel(context)
        LocalProcessingNotification.registerChannel(context)

        verify(exactly = 2) { manager.createNotificationChannel(any<NotificationChannel>()) }
    }

    @Test
    fun `registerChannel is a no-op when NotificationManager is unavailable`() {
        val context = mockk<Context>(relaxed = true) {
            every { getSystemService(Context.NOTIFICATION_SERVICE) } returns null
            every { getSystemService(NotificationManager::class.java) } returns null
        }

        LocalProcessingNotification.registerChannel(context)
        // No throw, no manager interaction — verifies the early-return guard.
    }

    private fun stubContext(manager: NotificationManager): Context = mockk(relaxed = true) {
        every { getSystemService(Context.NOTIFICATION_SERVICE) } returns manager
        every { getSystemService(NotificationManager::class.java) } returns manager
        every { getString(R.string.notification_channel_local_processing_name) } returns "Local processing"
        every { getString(R.string.notification_channel_local_processing_description) } returns
            "Status notification while Vestige reads an entry locally."
    }
}
