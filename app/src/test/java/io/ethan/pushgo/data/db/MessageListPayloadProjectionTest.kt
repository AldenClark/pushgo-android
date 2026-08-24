package io.ethan.pushgo.data.db

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageListPayloadProjectionTest {
    @Test
    fun projectionKeepsOnlyBoundedListFields() {
        val tags = JSONArray((0 until 40).map { "tag-$it-${"x".repeat(100)}" })
        val images = JSONArray((0 until 12).map { "https://example.com/${"i".repeat(3_000)}/$it.png" })
        val raw = JSONObject()
            .put("severity", "critical-${"s".repeat(100)}")
            .put("tags", tags)
            .put("images", images)
            .put("image_local_path", "/cache/${"p".repeat(2_000)}")
            .put("metadata", "m".repeat(1_000_000))
            .put("ciphertext", "c".repeat(1_000_000))
            .put("entity_type", "message")
            .put("encrypted", true)

        val encoded = MessageEntity.buildListPayloadJson(raw.toString())
        val projected = JSONObject(encoded)

        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= MessageEntity.MAX_LIST_PAYLOAD_BYTES)
        assertEquals(32, projected.getString("severity").length)
        assertTrue(JSONArray(projected.getString("tags")).length() <= 16)
        assertTrue(JSONArray(projected.getString("images")).length() <= 4)
        assertTrue(projected.getString("image_local_path").length <= 1_024)
        listOf("metadata", "ciphertext", "entity_type", "encrypted", "aps").forEach { key ->
            assertFalse(projected.has(key))
        }
    }

    @Test
    fun projectionNormalizesStringAndArrayInputsForListConsumers() {
        val raw = JSONObject()
            .put("tags", "[\"ops\",\"ops\",\"alerts\"]")
            .put("images", JSONArray(listOf("https://example.com/a.png", "https://example.com/b.png")))

        val projected = JSONObject(MessageEntity.buildListPayloadJson(raw.toString()))

        assertEquals(listOf("ops", "alerts"), jsonStrings(projected.getString("tags")))
        assertEquals(
            listOf("https://example.com/a.png", "https://example.com/b.png"),
            jsonStrings(projected.getString("images")),
        )
    }

    private fun jsonStrings(encoded: String): List<String> {
        val array = JSONArray(encoded)
        return (0 until array.length()).map(array::getString)
    }
}
