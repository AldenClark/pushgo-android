package io.ethan.pushgo.update

import android.content.Context
import android.util.Base64
import io.ethan.pushgo.data.AppConstants
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.NoSuchAlgorithmException
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json

class UpdateFeedClient(private val context: Context) {
    private data class SignatureVerifierConfig(
        val signatureKey: String,
        val signatureAlgorithm: String,
        val keyFactoryAlgorithm: String,
        val publicKeyBase64: String,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    suspend fun fetchFeed(): UpdateFeedPayload = withContext(Dispatchers.IO) {
        val feedUrl = AppConstants.defaultUpdateFeedUrl.trim()
        require(feedUrl.isNotEmpty()) { "Update feed URL is empty" }
        require(feedUrl.startsWith("https://") || feedUrl.startsWith("http://")) {
            "Update feed URL must be http(s): $feedUrl"
        }

        val raw = fetchText(feedUrl)
        val parsed = json.decodeFromString<SignedUpdateFeed>(raw)
        verifySignatureIfConfigured(parsed)
        parsed.payload
    }

    private fun verifySignatureIfConfigured(document: SignedUpdateFeed) {
        val verifiers = configuredSignatureVerifiers()
        if (verifiers.isEmpty()) {
            return
        }

        val payloadCanonical = canonicalJson(
            json.encodeToJsonElement(UpdateFeedPayload.serializer(), document.payload)
        )
        val payloadBytes = payloadCanonical.toByteArray(StandardCharsets.UTF_8)
        val signaturesByAlgorithm = collectSignatures(document)
        if (signaturesByAlgorithm.isEmpty()) {
            error("Update feed signature is missing")
        }

        val attempted = mutableListOf<String>()
        val errors = mutableListOf<String>()
        for (verifier in verifiers) {
            val signatureB64 = signaturesByAlgorithm[verifier.signatureKey] ?: continue
            attempted += verifier.signatureKey
            try {
                if (verifySignature(verifier, payloadBytes, signatureB64)) {
                    return
                }
                errors += "${verifier.signatureKey}: signature mismatch"
            } catch (error: NoSuchAlgorithmException) {
                errors += "${verifier.signatureKey}: algorithm unavailable (${error.message ?: error::class.simpleName})"
            } catch (error: Throwable) {
                errors += "${verifier.signatureKey}: ${error.message ?: error::class.simpleName.orEmpty()}"
            }
        }

        if (attempted.isEmpty()) {
            error("Update feed does not contain a signature compatible with this app")
        }
        error(
            "Update feed signature verification failed: ${errors.joinToString(separator = "; ")}"
        )
    }

    private fun configuredSignatureVerifiers(): List<SignatureVerifierConfig> {
        val configured = mutableListOf<SignatureVerifierConfig>()
        val ecdsaP256Key = AppConstants.updateFeedSignaturePublicKeyBase64.trim()
        if (ecdsaP256Key.isNotEmpty()) {
            configured += SignatureVerifierConfig(
                signatureKey = "ecdsa-p256-sha256",
                signatureAlgorithm = "SHA256withECDSA",
                keyFactoryAlgorithm = "EC",
                publicKeyBase64 = ecdsaP256Key,
            )
        }
        return configured
    }

    private fun collectSignatures(document: SignedUpdateFeed): Map<String, String> {
        val normalized = mutableMapOf<String, String>()
        document.signatures.forEach { (algorithm, signature) ->
            val key = algorithm.trim().lowercase()
            val value = signature.trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                normalized[key] = value
            }
        }
        return normalized
    }

    private fun verifySignature(
        verifierConfig: SignatureVerifierConfig,
        payloadBytes: ByteArray,
        signatureB64: String,
    ): Boolean {
        val publicKeyBytes = Base64.decode(verifierConfig.publicKeyBase64, Base64.DEFAULT)
        val signatureBytes = Base64.decode(signatureB64, Base64.DEFAULT)
        val key = loadPublicKey(verifierConfig, publicKeyBytes)
        val verifier = Signature.getInstance(verifierConfig.signatureAlgorithm)
        verifier.initVerify(key)
        verifier.update(payloadBytes)
        return verifier.verify(signatureBytes)
    }

    private fun loadPublicKey(verifierConfig: SignatureVerifierConfig, keyBytes: ByteArray): PublicKey {
        val keyFactory = KeyFactory.getInstance(verifierConfig.keyFactoryAlgorithm)
        return keyFactory.generatePublic(X509EncodedKeySpec(keyBytes))
    }

    private fun canonicalJson(element: JsonElement): String {
        return when (element) {
            is JsonObject -> {
                element.entries
                    .sortedBy { it.key }
                    .joinToString(prefix = "{", postfix = "}") { (key, value) ->
                        "\"${escapeJsonString(key)}\":${canonicalJson(value)}"
                    }
            }
            is JsonArray -> {
                element.joinToString(prefix = "[", postfix = "]") { canonicalJson(it) }
            }
            is JsonPrimitive -> {
                if (element.isString) {
                    "\"${escapeJsonString(element.content)}\""
                } else {
                    element.toString()
                }
            }
        }
    }

    private fun escapeJsonString(value: String): String {
        val builder = StringBuilder(value.length + 8)
        value.forEach { ch ->
            when (ch) {
                '\\' -> builder.append("\\\\")
                '"' -> builder.append("\\\"")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> {
                    if (ch.code in 0x00..0x1F) {
                        builder.append("\\u%04x".format(ch.code))
                    } else {
                        builder.append(ch)
                    }
                }
            }
        }
        return builder.toString()
    }

    private fun fetchText(endpoint: String): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "${context.packageName}/android-update-feed")
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
            val body = BufferedReader(InputStreamReader(BufferedInputStream(stream), StandardCharsets.UTF_8)).use {
                it.readText()
            }
            if (status !in 200..299) {
                throw IllegalStateException("Update feed request failed: HTTP $status ${body.take(180)}")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }
}
