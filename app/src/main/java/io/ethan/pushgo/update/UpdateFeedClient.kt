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
        val publicKeyB64 = AppConstants.updateFeedPublicKeyBase64.trim()
        if (publicKeyB64.isEmpty()) {
            return
        }
        val signatureB64 = document.signature?.trim().orEmpty()
        require(signatureB64.isNotEmpty()) { "Update feed signature is missing" }

        val payloadCanonical = canonicalJson(
            json.encodeToJsonElement(UpdateFeedPayload.serializer(), document.payload)
        )
        val payloadBytes = payloadCanonical.toByteArray(StandardCharsets.UTF_8)
        val publicKeyBytes = Base64.decode(publicKeyB64, Base64.DEFAULT)
        val signatureBytes = Base64.decode(signatureB64, Base64.DEFAULT)
        val keyFactory = KeyFactory.getInstance("Ed25519")
        val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicKeyBytes))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(payloadBytes)
        require(verifier.verify(signatureBytes)) { "Update feed signature verification failed" }
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
