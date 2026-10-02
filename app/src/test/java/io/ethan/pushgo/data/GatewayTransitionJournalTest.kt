package io.ethan.pushgo.data

import android.content.SharedPreferences
import io.ethan.pushgo.data.db.AppSettingsDao
import io.ethan.pushgo.data.db.AppSettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayTransitionJournalTest {
    @Test
    fun interruptedWriteRestoresCompletePreviousGatewayAfterRepositoryRecreation() = runBlocking {
        val dao = MemorySettingsDao()
        val protectedValues = MemorySecretStore()
        val preferences = MemoryPreferences()
        val first = SettingsRepository(dao, protectedValues, preferences)
        first.setServerAddress("https://old.example")
        first.setGatewayToken("old-access")
        first.setFcmToken("old-provider")
        first.setDeviceKey("old-device")
        first.setGatewayAckToken("https://new.example", "previous-new-ack")
        val expected = GatewayTransitionJournal(
            previous = GatewayTransitionSnapshot(
                address = "https://old.example",
                gatewayToken = "old-access",
                fcmToken = "old-provider",
                deviceKey = "old-device",
                candidateAckToken = null,
            ),
            candidate = GatewayTransitionSnapshot(
                address = "https://new.example",
                gatewayToken = "new-access",
                fcmToken = "new-provider",
                deviceKey = "new-device",
                candidateAckToken = "previous-new-ack",
            ),
            stage = GatewayTransitionStage.GATEWAY_TOKEN_WRITTEN,
            fcmCleanupPending = true,
            privateCleanupPending = true,
        )

        first.beginGatewayTransition(expected)
        // Model process death after only address and gateway credential changed.
        first.setServerAddress("https://new.example")
        first.setGatewayToken("new-access")

        val recreated = SettingsRepository(dao, protectedValues, preferences)
        assertEquals(expected, recreated.getGatewayTransitionJournal())
        val onDiskMetadata = preferences.getString("gateway_transition_metadata", "").orEmpty()
        assertFalse(onDiskMetadata.contains("old-access"))
        assertFalse(onDiskMetadata.contains("new-access"))
        assertEquals(
            GatewayTransitionStartupRecovery.ROLLED_BACK,
            recreated.recoverGatewayTransitionAtStartup(),
        )
        assertEquals("https://old.example", recreated.getServerAddress())
        assertEquals("old-access", recreated.getGatewayToken())
        assertEquals("old-provider", recreated.getFcmToken())
        assertEquals("old-device", recreated.getDeviceKey())
        assertEquals("previous-new-ack", recreated.getGatewayAckToken("https://new.example"))
        assertNull(recreated.getGatewayTransitionJournal())
    }

    @Test
    fun committedTransitionRemainsAuthoritativeAtStartupAndStaysPendingForReconciliation() = runBlocking {
        val dao = MemorySettingsDao()
        val protectedValues = MemorySecretStore()
        val preferences = MemoryPreferences()
        val first = SettingsRepository(dao, protectedValues, preferences)
        first.setServerAddress("https://new.example")
        first.setGatewayToken("new-access")
        first.setFcmToken("new-provider")
        first.setDeviceKey("new-device")
        first.beginGatewayTransition(
            GatewayTransitionJournal(
                previous = GatewayTransitionSnapshot(
                    address = "https://old.example",
                    gatewayToken = "old-access",
                    fcmToken = "old-provider",
                    deviceKey = "old-device",
                    candidateAckToken = null,
                ),
                candidate = GatewayTransitionSnapshot(
                    address = "https://new.example",
                    gatewayToken = "new-access",
                    fcmToken = "new-provider",
                    deviceKey = "new-device",
                    candidateAckToken = null,
                ),
                stage = GatewayTransitionStage.COMMITTED,
                fcmCleanupPending = true,
                privateCleanupPending = false,
                previousChannelType = "fcm",
            ),
        )

        val recreated = SettingsRepository(dao, protectedValues, preferences)
        assertEquals(
            GatewayTransitionStartupRecovery.COMMITTED,
            recreated.recoverGatewayTransitionAtStartup(),
        )
        assertEquals("https://new.example", recreated.getServerAddress())
        assertEquals("new-access", recreated.getGatewayToken())
        assertEquals("new-provider", recreated.getFcmToken())
        assertEquals("new-device", recreated.getDeviceKey())
        assertEquals(true, recreated.getGatewayRecoveryPending())
        assertEquals(GatewayTransitionStage.COMMITTED, recreated.getGatewayTransitionJournal()?.stage)
    }

    @Test
    fun routeCompletionPersistsIndependentlyAndClearingRemovesRecoveryRecord() {
        val dao = MemorySettingsDao()
        val protectedValues = MemorySecretStore()
        val preferences = MemoryPreferences()
        val repository = SettingsRepository(dao, protectedValues, preferences)
        repository.beginGatewayTransition(
            GatewayTransitionJournal(
                previous = GatewayTransitionSnapshot("https://old.example", null, null, "old-device", null),
                candidate = GatewayTransitionSnapshot("https://new.example", null, null, "new-device", null),
                stage = GatewayTransitionStage.COMMITTED,
                fcmCleanupPending = true,
                privateCleanupPending = true,
            ),
        )

        repository.markGatewayTransitionRouteCleanup("fcm", false)

        val afterFcm = repository.getGatewayTransitionJournal()
        assertEquals(false, afterFcm?.fcmCleanupPending)
        assertEquals(true, afterFcm?.privateCleanupPending)
        repository.clearGatewayTransitionJournal()
        assertNull(repository.getGatewayTransitionJournal())
        assertNull(protectedValues.pendingTransportToken("gateway-transition-journal-v1"))
    }

    @Test
    fun routeCleanupMismatchMovesBothPendingFlagsInOnePersistedSnapshot() {
        val dao = MemorySettingsDao()
        val protectedValues = MemorySecretStore()
        val preferences = MemoryPreferences()
        val repository = SettingsRepository(dao, protectedValues, preferences)
        repository.beginGatewayTransition(
            GatewayTransitionJournal(
                previous = GatewayTransitionSnapshot("https://old.example", null, null, "old-device", null),
                candidate = GatewayTransitionSnapshot("https://new.example", null, null, "new-device", null),
                stage = GatewayTransitionStage.COMMITTED,
                fcmCleanupPending = true,
                privateCleanupPending = false,
            ),
        )

        val commitsBefore = preferences.commitCount
        repository.markGatewayTransitionRouteCleanup(
            fcmPending = false,
            privatePending = true,
        )

        assertEquals(commitsBefore + 1, preferences.commitCount)
        val recreated = SettingsRepository(dao, protectedValues, preferences)
        assertEquals(false, recreated.getGatewayTransitionJournal()?.fcmCleanupPending)
        assertEquals(true, recreated.getGatewayTransitionJournal()?.privateCleanupPending)
    }

    @Test
    fun automationResetRemovesTransitionMetadataBeforeProtectedStoreReset() = runBlocking {
        val dao = MemorySettingsDao()
        val protectedValues = MemorySecretStore()
        val preferences = MemoryPreferences()
        val repository = SettingsRepository(dao, protectedValues, preferences)
        repository.beginGatewayTransition(
            GatewayTransitionJournal(
                previous = GatewayTransitionSnapshot("https://old.example", "old", "fcm", "device", null),
                candidate = GatewayTransitionSnapshot("https://new.example", "new", "fcm-new", "device-new", null),
                stage = GatewayTransitionStage.ADDRESS_WRITTEN,
                fcmCleanupPending = true,
                privateCleanupPending = false,
                previousChannelType = "fcm",
            ),
        )
        repository.setGatewayRecoveryPending(true)

        repository.resetForAutomation("https://default.example")

        assertNull(repository.getGatewayTransitionJournal())
        assertNull(protectedValues.pendingTransportToken("gateway-transition-journal-v1"))
        assertEquals(false, repository.getGatewayRecoveryPending())
    }

    @Test
    fun cleanupTreatsMissingDeviceAndRouteAsAlreadyGoneButRetainsTransientFailures() {
        listOf("device_key_not_found", "device_not_found", "route_not_found").forEach { code ->
            assertEquals(
                GatewayRouteRetirement.ALREADY_GONE,
                classifyGatewayRouteRetirement(
                    ChannelSubscriptionException.local(
                        message = "route is already absent",
                        code = code,
                        category = GatewayErrorCategory.NOT_FOUND,
                    ),
                ),
            )
        }
        assertEquals(
            GatewayRouteRetirement.CHANNEL_TYPE_MISMATCH,
            classifyGatewayRouteRetirement(
                ChannelSubscriptionException.local(
                    message = "route belongs to another transport",
                    code = "channel_type_mismatch",
                    category = GatewayErrorCategory.CONFLICT,
                ),
            ),
        )
        assertEquals(
            GatewayRouteRetirement.RETRY,
            classifyGatewayRouteRetirement(
                ChannelSubscriptionException.local(
                    message = "temporary gateway outage",
                    code = "gateway_unavailable",
                    category = GatewayErrorCategory.NETWORK,
                ),
            ),
        )
    }

    @Test
    fun channelTypeMismatchCarriesCleanupObligationToAlternateTransport() {
        assertEquals(
            false to true,
            advanceGatewayRouteCleanupFlags(
                fcmPending = true,
                privatePending = false,
                route = "fcm",
                result = GatewayRouteRetirement.CHANNEL_TYPE_MISMATCH,
            ),
        )
        assertEquals(
            true to false,
            advanceGatewayRouteCleanupFlags(
                fcmPending = true,
                privatePending = true,
                route = "private",
                result = GatewayRouteRetirement.CHANNEL_TYPE_MISMATCH,
            ),
        )
        assertEquals(
            true to true,
            advanceGatewayRouteCleanupFlags(
                fcmPending = true,
                privatePending = true,
                route = "fcm",
                result = GatewayRouteRetirement.RETRY,
            ),
        )
    }

    private class MemorySettingsDao : AppSettingsDao {
        private val state = MutableStateFlow<AppSettingsEntity?>(null)

        override fun observe(): Flow<AppSettingsEntity?> = state

        override suspend fun get(): AppSettingsEntity? = state.value

        override suspend fun upsert(entity: AppSettingsEntity) {
            state.value = entity
        }

        override suspend fun deleteAll() {
            state.value = null
        }
    }

    private class MemorySecretStore : SecureSecretStore {
        private val values = mutableMapOf<String, String?>()

        override fun gatewayToken(): String? = values["gateway"]
        override fun setGatewayToken(token: String?) = set("gateway", token)
        override fun gatewayAckToken(gatewayUrl: String): String? = values["ack:$gatewayUrl"]
        override fun setGatewayAckToken(gatewayUrl: String, token: String?) = set("ack:$gatewayUrl", token)
        override fun fcmToken(): String? = values["fcm"]
        override fun setFcmToken(token: String?) = set("fcm", token)
        override fun pendingTransportToken(operationId: String): String? = values["pending:$operationId"]
        override fun setPendingTransportToken(operationId: String, token: String?) = set("pending:$operationId", token)
        override fun deviceKey(): String? = values["device"]
        override fun setDeviceKey(deviceKey: String?) = set("device", deviceKey)
        override fun notificationKeyBytes(): ByteArray? = null
        override fun setNotificationKeyBytes(value: ByteArray?) = Unit
        override fun channelPassword(gatewayUrl: String, channelId: String): String? = null
        override fun setChannelPassword(gatewayUrl: String, channelId: String, password: String?) = Unit
        override fun removeChannelPassword(gatewayUrl: String, channelId: String) = Unit
        override fun clearAll() = values.clear()

        private fun set(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        var commitCount: Int = 0

        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = MemoryEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        private inner class MemoryEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearRequested = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = put(key, value)
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = put(key, values)
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = put(key, value)
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = put(key, value)
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = put(key, value)
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = put(key, value)
            override fun remove(key: String): SharedPreferences.Editor = put(key, null)
            override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }
            override fun commit(): Boolean {
                commitCount += 1
                if (clearRequested) values.clear()
                pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                return true
            }
            override fun apply() { commit() }

            private fun put(key: String, value: Any?): SharedPreferences.Editor = apply {
                pending[key] = value
            }
        }
    }
}
