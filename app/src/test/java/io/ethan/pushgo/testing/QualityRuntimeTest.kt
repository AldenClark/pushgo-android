package io.ethan.pushgo.testing

import io.ethan.pushgo.data.AndroidKeystoreSecretStore
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking

class QualityRuntimeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @After
    fun tearDown() {
        QualityRuntime.resetForTesting()
    }

    @Test
    fun typedSessionRoundTripsAndSelectsANonProductionDatabase() {
        val session = QualitySessionDescriptor(
            schemaVersion = 1,
            sessionId = "android-pr-123_retry-1",
            fixture = QualityFixture.MESSAGES_STANDARD,
            faults = QualityFaults(
                failLocalStoreInitialization = true,
                messageLoadDelayMs = 250,
                messagePageLoadDelayMs = 1_500,
                messageRefreshDelayMs = 2_500,
                messageRefreshPresentationDelayMs = 2_750,
                messageSearchDelayMs = 2_000,
                failMessagePageLoadOnce = true,
                failMessageSearchOnce = true,
                failGatewaySwitchValidationOnce = true,
                failGatewaySwitchCommitOnce = true,
                failGatewayPostCommitSyncOnce = true,
                failNotificationKeyPersistenceOnce = true,
                failChannelSubscriptionPersistenceOnce = true,
                failTransportSelectionPersistenceOnce = true,
            ),
            messageRefreshScenario = QualityMessageRefreshScenario.FAIL_ONCE_THEN_NEW_MESSAGE,
            eventCloseScenario = QualityEventCloseScenario.FAIL_ONCE_THEN_ACCEPTED_AND_DELIVERED,
            channelMutationScenario = QualityChannelMutationScenario.ACCEPTED,
            expectedChannelMutationGatewayUrl = "https://quality-settings.invalid/api",
            transportSwitchScenario = QualityTransportSwitchScenario.REJECT_ONCE_THEN_ACCEPTED,
            updateScenario = QualityUpdateScenario.AVAILABLE_STABLE,
            updateArtifact = QualityUpdateArtifact(
                versionCode = 1_030_199,
                versionName = "v1.3.1",
                apkUrl = "http://127.0.0.1:48123/update.apk",
                apkSha256 = "ab".repeat(32),
            ),
            systemCapabilities = setOf(
                QualitySystemCapability.PRIVATE_FOREGROUND_SERVICE,
                QualitySystemCapability.NOTIFICATION_PERMISSION_JOURNEY,
                QualitySystemCapability.DOZE_REMINDER_JOURNEY,
            ),
        )

        val decoded = QualityRuntime.decode(QualityRuntime.encode(session))

        assertEquals(session, decoded)
        assertTrue(decoded.databaseName.startsWith("pushgo-quality-"))
        assertTrue(decoded.databaseName != "pushgo.db")
        assertTrue(decoded.securePreferencesName.startsWith("pushgo-quality-"))
        assertTrue(decoded.securePreferencesName != AndroidKeystoreSecretStore.PRODUCTION_PREFERENCE_FILE)
        assertTrue(decoded.settingsCachePreferencesName.startsWith("pushgo-quality-"))
        assertTrue(decoded.securePreferencesName != decoded.settingsCachePreferencesName)
        assertEquals(
            QualityEventCloseScenario.FAIL_ONCE_THEN_ACCEPTED_AND_DELIVERED,
            decoded.eventCloseScenario,
        )
        assertEquals(QualityChannelMutationScenario.ACCEPTED, decoded.channelMutationScenario)
        assertEquals(
            "https://quality-settings.invalid/api",
            decoded.expectedChannelMutationGatewayUrl,
        )
        assertEquals(
            QualityTransportSwitchScenario.REJECT_ONCE_THEN_ACCEPTED,
            decoded.transportSwitchScenario,
        )
        assertEquals(QualityUpdateScenario.AVAILABLE_STABLE, decoded.updateScenario)
        assertEquals(1_030_199, decoded.updateArtifact?.versionCode)
        assertEquals(
            setOf(
                QualitySystemCapability.PRIVATE_FOREGROUND_SERVICE,
                QualitySystemCapability.NOTIFICATION_PERMISSION_JOURNEY,
                QualitySystemCapability.DOZE_REMINDER_JOURNEY,
            ),
            decoded.systemCapabilities,
        )
    }

    @Test
    fun messagePageFailureIsConsumedOnceSoTheUserCanRetry() {
        val session = QualitySessionDescriptor(
            schemaVersion = 1,
            sessionId = "message-page-retry",
            fixture = QualityFixture.MESSAGES_WORKFLOW,
            faults = QualityFaults(failMessagePageLoadOnce = true),
        )
        QualityRuntime.configure(QualityRuntime.encode(session))

        assertThrows(QualityMessagePageLoadException::class.java) {
            runBlocking { QualityRuntime.beforeMessagePageLoad() }
        }
        runBlocking { QualityRuntime.beforeMessagePageLoad() }
    }

    @Test
    fun globalErrorPresentationLedgerIsSessionScopedAndNotProductionState() {
        assertEquals(0, QualityRuntime.globalErrorPresentationCount())

        val session = QualitySessionDescriptor(
            schemaVersion = 1,
            sessionId = "host-error-ledger",
            fixture = QualityFixture.EMPTY_CLEAN,
            faults = QualityFaults(),
        )
        QualityRuntime.configure(QualityRuntime.encode(session))
        QualityRuntime.recordGlobalErrorPresentation()
        QualityRuntime.recordGlobalErrorPresentation()
        assertEquals(2, QualityRuntime.globalErrorPresentationCount())

        QualityRuntime.configure(null)
        QualityRuntime.recordGlobalErrorPresentation()
        assertEquals(0, QualityRuntime.globalErrorPresentationCount())
    }

    @Test
    fun unknownSystemCapabilityIsRejectedInsteadOfSilentlyBroadeningTheSession() {
        val encoded = encodeJson(
            JSONObject()
                .put("schema_version", 1)
                .put("session_id", "unknown-system-capability")
                .put("fixture", "empty.clean")
                .put("system_capabilities", org.json.JSONArray().put("unknown_system_surface"))
        )

        assertThrows(IllegalArgumentException::class.java) {
            QualityRuntime.decode(encoded)
        }
    }

    @Test
    fun updateArtifactRejectsUntrustedPlaintextAndInvalidDigest() {
        val plaintextRemote = encodeJson(
            JSONObject()
                .put("schema_version", 1)
                .put("session_id", "update-remote-http")
                .put("fixture", "empty.clean")
                .put("update_scenario", "available_stable")
                .put(
                    "update_artifact",
                    JSONObject()
                        .put("version_code", 1_030_199)
                        .put("version_name", "v1.3.1")
                        .put("apk_url", "http://example.com/update.apk")
                        .put("apk_sha256", "ab".repeat(32)),
                )
        )
        val invalidDigest = encodeJson(
            JSONObject()
                .put("schema_version", 1)
                .put("session_id", "update-invalid-digest")
                .put("fixture", "empty.clean")
                .put("update_scenario", "available_stable")
                .put(
                    "update_artifact",
                    JSONObject()
                        .put("version_code", 1_030_199)
                        .put("version_name", "v1.3.1")
                        .put("apk_url", "http://127.0.0.1:48123/update.apk")
                        .put("apk_sha256", "not-a-digest"),
                )
        )

        assertThrows(IllegalArgumentException::class.java) { QualityRuntime.decode(plaintextRemote) }
        assertThrows(IllegalArgumentException::class.java) { QualityRuntime.decode(invalidDigest) }
    }

    @Test
    fun channelFailureScenariosRoundTripThroughTheTypedSession() {
        QualityChannelMutationScenario.entries
            .filterNot { it == QualityChannelMutationScenario.NONE }
            .forEachIndexed { index, channelScenario ->
                val session = QualitySessionDescriptor(
                    schemaVersion = 1,
                    sessionId = "channel-failure-$index",
                    fixture = QualityFixture.CHANNELS_STANDARD,
                    faults = QualityFaults(),
                    channelMutationScenario = channelScenario,
                )

                assertEquals(
                    channelScenario,
                    QualityRuntime.decode(QualityRuntime.encode(session)).channelMutationScenario,
                )
            }
    }

    @Test
    fun transportFailureScenariosRoundTripThroughTheTypedSession() {
        QualityTransportSwitchScenario.entries
            .filterNot { it == QualityTransportSwitchScenario.NONE }
            .forEachIndexed { index, transportScenario ->
                val session = QualitySessionDescriptor(
                    schemaVersion = 1,
                    sessionId = "transport-failure-$index",
                    fixture = QualityFixture.MESSAGES_STANDARD,
                    faults = QualityFaults(),
                    transportSwitchScenario = transportScenario,
                )

                assertEquals(
                    transportScenario,
                    QualityRuntime.decode(QualityRuntime.encode(session)).transportSwitchScenario,
                )
            }
    }

    @Test
    fun transportSelectionPersistenceFaultFailsOnceThenAllowsRetry() {
        val session = QualitySessionDescriptor(
            schemaVersion = 1,
            sessionId = "transport-persistence-retry",
            fixture = QualityFixture.CHANNELS_STANDARD,
            faults = QualityFaults(failTransportSelectionPersistenceOnce = true),
            transportSwitchScenario = QualityTransportSwitchScenario.ACCEPTED,
        )
        QualityRuntime.configure(QualityRuntime.encode(session))

        assertThrows(QualityTransportSelectionPersistenceException::class.java) {
            QualityRuntime.afterTransportSelectionPersistence()
        }
        QualityRuntime.afterTransportSelectionPersistence()
    }

    @Test
    fun everyAppOwnedFixtureRoundTripsThroughTheSessionAllowlist() {
        QualityFixture.entries.forEachIndexed { index, fixture ->
            val session = QualitySessionDescriptor(
                schemaVersion = 1,
                sessionId = "fixture-roundtrip-$index",
                fixture = fixture,
                faults = QualityFaults(),
            )

            assertEquals(fixture, QualityRuntime.decode(QualityRuntime.encode(session)).fixture)
        }
    }

    @Test
    fun pathTraversalSessionIdIsRejected() {
        val encoded = encodeJson(
            JSONObject()
                .put("schema_version", 1)
                .put("session_id", "../../pushgo")
                .put("fixture", "empty.clean")
        )

        assertThrows(IllegalArgumentException::class.java) {
            QualityRuntime.decode(encoded)
        }
    }

    @Test
    fun unboundedDelayFaultIsRejected() {
        val encoded = encodeJson(
            JSONObject()
                .put("schema_version", 1)
                .put("session_id", "slow-load-negative-control")
                .put("fixture", "messages.standard")
                .put("faults", JSONObject().put("message_load_delay_ms", 30_001))
        )

        assertThrows(IllegalArgumentException::class.java) {
            QualityRuntime.decode(encoded)
        }
    }

    @Test
    fun unboundedPageDelayFaultIsRejected() {
        val encoded = encodeJson(
            JSONObject()
                .put("schema_version", 1)
                .put("session_id", "unbounded-page-delay")
                .put("fixture", "messages.standard")
                .put("faults", JSONObject().put("message_page_load_delay_ms", 30_001))
        )

        assertThrows(IllegalArgumentException::class.java) {
            QualityRuntime.decode(encoded)
        }
    }

    @Test
    fun unboundedRefreshDelayFaultIsRejected() {
        val encoded = encodeJson(
            JSONObject()
                .put("schema_version", 1)
                .put("session_id", "slow-refresh-negative-control")
                .put("fixture", "messages.standard")
                .put("faults", JSONObject().put("message_refresh_delay_ms", 30_001))
        )

        assertThrows(IllegalArgumentException::class.java) {
            QualityRuntime.decode(encoded)
        }
    }

    @Test
    fun unboundedSearchDelayFaultIsRejected() {
        val encoded = encodeJson(
            JSONObject()
                .put("schema_version", 1)
                .put("session_id", "slow-search-negative-control")
                .put("fixture", "messages.standard")
                .put("faults", JSONObject().put("message_search_delay_ms", 30_001))
        )

        assertThrows(IllegalArgumentException::class.java) {
            QualityRuntime.decode(encoded)
        }
    }

    @Test
    fun artifactPathIsContainedUnderTheAppFilesDirectory() {
        val session = QualitySessionDescriptor(
            schemaVersion = 1,
            sessionId = "contained-session",
            fixture = QualityFixture.EMPTY_CLEAN,
            faults = QualityFaults(),
        )
        QualityRuntime.configure(QualityRuntime.encode(session))
        val filesDir = File("/app/data/files")

        val artifact = checkNotNull(
            QualityRuntime.artifactFileFromFilesDir(filesDir, "quality-readiness.json")
        )

        assertTrue(artifact.path.startsWith(filesDir.path + File.separator))
        assertTrue(!artifact.path.contains(".."))
    }

    @Test
    fun fixtureInitializationIsRecordedOncePerSessionAndSurvivesRuntimeReconfiguration() {
        val session = QualitySessionDescriptor(
            schemaVersion = 1,
            sessionId = "fixture-init-session",
            fixture = QualityFixture.CHANNELS_STANDARD,
            faults = QualityFaults(),
        )
        val encoded = QualityRuntime.encode(session)
        val filesDir = temporaryFolder.newFolder("files")
        QualityRuntime.configure(encoded)

        assertTrue(!QualityRuntime.fixtureInitializationWasRecorded(filesDir))
        QualityRuntime.recordFixtureInitialization(filesDir)
        assertTrue(QualityRuntime.fixtureInitializationWasRecorded(filesDir))

        QualityRuntime.resetForTesting()
        QualityRuntime.configure(encoded)

        assertTrue(QualityRuntime.fixtureInitializationWasRecorded(filesDir))
    }

    @Test
    fun invalidFixtureInitializationMarkerFailsLoudly() {
        val session = QualitySessionDescriptor(
            schemaVersion = 1,
            sessionId = "invalid-fixture-init",
            fixture = QualityFixture.MESSAGES_STANDARD,
            faults = QualityFaults(),
        )
        val filesDir = temporaryFolder.newFolder("invalid-files")
        QualityRuntime.configure(QualityRuntime.encode(session))
        val marker = File(
            filesDir,
            "quality/sessions/${session.sessionId}/fixture-initialization.json",
        )
        assertTrue(marker.parentFile?.mkdirs() == true)
        marker.writeText("not-json")

        assertThrows(IllegalStateException::class.java) {
            QualityRuntime.fixtureInitializationWasRecorded(filesDir)
        }
    }

    @Test
    fun messageLoadFaultFailsOnceThenAllowsARealRetry() = runBlocking {
        val session = QualitySessionDescriptor(
            schemaVersion = 1,
            sessionId = "retry-contract",
            fixture = QualityFixture.EMPTY_CLEAN,
            faults = QualityFaults(failMessageLoad = true),
        )
        QualityRuntime.configure(QualityRuntime.encode(session))

        var didFail = false
        try {
            QualityRuntime.beforeMessageListLoad()
        } catch (_: QualityMessageLoadException) {
            didFail = true
        }
        assertTrue(didFail)
        QualityRuntime.beforeMessageListLoad()
    }

    @Test
    fun gatewaySwitchValidationFaultFailsOnceThenAllowsTheSameUserRetry() {
        val session = QualitySessionDescriptor(
            schemaVersion = 1,
            sessionId = "gateway-switch-retry-contract",
            fixture = QualityFixture.CHANNELS_STANDARD,
            faults = QualityFaults(failGatewaySwitchValidationOnce = true),
        )
        QualityRuntime.configure(QualityRuntime.encode(session))

        assertThrows(QualityGatewaySwitchValidationException::class.java) {
            QualityRuntime.beforeGatewaySwitchValidation()
        }
        QualityRuntime.beforeGatewaySwitchValidation()
    }

    @Test
    fun gatewayPostCommitSyncFaultFailsOnceThenAllowsRecoveryRetry() {
        val session = QualitySessionDescriptor(
            schemaVersion = 1,
            sessionId = "gateway-post-commit-sync-contract",
            fixture = QualityFixture.CHANNELS_STANDARD,
            faults = QualityFaults(failGatewayPostCommitSyncOnce = true),
        )
        QualityRuntime.configure(QualityRuntime.encode(session))

        // The fault is armed only after the user-visible gateway commit;
        // startup/channel-entry sync must not consume it first.
        QualityRuntime.beforeGatewayPostCommitSync()
        QualityRuntime.armGatewayPostCommitSyncFailure()
        assertThrows(QualityGatewayPostCommitSyncException::class.java) {
            QualityRuntime.beforeGatewayPostCommitSync()
        }
        QualityRuntime.beforeGatewayPostCommitSync()
    }

    @Test
    fun providerRefreshScenarioFailsOnceThenReturnsARealIngressPage() {
        val session = QualitySessionDescriptor(
            schemaVersion = 1,
            sessionId = "refresh-recovery-contract",
            fixture = QualityFixture.MESSAGES_STANDARD,
            faults = QualityFaults(),
            messageRefreshScenario = QualityMessageRefreshScenario.FAIL_ONCE_THEN_NEW_MESSAGE,
        )
        QualityRuntime.configure(QualityRuntime.encode(session))

        assertTrue(checkNotNull(QualityRuntime.takeMessageRefreshPullOverride()).isFailure)
        val page = checkNotNull(QualityRuntime.takeMessageRefreshPullOverride()).getOrThrow()
        assertEquals("quality-refresh-result", page.items.single().payload["message_id"])
        assertTrue(checkNotNull(QualityRuntime.takeMessageRefreshPullOverride()).getOrThrow().items.isEmpty())
    }

    private fun encodeJson(json: JSONObject): String {
        return java.util.Base64.getEncoder().encodeToString(json.toString().toByteArray())
    }
}
