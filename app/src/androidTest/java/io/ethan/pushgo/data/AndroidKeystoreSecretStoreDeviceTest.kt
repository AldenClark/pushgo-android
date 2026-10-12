package io.ethan.pushgo.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSecretStoreDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanup() {
        context.deleteSharedPreferences(PREFERENCE_FILE)
    }

    @Test
    fun reopeningRemovesObsoleteRouteTokensAndPreservesGatewayJournalAndCredentials() {
        context.deleteSharedPreferences(PREFERENCE_FILE)
        val store = AndroidKeystoreSecretStore(context, PREFERENCE_FILE)
        store.setGatewayToken("test-gateway-credential")
        store.setGatewayAckToken(GATEWAY, "test-ack-credential")
        store.setFcmToken("test-provider-token")
        store.setDeviceKey("test-device")
        store.setNotificationKeyBytes(byteArrayOf(1, 2, 3, 4))
        store.setChannelPassword(GATEWAY, "test-channel", "test-channel-password")
        store.setGatewayTransitionJournal("test-gateway-address-journal")

        val prefs = context.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)
        val journalKey = "pending_transport_token_" +
            MessageDigest.getInstance("SHA-256")
                .digest("gateway-transition-journal-v1".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        assertTrue("Existing gateway journal key is retained", prefs.contains(journalKey))
        assertTrue(
            prefs.edit().putString("pending_transport_token_obsolete-operation", "obsolete-ciphertext")
                .commit()
        )

        val reopened = AndroidKeystoreSecretStore(context, PREFERENCE_FILE)
        assertFalse(prefs.contains("pending_transport_token_obsolete-operation"))
        assertEquals("test-gateway-address-journal", reopened.gatewayTransitionJournal())
        assertEquals("test-gateway-credential", reopened.gatewayToken())
        assertEquals("test-ack-credential", reopened.gatewayAckToken(GATEWAY))
        assertEquals("test-provider-token", reopened.fcmToken())
        assertEquals("test-device", reopened.deviceKey())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), reopened.notificationKeyBytes())
        assertEquals("test-channel-password", reopened.channelPassword(GATEWAY, "test-channel"))
    }

    companion object {
        private const val PREFERENCE_FILE = "quality_route_token_cleanup_test"
        private const val GATEWAY = "https://quality-gateway.example"
    }
}
