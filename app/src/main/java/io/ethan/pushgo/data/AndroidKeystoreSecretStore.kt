package io.ethan.pushgo.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreSecretStore(context: Context) : SecureSecretStore {
    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "pushgo.secure.secrets.v1"
        private const val PREF_FILE = "pushgo_secure_secrets_v1"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_MIN_PAYLOAD_SIZE = 13
        private const val SECRET_GATEWAY_TOKEN = "gateway_token"
        private const val SECRET_FCM_TOKEN = "fcm_token"
        private const val SECRET_NOTIFICATION_KEY = "notification_key_bytes"
        private const val SECRET_PROVIDER_DEVICE_KEY_PREFIX = "provider_device_key_"
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    override fun gatewayToken(): String? {
        return getString(SECRET_GATEWAY_TOKEN)?.trim()?.ifEmpty { null }
    }

    override fun setGatewayToken(token: String?) {
        putString(SECRET_GATEWAY_TOKEN, token?.trim()?.ifEmpty { null })
    }

    override fun fcmToken(): String? {
        return getString(SECRET_FCM_TOKEN)?.trim()?.ifEmpty { null }
    }

    override fun setFcmToken(token: String?) {
        putString(SECRET_FCM_TOKEN, token?.trim()?.ifEmpty { null })
    }

    override fun notificationKeyBytes(): ByteArray? =
        getBytes(SECRET_NOTIFICATION_KEY)?.takeIf { it.isNotEmpty() }

    override fun setNotificationKeyBytes(value: ByteArray?) {
        putBytes(SECRET_NOTIFICATION_KEY, value?.takeIf { it.isNotEmpty() })
    }

    override fun providerDeviceKey(platform: String): String? {
        return getString(providerDeviceSecretKey(platform))?.trim()?.ifEmpty { null }
    }

    override fun setProviderDeviceKey(platform: String, deviceKey: String?) {
        putString(
            providerDeviceSecretKey(platform),
            deviceKey?.trim()?.ifEmpty { null }
        )
    }

    override fun channelPassword(gatewayUrl: String, channelId: String): String? {
        return getString(channelPasswordKey(gatewayUrl, channelId))?.trim()?.ifEmpty { null }
    }

    override fun setChannelPassword(gatewayUrl: String, channelId: String, password: String?) {
        putString(
            channelPasswordKey(gatewayUrl, channelId),
            password?.trim()?.ifEmpty { null }
        )
    }

    override fun removeChannelPassword(gatewayUrl: String, channelId: String) {
        delete(channelPasswordKey(gatewayUrl, channelId))
    }

    override fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun getString(key: String): String? {
        val bytes = getBytes(key) ?: return null
        val value = bytes.toString(Charsets.UTF_8).trim()
        return value.ifEmpty { null }
    }

    private fun putString(key: String, value: String?) {
        val normalized = value?.trim().orEmpty()
        if (normalized.isEmpty()) {
            delete(key)
            return
        }
        putBytes(key, normalized.toByteArray(Charsets.UTF_8))
    }

    private fun getBytes(key: String): ByteArray? {
        val encrypted = prefs.getString(key, null) ?: return null
        return decrypt(encrypted)
    }

    private fun putBytes(key: String, value: ByteArray?) {
        val normalized = value?.takeIf { it.isNotEmpty() }
        if (normalized == null) {
            delete(key)
            return
        }
        val encrypted = encrypt(normalized) ?: return
        prefs.edit().putString(key, encrypted).apply()
    }

    private fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }

    private fun channelPasswordKey(gatewayUrl: String, channelId: String): String {
        return "channel_password_${sha256Hex("${gatewayUrl.trim()}|${channelId.trim()}")}"
    }

    private fun providerDeviceSecretKey(platform: String): String {
        val normalized = platform.trim().lowercase().ifEmpty { "unknown" }
        return SECRET_PROVIDER_DEVICE_KEY_PREFIX + normalized
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            builder.append(((byte.toInt() ushr 4) and 0x0f).toString(16))
            builder.append((byte.toInt() and 0x0f).toString(16))
        }
        return builder.toString()
    }

    private fun encrypt(plaintext: ByteArray): String? {
        return runCatching {
            val key = loadOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plaintext)
            val payload = ByteArray(1 + iv.size + encrypted.size)
            payload[0] = iv.size.toByte()
            System.arraycopy(iv, 0, payload, 1, iv.size)
            System.arraycopy(encrypted, 0, payload, 1 + iv.size, encrypted.size)
            Base64.encodeToString(payload, Base64.NO_WRAP)
        }.getOrNull()
    }

    private fun decrypt(encoded: String): ByteArray? {
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size < GCM_MIN_PAYLOAD_SIZE) {
                return@runCatching null
            }
            val ivLength = payload[0].toInt() and 0xff
            if (ivLength <= 0 || payload.size <= 1 + ivLength) {
                return@runCatching null
            }
            val iv = payload.copyOfRange(1, 1 + ivLength)
            val encrypted = payload.copyOfRange(1 + ivLength, payload.size)
            val key = loadOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            cipher.doFinal(encrypted)
        }.getOrNull()
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            return existing
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
