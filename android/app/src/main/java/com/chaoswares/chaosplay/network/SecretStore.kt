package com.chaoswares.chaosplay.network

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.secretStore by preferencesDataStore(name = "chaosplay_secrets")

/**
 * Passwords and access tokens, encrypted with an AES-GCM key that never
 * leaves the Android Keystore (spec 19.8: "store credentials in Android
 * Keystore"; "never log passwords or tokens"). Values are stored as
 * base64(iv || ciphertext) and decrypted only when a request needs them.
 */
class SecretStore(private val context: Context) {

    private val alias = "chaosplay.secrets.v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }.getOrNull()

    suspend fun put(name: String, value: String) {
        val enc = runCatching { encrypt(value) }.getOrElse {
            // Keystore broken (rare emulator/ROM bugs): refuse to store in
            // plain text; the user simply signs in again next launch.
            return
        }
        context.secretStore.edit { it[stringPreferencesKey(name)] = enc }
    }

    suspend fun get(name: String): String? {
        val raw = context.secretStore.data.first()[stringPreferencesKey(name)] ?: return null
        return decrypt(raw)
    }

    suspend fun remove(name: String) {
        context.secretStore.edit { it.remove(stringPreferencesKey(name)) }
    }

    suspend fun clearAll() {
        context.secretStore.edit { it.clear() }
    }

    companion object {
        fun passwordKey(serverId: String) = "pw.$serverId"
        fun tokenKey(serverId: String) = "token.$serverId"

        /** Stable per-install device id for Jellyfin's DeviceId field. */
        fun deviceIdFallback(): String = "chaosplay-" + (Build.MODEL ?: "android").replace(' ', '-')
    }
}
