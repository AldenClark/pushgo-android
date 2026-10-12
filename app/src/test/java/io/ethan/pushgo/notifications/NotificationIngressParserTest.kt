package io.ethan.pushgo.notifications

import io.ethan.pushgo.data.ProviderAckContract
import io.ethan.pushgo.util.JsonCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class NotificationIngressParserTest {

    @Test
    fun authenticatedNullTitleAndBodyCannotFallBackToOuterCompanionContent() {
        val correctKey = "1234567890123456".toByteArray(Charsets.UTF_8)
        val ciphertext = encryptForIngress("""{"title":null,"body":null}""", correctKey)
        val parsed = NotificationIngressParser.parse(
            data = mapOf(
                "entity_type" to "message",
                "message_id" to "authenticated-null-1",
                "entity_id" to "authenticated-null-1",
                "title" to "attacker null-title fallback",
                "body" to "attacker null-body fallback",
                "url" to "https://attacker.example/null",
                "metadata" to "{\"source\":\"attacker\"}",
                "ciphertext" to ciphertext,
            ),
            transportMessageId = "authenticated-null-transport",
            keyBytes = correctKey,
            now = Instant.ofEpochSecond(1_710_000_000),
        ) as? InboundPersistenceRequest.Message
        assertNotNull(parsed)
        parsed ?: return

        assertEquals(io.ethan.pushgo.data.model.DecryptionState.DECRYPT_OK, parsed.message.decryptionState)
        assertEquals(NotificationIngressParser.AUTHENTICATION_FAILED_TITLE, parsed.message.title)
        assertEquals("", parsed.message.body)
        assertNull(parsed.message.url)
        assertFalse(parsed.message.rawPayloadJson.contains("attacker"))
    }

    @Test
    fun externalReservedRecoveryFieldsCannotPoisonCanonicalCiphertextRecovery() {
        val correctKey = "1234567890123456".toByteArray(Charsets.UTF_8)
        val canonicalCiphertext = encryptForIngress(
            """{"title":"trusted canonical title","body":"trusted canonical body"}""",
            correctKey,
        )
        val attackerKey = ByteArray(16) { 0x5A.toByte() }
        val attackerTitleEnvelope = encryptForIngress("attacker title", attackerKey)
        val attackerBodyEnvelope = encryptForIngress("attacker body", attackerKey)
        val unavailable = NotificationIngressParser.parse(
            data = mapOf(
                "entity_type" to "message",
                "message_id" to "reserved-poison-1",
                "entity_id" to "reserved-poison-1",
                "ciphertext" to canonicalCiphertext,
                NotificationIngressParser.RECOVERY_TITLE_CIPHERTEXT to attackerTitleEnvelope,
                NotificationIngressParser.RECOVERY_BODY_CIPHERTEXT to attackerBodyEnvelope,
            ),
            transportMessageId = "reserved-poison-transport-1",
            keyBytes = null,
            now = Instant.ofEpochSecond(1_710_000_000),
        ) as? InboundPersistenceRequest.Message
        assertNotNull(unavailable)
        unavailable ?: return

        val unavailableRaw = JsonCompat.parseObject(unavailable.message.rawPayloadJson) ?: emptyMap()
        assertFalse(unavailableRaw.containsKey(NotificationIngressParser.RECOVERY_TITLE_CIPHERTEXT))
        assertFalse(unavailableRaw.containsKey(NotificationIngressParser.RECOVERY_BODY_CIPHERTEXT))
        val recoveryPayload = encryptedRecoveryPayloadStrings(unavailable.message.rawPayloadJson)
        assertNotNull(recoveryPayload)
        recoveryPayload ?: return
        val recovered = NotificationIngressParser.parse(
            data = recoveryPayload,
            transportMessageId = unavailable.message.notificationId,
            keyBytes = correctKey,
            now = unavailable.message.receivedAt,
        ) as? InboundPersistenceRequest.Message
        assertNotNull(recovered)
        recovered ?: return

        assertEquals(io.ethan.pushgo.data.model.DecryptionState.DECRYPT_OK, recovered.message.decryptionState)
        assertEquals("trusted canonical title", recovered.message.title)
        assertEquals("trusted canonical body", recovered.message.body)
        assertFalse(recovered.message.rawPayloadJson.contains("attacker"))
    }

    @Test
    fun inlineEncryptedFields_wrongKeyRetainsCiphertextAndCorrectKeyRecoversExactContent() {
        val correctKey = "1234567890123456".toByteArray(Charsets.UTF_8)
        val titleCiphertext = encryptForIngress("trusted inline title", correctKey)
        val bodyCiphertext = encryptForIngress("trusted inline body", correctKey)
        val wrongKeyParsed = NotificationIngressParser.parse(
            data = mapOf(
                "entity_type" to "message",
                "message_id" to "inline-canonical-1",
                "entity_id" to "inline-canonical-1",
                "title" to titleCiphertext,
                "body" to bodyCiphertext,
            ),
            transportMessageId = "inline-transport-1",
            keyBytes = ByteArray(16) { 0x5A.toByte() },
            now = Instant.ofEpochSecond(1_710_000_000),
        ) as? InboundPersistenceRequest.Message
        assertNotNull(wrongKeyParsed)
        wrongKeyParsed ?: return

        assertEquals(InboundSecurityDisposition.AUTHENTICATION_FAILED, wrongKeyParsed.securityDisposition)
        assertEquals(NotificationIngressParser.AUTHENTICATION_FAILED_TITLE, wrongKeyParsed.message.title)
        assertEquals(NotificationIngressParser.AUTHENTICATION_FAILED_BODY, wrongKeyParsed.message.body)
        val failedRaw = JsonCompat.parseObject(wrongKeyParsed.message.rawPayloadJson) ?: emptyMap()
        assertEquals(titleCiphertext, failedRaw[NotificationIngressParser.RECOVERY_TITLE_CIPHERTEXT])
        assertEquals(bodyCiphertext, failedRaw[NotificationIngressParser.RECOVERY_BODY_CIPHERTEXT])

        val recoveryPayload = encryptedRecoveryPayloadStrings(wrongKeyParsed.message.rawPayloadJson)
        assertNotNull(recoveryPayload)
        recoveryPayload ?: return
        assertEquals(titleCiphertext, recoveryPayload["title"])
        assertEquals(bodyCiphertext, recoveryPayload["body"])
        assertFalse(recoveryPayload.containsKey(NotificationIngressParser.RECOVERY_TITLE_CIPHERTEXT))
        assertFalse(recoveryPayload.containsKey(NotificationIngressParser.RECOVERY_BODY_CIPHERTEXT))
        val recovered = NotificationIngressParser.parse(
            data = recoveryPayload,
            transportMessageId = wrongKeyParsed.message.notificationId,
            keyBytes = correctKey,
            now = wrongKeyParsed.message.receivedAt,
        ) as? InboundPersistenceRequest.Message
        assertNotNull(recovered)
        recovered ?: return

        assertEquals("inline-canonical-1", recovered.message.messageId)
        assertEquals("trusted inline title", recovered.message.title)
        assertEquals("trusted inline body", recovered.message.body)
        assertEquals(io.ethan.pushgo.data.model.DecryptionState.DECRYPT_OK, recovered.message.decryptionState)
        val recoveredRaw = JsonCompat.parseObject(recovered.message.rawPayloadJson) ?: emptyMap()
        assertEquals(titleCiphertext, recoveredRaw[NotificationIngressParser.RECOVERY_TITLE_CIPHERTEXT])
        assertEquals(bodyCiphertext, recoveredRaw[NotificationIngressParser.RECOVERY_BODY_CIPHERTEXT])
    }

    @Test
    fun authenticatedPayloadOnlyUsesFieldsPresentInsideAuthenticatedPlaintext() {
        val correctKey = "1234567890123456".toByteArray(Charsets.UTF_8)
        val ciphertext = encryptForIngress("""{"body":"trusted body only"}""", correctKey)
        val parsed = NotificationIngressParser.parse(
            data = mapOf(
                "entity_type" to "message",
                "message_id" to "partial-authenticated-1",
                "entity_id" to "partial-authenticated-1",
                "title" to "attacker title",
                "body" to "attacker body",
                "url" to "https://attacker.example/open",
                "images" to "[\"https://attacker.example/image.png\"]",
                "metadata" to "{\"source\":\"attacker\"}",
                "severity" to "critical",
                "ciphertext" to ciphertext,
            ),
            transportMessageId = "partial-authenticated-transport",
            keyBytes = correctKey,
            now = Instant.ofEpochSecond(1_710_000_000),
        ) as? InboundPersistenceRequest.Message
        assertNotNull(parsed)
        parsed ?: return

        assertEquals(InboundSecurityDisposition.ACCEPTED, parsed.securityDisposition)
        assertEquals(NotificationIngressParser.AUTHENTICATION_FAILED_TITLE, parsed.message.title)
        assertEquals("trusted body only", parsed.message.body)
        assertNull(parsed.message.url)
        assertTrue(parsed.message.imageUrls.isEmpty())
        assertNull(parsed.level)
        val raw = JsonCompat.parseObject(parsed.message.rawPayloadJson) ?: emptyMap()
        assertFalse(raw.values.any { it?.toString()?.contains("attacker") == true })
        assertFalse(raw.containsKey("metadata"))
        assertFalse(raw.containsKey("severity"))
    }

    @Test
    fun encryptedEntity_authenticationFailureCannotEnterProjectionAndRedeliveryCanRecover() {
        val correctKey = "1234567890123456".toByteArray(Charsets.UTF_8)
        val ciphertext = encryptForIngress(
            """{"title":"trusted event","body":"trusted event body"}""",
            correctKey,
        )
        val payload = mapOf(
            "entity_type" to "event",
            "entity_id" to "event-auth-1",
            "event_id" to "event-auth-1",
            "delivery_id" to "event-auth-delivery-1",
            "title" to "attacker event title",
            "body" to "attacker event body",
            "metadata" to "{\"source\":\"attacker\"}",
            "ciphertext" to ciphertext,
        )
        val failed = NotificationIngressParser.parse(
            data = payload,
            transportMessageId = "event-auth-transport-1",
            keyBytes = ByteArray(16) { 0x5A.toByte() },
            now = Instant.ofEpochSecond(1_710_000_000),
        ) as? InboundPersistenceRequest.Entity
        assertNotNull(failed)
        failed ?: return

        assertEquals(InboundSecurityDisposition.AUTHENTICATION_FAILED, failed.securityDisposition)
        assertFalse(shouldPersistEntityProjection(failed))
        assertFalse(failed.shouldNotify)
        assertNull(failed.providerAckIdentity)
        assertFalse(failed.record.rawPayloadJson.contains("attacker"))

        val recoveredRedelivery = NotificationIngressParser.parse(
            data = payload,
            transportMessageId = "event-auth-transport-1",
            keyBytes = correctKey,
            now = Instant.ofEpochSecond(1_710_000_000),
        ) as? InboundPersistenceRequest.Entity
        assertNotNull(recoveredRedelivery)
        recoveredRedelivery ?: return
        assertEquals(InboundSecurityDisposition.ACCEPTED, recoveredRedelivery.securityDisposition)
        assertTrue(shouldPersistEntityProjection(recoveredRedelivery))
        assertEquals("trusted event", recoveredRedelivery.record.title)
        assertEquals("trusted event body", recoveredRedelivery.record.body)
    }

    @Test
    fun encryptedThing_authenticationFailureCannotEnterProjection() {
        val correctKey = "1234567890123456".toByteArray(Charsets.UTF_8)
        val ciphertext = encryptForIngress(
            """{"title":"trusted thing","body":"trusted thing body"}""",
            correctKey,
        )
        val failed = NotificationIngressParser.parse(
            data = mapOf(
                "entity_type" to "thing",
                "entity_id" to "thing-auth-1",
                "thing_id" to "thing-auth-1",
                "delivery_id" to "thing-auth-delivery-1",
                "title" to "attacker thing title",
                "body" to "attacker thing body",
                "ciphertext" to ciphertext,
            ),
            transportMessageId = "thing-auth-transport-1",
            keyBytes = ByteArray(16) { 0x5A.toByte() },
            now = Instant.ofEpochSecond(1_710_000_000),
        ) as? InboundPersistenceRequest.Entity
        assertNotNull(failed)
        failed ?: return

        assertEquals(InboundSecurityDisposition.AUTHENTICATION_FAILED, failed.securityDisposition)
        assertFalse(shouldPersistEntityProjection(failed))
        assertFalse(failed.shouldNotify)
        assertFalse(failed.record.rawPayloadJson.contains("attacker"))
    }

    @Test
    fun authenticatedCiphertextFailure_quarantinesCompanionContentAndExternalSideEffects() {
        val correctKey = "1234567890123456".toByteArray(Charsets.UTF_8)
        val ciphertext = encryptForIngress(
            plaintext = """{"title":"trusted-title","body":"trusted-body"}""",
            keyBytes = correctKey,
        )
        val payload = mapOf(
            "entity_type" to "message",
            "message_id" to "canonical-message-1",
            "entity_id" to "canonical-message-1",
            "delivery_id" to "delivery-auth-failure-1",
            "base_url" to "https://gateway.example",
            "provider_device_key" to "provider-device",
            "title" to "attacker companion title",
            "body" to "attacker companion body",
            "url" to "https://attacker.example/open",
            "metadata" to "{\"role\":\"attacker\"}",
            "images" to "[\"https://attacker.example/image.png\"]",
            "ciphertext" to ciphertext,
        )

        val parsed = NotificationIngressParser.parse(
            data = payload,
            transportMessageId = "transport-auth-failure-1",
            keyBytes = ByteArray(16) { 0x5A.toByte() },
            now = Instant.ofEpochSecond(1_710_000_000),
        ) as? InboundPersistenceRequest.Message
        assertNotNull(parsed)
        parsed ?: return

        assertEquals("canonical-message-1", parsed.message.messageId)
        assertEquals(NotificationIngressParser.AUTHENTICATION_FAILED_TITLE, parsed.message.title)
        assertEquals(NotificationIngressParser.AUTHENTICATION_FAILED_BODY, parsed.message.body)
        assertNull(parsed.message.url)
        assertTrue(parsed.message.imageUrls.isEmpty())
        assertEquals(io.ethan.pushgo.data.model.DecryptionState.DECRYPT_FAILED, parsed.message.decryptionState)
        assertEquals(InboundSecurityDisposition.AUTHENTICATION_FAILED, parsed.securityDisposition)
        assertFalse(parsed.shouldNotify)
        assertNull(parsed.providerAckIdentity)

        val raw = JsonCompat.parseObject(parsed.message.rawPayloadJson) ?: emptyMap()
        assertEquals(ciphertext, raw["ciphertext"])
        assertEquals("canonical-message-1", raw["message_id"])
        assertFalse(raw.values.any { it?.toString()?.contains("attacker") == true })
    }

    @Test
    fun authenticatedCiphertextSuccess_restoresCanonicalContentAndAckEligibility() {
        val correctKey = "1234567890123456".toByteArray(Charsets.UTF_8)
        val ciphertext = encryptForIngress(
            plaintext = """{"title":"trusted-title","body":"trusted-body"}""",
            keyBytes = correctKey,
        )
        val parsed = NotificationIngressParser.parse(
            data = mapOf(
                "entity_type" to "message",
                "message_id" to "canonical-message-1",
                "entity_id" to "canonical-message-1",
                "delivery_id" to "delivery-auth-success-1",
                "base_url" to "https://gateway.example",
                "provider_device_key" to "provider-device",
                "title" to "attacker companion title",
                "body" to "attacker companion body",
                "ciphertext" to ciphertext,
            ),
            transportMessageId = "transport-auth-success-1",
            keyBytes = correctKey,
            now = Instant.ofEpochSecond(1_710_000_000),
        ) as? InboundPersistenceRequest.Message
        assertNotNull(parsed)
        parsed ?: return

        assertEquals("canonical-message-1", parsed.message.messageId)
        assertEquals("trusted-title", parsed.message.title)
        assertEquals("trusted-body", parsed.message.body)
        assertEquals(io.ethan.pushgo.data.model.DecryptionState.DECRYPT_OK, parsed.message.decryptionState)
        assertEquals(InboundSecurityDisposition.ACCEPTED, parsed.securityDisposition)
        assertTrue(parsed.shouldNotify)
        assertNotNull(parsed.providerAckIdentity)
        val raw = JsonCompat.parseObject(parsed.message.rawPayloadJson) ?: emptyMap()
        assertEquals(ciphertext, raw["ciphertext"])
    }

    @Test
    fun corruptedAuthenticatedCiphertext_quarantinesCompanionContentAndKeepsCiphertext() {
        val correctKey = "1234567890123456".toByteArray(Charsets.UTF_8)
        val validCiphertext = encryptForIngress(
            plaintext = """{"title":"trusted-title","body":"trusted-body"}""",
            keyBytes = correctKey,
        )
        val corruptedBytes = Base64.getDecoder().decode(validCiphertext).also { bytes ->
            bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        }
        val corruptedCiphertext = Base64.getEncoder().encodeToString(corruptedBytes)
        val parsed = NotificationIngressParser.parse(
            data = mapOf(
                "entity_type" to "message",
                "message_id" to "canonical-corrupt-1",
                "entity_id" to "canonical-corrupt-1",
                "delivery_id" to "delivery-corrupt-1",
                "base_url" to "https://gateway.example",
                "provider_device_key" to "provider-device",
                "title" to "attacker corrupt title",
                "body" to "attacker corrupt body",
                "url" to "https://attacker.example/open",
                "metadata" to "{\"source\":\"attacker\"}",
                "ciphertext" to corruptedCiphertext,
            ),
            transportMessageId = "transport-corrupt-1",
            keyBytes = correctKey,
            now = Instant.ofEpochSecond(1_710_000_000),
        ) as? InboundPersistenceRequest.Message
        assertNotNull(parsed)
        parsed ?: return

        assertEquals("canonical-corrupt-1", parsed.message.messageId)
        assertEquals(NotificationIngressParser.AUTHENTICATION_FAILED_TITLE, parsed.message.title)
        assertEquals(NotificationIngressParser.AUTHENTICATION_FAILED_BODY, parsed.message.body)
        assertEquals(io.ethan.pushgo.data.model.DecryptionState.DECRYPT_FAILED, parsed.message.decryptionState)
        assertEquals(InboundSecurityDisposition.AUTHENTICATION_FAILED, parsed.securityDisposition)
        assertFalse(parsed.securityDisposition.allowsNotification)
        assertFalse(parsed.securityDisposition.allowsSuccessfulAck)
        assertFalse(parsed.shouldNotify)
        assertNull(parsed.providerAckIdentity)
        val raw = JsonCompat.parseObject(parsed.message.rawPayloadJson) ?: emptyMap()
        assertEquals(corruptedCiphertext, raw["ciphertext"])
        assertFalse(raw.values.any { it?.toString()?.contains("attacker") == true })
    }

    @Test
    fun parseMessage_sanitizesOpenUrlAndImagesBeforePersistence() {
        val payload = mapOf(
            "entity_type" to "message",
            "message_id" to "m-1",
            "entity_id" to "m-1",
            "title" to "hello",
            "body" to "[x](javascript:alert(1)) and [ok](https://safe.example/p)",
            "url" to "javascript:alert(1)",
            "images" to "[\"https://cdn.example.com/a.png\",\"http://localhost/b.png\",\"data:image/png;base64,AAA\"]",
        )

        val parsed = NotificationIngressParser.parse(
            data = payload,
            transportMessageId = "fcm-1",
            keyBytes = null,
            now = Instant.ofEpochSecond(1_710_000_000),
        )
        val message = (parsed as? InboundPersistenceRequest.Message)?.message
        assertNotNull(message)
        message ?: return

        assertEquals("[x](#) and [ok](https://safe.example/p)", message.body)
        assertNull(message.url)
        assertEquals(listOf("https://cdn.example.com/a.png"), message.imageUrls)

        val raw = JsonCompat.parseObject(message.rawPayloadJson) ?: emptyMap()
        assertFalse(raw.containsKey("url"))
        val rawImages = raw["images"]?.toString().orEmpty()
        assertTrue(rawImages.contains("https://cdn.example.com/a.png"))
        assertFalse(rawImages.contains("localhost"))
        assertFalse(rawImages.contains("data:image"))
    }

    @Test
    fun parseThing_keepsCanonicalThingFieldsUntouchedByIngressFilter() {
        val payload = mapOf(
            "entity_type" to "thing",
            "thing_id" to "thing-1",
            "entity_id" to "thing-1",
            "title" to "Object",
            "body" to "updated",
            "description" to "[bad](javascript:alert(1))",
            "message" to "[ok](https://safe.example/x)",
            "primary_image" to "http://127.0.0.1/a.png",
            "images" to "[\"https://cdn.example.com/a.png\",\"http://localhost/b.png\"]",
        )

        val parsed = NotificationIngressParser.parse(
            data = payload,
            transportMessageId = null,
            keyBytes = null,
            now = Instant.ofEpochSecond(1_710_000_000),
        )
        val entity = parsed as? InboundPersistenceRequest.Entity
        assertNotNull(entity)
        entity ?: return
        val raw = JsonCompat.parseObject(entity.record.rawPayloadJson) ?: emptyMap()
        assertEquals("[bad](javascript:alert(1))", raw["description"])
        assertEquals("[ok](https://safe.example/x)", raw["message"])
        assertEquals("http://127.0.0.1/a.png", raw["primary_image"])
        val images = JsonCompat.parseArray(raw["images"]?.toString())?.mapNotNull { it?.toString() } ?: emptyList()
        assertEquals(
            listOf("https://cdn.example.com/a.png"),
            images,
        )
    }

    private fun encryptForIngress(plaintext: String, keyBytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = byteArrayOf(1, 3, 5, 7, 9, 11, 13, 15, 2, 4, 6, 8)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val cipherAndTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipherAndTag + iv)
    }

    @Test
    fun parseMessage_keepsCiphertextFlowAndMarksNotConfiguredWithoutKey() {
        val payload = mapOf(
            "entity_type" to "message",
            "message_id" to "m-2",
            "entity_id" to "m-2",
            "ciphertext" to "QUJDREVGR0hJSg==",
        )

        val parsed = NotificationIngressParser.parse(
            data = payload,
            transportMessageId = null,
            keyBytes = null,
            now = Instant.ofEpochSecond(1_710_000_000),
        )
        val message = (parsed as? InboundPersistenceRequest.Message)?.message
        assertNotNull(message)
        message ?: return

        assertEquals(io.ethan.pushgo.data.model.DecryptionState.NOT_CONFIGURED, message.decryptionState)
        val raw = JsonCompat.parseObject(message.rawPayloadJson) ?: emptyMap()
        assertEquals("notConfigured", raw["decryption_state"])
        assertEquals("QUJDREVGR0hJSg==", raw["ciphertext"])
    }

    @Test
    fun parseMessage_keepsMarkdownRichBodyPersistable() {
        val richBody = "[https://sway.cloud.microsoft/lNjlqkdUA7wtAxfV](https://sway.cloud.microsoft/lNjlqkdUA7wtAxfV)\n\n无论可以玩玩。有上千个，\n\n\n\n[原文链接](https://www.v2ex.com/t/1200790)"
        val payload = mapOf(
            "entity_type" to "message",
            "message_id" to "m-rich-1",
            "entity_id" to "m-rich-1",
            "title" to "sample",
            "body" to richBody,
        )

        val parsed = NotificationIngressParser.parse(
            data = payload,
            transportMessageId = "fcm-rich-1",
            keyBytes = null,
            now = Instant.ofEpochSecond(1_710_000_000),
        )
        val message = (parsed as? InboundPersistenceRequest.Message)?.message
        assertNotNull(message)
        message ?: return

        assertEquals(richBody, message.body)
        val raw = JsonCompat.parseObject(message.rawPayloadJson) ?: emptyMap()
        assertEquals(richBody, raw["body"])
    }

    @Test
    fun parseMessage_resolvesLegacyLevelAliasWhenSeverityMissing() {
        val payload = mapOf(
            "entity_type" to "message",
            "message_id" to "m-level-alias-1",
            "entity_id" to "m-level-alias-1",
            "title" to "hello",
            "body" to "world",
            "level" to "medium",
        )

        val parsed = NotificationIngressParser.parse(
            data = payload,
            transportMessageId = null,
            keyBytes = null,
            now = Instant.ofEpochSecond(1_710_000_000),
        )
        val message = (parsed as? InboundPersistenceRequest.Message)?.message
        assertNotNull(message)
        message ?: return
        val raw = JsonCompat.parseObject(message.rawPayloadJson) ?: emptyMap()
        assertEquals("normal", raw["severity"])
    }

    @Test
    fun parseMessage_resolvesNumericPriorityAliasWhenSeverityMissing() {
        val payload = mapOf(
            "entity_type" to "message",
            "message_id" to "m-priority-alias-1",
            "entity_id" to "m-priority-alias-1",
            "title" to "hello",
            "body" to "world",
            "priority" to "5",
        )

        val parsed = NotificationIngressParser.parse(
            data = payload,
            transportMessageId = null,
            keyBytes = null,
            now = Instant.ofEpochSecond(1_710_000_000),
        )
        val message = (parsed as? InboundPersistenceRequest.Message)?.message
        assertNotNull(message)
        message ?: return
        val raw = JsonCompat.parseObject(message.rawPayloadJson) ?: emptyMap()
        assertEquals("critical", raw["severity"])
    }

    @Test
    fun parseEntity_normalizesMillisecondTimestamps() {
        val payload = mapOf(
            "entity_type" to "event",
            "event_id" to "evt-1",
            "entity_id" to "evt-1",
            "title" to "alarm",
            "body" to "opened",
            "event_time" to "1710000000123",
        )

        val parsed = NotificationIngressParser.parse(
            data = payload,
            transportMessageId = null,
            keyBytes = null,
            now = Instant.ofEpochSecond(1_710_000_100),
        )
        val entity = parsed as? InboundPersistenceRequest.Entity
        assertNotNull(entity)
        entity ?: return
        assertEquals(1_710_000_000_123L, entity.record.eventTimeEpoch)
    }

    @Test
    fun parseEntity_usesDisplayFallbackWithoutBackfillingPatchPayloadText() {
        val payload = mapOf(
            "entity_type" to "event",
            "event_id" to "evt-fallback-1",
            "entity_id" to "evt-fallback-1",
            "metadata" to "{\"stage\":\"patched\"}",
        )

        val parsed = NotificationIngressParser.parse(
            data = payload,
            transportMessageId = null,
            keyBytes = null,
            now = Instant.ofEpochSecond(1_710_000_000),
        )
        val entity = parsed as? InboundPersistenceRequest.Entity
        assertNotNull(entity)
        entity ?: return

        assertEquals("Event evt-fallback-1", entity.record.title)
        assertEquals("Updated", entity.record.body)
        assertTrue(entity.shouldNotify)
        val raw = JsonCompat.parseObject(entity.record.rawPayloadJson) ?: emptyMap()
        assertEquals("", raw["title"])
        assertEquals("", raw["body"])
        assertEquals("{\"stage\":\"patched\"}", raw["metadata"])
    }

    @Test
    fun parseEventUpdateAndClose_notifyWithoutHighSeverity() {
        for (state in listOf("updated", "closed")) {
            val parsed = NotificationIngressParser.parse(
                data = mapOf(
                    "entity_type" to "event",
                    "event_id" to "evt-$state",
                    "entity_id" to "evt-$state",
                    "status" to state,
                    "event_state" to state,
                ),
                transportMessageId = null,
                keyBytes = null,
                now = Instant.ofEpochSecond(1_710_000_000),
            )
            val entity = parsed as? InboundPersistenceRequest.Entity
            assertNotNull(entity)
            entity ?: continue

            assertTrue(entity.shouldNotify)
        }
    }

    @Test
    fun parseThingUpdateArchiveDelete_notifyAndUseOperationBody() {
        val cases = listOf(
            Triple("/thing/update", mapOf("attrs" to "{\"temperature\":\"24\"}"), "Attribute update || temperature: 24"),
            Triple("/thing/archive", mapOf("attrs" to "{\"temperature\":\"24\"}"), "Archived"),
            Triple("/thing/delete", emptyMap<String, String>(), "Deleted"),
        )

        cases.forEachIndexed { index, (endpoint, extra, expectedBody) ->
            val parsed = NotificationIngressParser.parse(
                data = mapOf(
                    "entity_type" to "thing",
                    "thing_id" to "thing-op-$index",
                    "entity_id" to "thing-op-$index",
                    "endpoint" to endpoint,
                    "severity" to "normal",
                ) + extra,
                transportMessageId = null,
                keyBytes = null,
                now = Instant.ofEpochSecond(1_710_000_000),
            )
            val entity = parsed as? InboundPersistenceRequest.Entity
            assertNotNull(entity)
            entity ?: return@forEachIndexed

            assertTrue(entity.shouldNotify)
            assertEquals(expectedBody, entity.notificationBody)
        }
    }

    @Test
    fun providerWakeupPullDeliveryId_requiresWakeupMarkers() {
        assertEquals(
            "delivery-1",
            NotificationIngressParser.providerWakeupPullDeliveryId(
                mapOf(
                    "delivery_id" to "delivery-1",
                    "provider_wakeup" to "1",
                    "provider_mode" to "wakeup",
                )
            ),
        )
        assertNull(
            NotificationIngressParser.providerWakeupPullDeliveryId(
                mapOf("delivery_id" to "delivery-2")
            )
        )
        assertNull(
            NotificationIngressParser.providerWakeupPullDeliveryId(
                mapOf(
                    "delivery_id" to "delivery-3",
                    "provider_wakeup" to "1",
                    "provider_mode" to "direct",
                )
            )
        )
    }

    @Test
    fun parseDirectPayloadCarriesImmutableProviderAckIdentity() {
        val parsed = NotificationIngressParser.parse(
            data = mapOf(
                "entity_type" to "event",
                "event_id" to "event-provider-source",
                "entity_id" to "event-provider-source",
                "delivery_id" to "delivery-provider-source",
                "base_url" to "HTTPS://Gateway-A.Example/GatewayA/",
                "provider_device_key" to " device-a ",
            ),
            transportMessageId = "transport-provider-source",
            keyBytes = null,
            now = Instant.ofEpochSecond(1_710_000_000),
        ) as InboundPersistenceRequest.Entity

        val identity = requireNotNull(parsed.providerAckIdentity)
        assertEquals("https://gateway-a.example/GatewayA", identity.gatewayUrl)
        assertEquals("device-a", identity.deviceKey)
        assertEquals(ProviderAckContract.LEGACY_SINGLE, identity.contract)
        assertEquals("provider_direct", identity.source)
    }

    @Test
    fun parseDirectPayloadWithoutCompleteProviderSourceDoesNotInventAckIdentity() {
        val parsed = NotificationIngressParser.parse(
            data = mapOf(
                "entity_type" to "event",
                "event_id" to "event-provider-source-missing",
                "entity_id" to "event-provider-source-missing",
                "delivery_id" to "delivery-provider-source-missing",
                "base_url" to "https://gateway-current.example",
            ),
            transportMessageId = "transport-provider-source-missing",
            keyBytes = null,
            now = Instant.ofEpochSecond(1_710_000_000),
        ) as InboundPersistenceRequest.Entity

        assertNull(parsed.providerAckIdentity)
    }
}
