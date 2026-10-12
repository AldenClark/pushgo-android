package io.ethan.pushgo.update

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateFeedSignatureRegressionTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun liveBeta10Feed_verifiesWithCurrentCanonicalization() {
        val raw = javaClass.classLoader
            ?.getResourceAsStream("update_feed_beta10.json")
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
            ?: error("Missing update_feed_beta10.json fixture")

        val parsed = json.decodeFromString<SignedUpdateFeed>(raw)
        val rawDocument = json.parseToJsonElement(raw).jsonObject
        val rawPayload = rawDocument["payload"] ?: error("payload missing")
        val payloadCanonical = CanonicalJson.encode(rawPayload)
        val payloadSha256 = sha256Hex(payloadCanonical.toByteArray(StandardCharsets.UTF_8))
        println("payloadCanonical.length=${payloadCanonical.length}")
        println("payloadCanonical.sha256=$payloadSha256")
        assertEquals("beta.10 payload canonicalization drifted", EXPECTED_PAYLOAD_SHA256, payloadSha256)
        val signatureBytes = Base64.getDecoder().decode(parsed.signatures.getValue("ecdsa-p256-sha256"))
        val publicKeyBytes = Base64.getDecoder().decode(PUBLIC_KEY_B64)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyBytes))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(payloadCanonical.toByteArray(StandardCharsets.UTF_8))

        assertTrue(
            "Current client canonicalization must verify the shipped feed fixture",
            verifier.verify(signatureBytes),
        )
    }

    @Test
    fun currentRepositoryFeed_verifiesWithProductionAlgorithm() {
        assertTrue(
            "The repository feed must verify with the public key and canonicalization used by the client",
            signatureVerifies(repositoryFeed()),
        )
    }

    @Test
    fun currentRepositoryFeed_rejectsTamperedPayload() {
        val raw = repositoryFeed()
        val document = json.parseToJsonElement(raw).jsonObject
        val payload = document.getValue("payload").jsonObject
        val generatedAt = payload.getValue("generatedAtEpochMs").toString().toLong()
        val tamperedPayload = buildJsonObject {
            payload.forEach { (key, value) -> put(key, value) }
            put("generatedAtEpochMs", JsonPrimitive(generatedAt + 1))
        }
        val tampered = buildJsonObject {
            put("payload", tamperedPayload)
            put("signatures", document.getValue("signatures"))
        }.toString()

        assertTrue("A changed payload must invalidate the existing signature", !signatureVerifies(tampered))
    }

    private fun repositoryFeed(): String {
        val path = listOf(
            Path.of("release/update-feed-v1.json"),
            Path.of("../release/update-feed-v1.json"),
        ).firstOrNull(Files::isRegularFile)
            ?: error("Missing repository release/update-feed-v1.json")
        return Files.readString(path, StandardCharsets.UTF_8)
    }

    private fun signatureVerifies(raw: String): Boolean {
        val parsed = json.decodeFromString<SignedUpdateFeed>(raw)
        val payload = json.parseToJsonElement(raw).jsonObject.getValue("payload")
        val canonical = CanonicalJson.encode(payload).toByteArray(StandardCharsets.UTF_8)
        val signatureBytes = Base64.getDecoder().decode(parsed.signatures.getValue("ecdsa-p256-sha256"))
        val publicKeyBytes = Base64.getDecoder().decode(PUBLIC_KEY_B64)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyBytes))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(canonical)
        return verifier.verify(signatureBytes)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { "%02x".format(it) }
    }

    companion object {
        private const val EXPECTED_PAYLOAD_SHA256 =
            "ee1e94cf656dba2329d680162fce79d82e218bda4d89ce176cb0c3c70ff63520"
        private const val PUBLIC_KEY_B64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEhC90rtHfY5sNLPHksv8tyvWcv8JRbuD8oeoIjFhat4rq1Sj9JRUlFiGOOAv0Leqd+RymbOiC4BtQcfdJtr7trw=="
    }
}
