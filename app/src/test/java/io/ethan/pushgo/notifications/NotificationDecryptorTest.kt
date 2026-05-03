package io.ethan.pushgo.notifications

import io.ethan.pushgo.data.model.DecryptionState
import io.ethan.pushgo.util.JsonCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class NotificationDecryptorTest {

    @Test
    fun decryptIfNeeded_decodesCanonicalCiphertextFields_withoutMissingAnyField() {
        val key = "1234567890123456".toByteArray(Charsets.UTF_8)
        val cipherPayload = """
            {
              "title": "enc-title",
              "body": "enc-body",
              "url": "https://enc.example/open",
              "images": ["https://img.example/a.png", "https://img.example/b.png"],
              "tags": ["a", "b", "c"],
              "metadata": {"k":"v","n":1},
              "description": "enc-description",
              "status": "open",
              "message": "enc-message",
              "attrs": {"name":"pump","version":2},
              "started_at": 1710000000000,
              "ended_at": 1710000009999,
              "primary_image": "https://img.example/primary.png",
              "state": "active",
              "created_at": 1710000010000,
              "deleted_at": 1710000019999,
              "external_ids": {"serial":"SN-1","asset":"A-1"},
              "location_type": "geo",
              "location_value": "31.2304,121.4737",
              "location": {"type":"geo","value":"31.2304,121.4737"}
            }
        """.trimIndent()

        val ciphertext = encryptForIngress(cipherPayload, key)
        val result = NotificationDecryptor.decryptIfNeeded(
            data = mapOf("ciphertext" to ciphertext),
            title = "plain-title",
            body = "plain-body",
            keyBytes = key,
        )

        assertEquals("enc-title", result.title)
        assertEquals("enc-body", result.body)
        assertEquals(listOf("https://img.example/a.png", "https://img.example/b.png"), result.images)
        assertEquals("https://enc.example/open", result.url)
        assertEquals("enc-description", result.description)
        assertEquals("open", result.statusText)
        assertEquals("enc-message", result.messageText)
        assertEquals("1710000000000", result.startedAt)
        assertEquals("1710000009999", result.endedAt)
        assertEquals("https://img.example/primary.png", result.primaryImage)
        assertEquals("active", result.stateText)
        assertEquals("1710000010000", result.createdAt)
        assertEquals("1710000019999", result.deletedAt)
        assertEquals("geo", result.locationType)
        assertEquals("31.2304,121.4737", result.locationValue)
        assertEquals(DecryptionState.DECRYPT_OK, result.decryptionState)

        val tags = JsonCompat.parseArray(result.tagsJson)?.mapNotNull { it?.toString() } ?: emptyList()
        assertEquals(listOf("a", "b", "c"), tags)

        val metadata = JsonCompat.parseObject(result.metadataJson)
        assertNotNull(metadata)
        assertEquals("v", metadata?.get("k"))

        val attrs = JsonCompat.parseObject(result.attrsJson)
        assertNotNull(attrs)
        assertEquals("pump", attrs?.get("name"))

        val externalIds = JsonCompat.parseObject(result.externalIdsJson)
        assertNotNull(externalIds)
        assertEquals("SN-1", externalIds?.get("serial"))

        val location = JsonCompat.parseObject(result.locationJson)
        assertNotNull(location)
        assertEquals("geo", location?.get("type"))
    }

    private fun encryptForIngress(plaintext: String, keyBytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = byteArrayOf(1, 3, 5, 7, 9, 11, 13, 15, 2, 4, 6, 8)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), spec)
        val cipherAndTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val output = ByteArray(cipherAndTag.size + iv.size)
        System.arraycopy(cipherAndTag, 0, output, 0, cipherAndTag.size)
        System.arraycopy(iv, 0, output, cipherAndTag.size, iv.size)
        return Base64.getEncoder().encodeToString(output)
    }
}
