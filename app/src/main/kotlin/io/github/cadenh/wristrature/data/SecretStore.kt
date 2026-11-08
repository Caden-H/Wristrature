package io.github.cadenh.wristrature.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The OPDS and sync passwords. Masked in [toString] to keep them out of logs and crash reports. */
@Serializable
data class Secrets(val opdsPassword: String = "", val syncPassword: String = "") {
    override fun toString() = "Secrets(***)"
}

interface SecretStorage {
    val secrets: StateFlow<Secrets>
    fun save(secrets: Secrets)
    fun clear()
}

/**
 * Stores the passwords encrypted with AES-256-GCM. The AES key lives in the Android Keystore and cannot be exported;
 * the file is in no-backup storage and app backup is disabled.
 * File format: [version=1][iv length][iv][ciphertext+tag].
 */
class SecretStore(context: Context) : SecretStorage {
    private val file = File(context.noBackupFilesDir, "secrets.bin")
    private val lock = Any()
    private val state by lazy { MutableStateFlow(read()) }
    private val json = Json { ignoreUnknownKeys = true }

    override val secrets: StateFlow<Secrets> by lazy { state.asStateFlow() }

    override fun save(secrets: Secrets) = synchronized(lock) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val sealed = cipher.doFinal(json.encodeToString(Secrets.serializer(), secrets).toByteArray(Charsets.UTF_8))
        val out = ByteBuffer.allocate(2 + iv.size + sealed.size).put(FORMAT_VERSION).put(iv.size.toByte()).put(iv).put(sealed).array()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        state.value = secrets
    }

    override fun clear() = synchronized(lock) {
        file.delete()
        state.value = Secrets()
    }

    private fun read(): Secrets {
        if (!file.exists()) return Secrets()
        return try {
            val buffer = ByteBuffer.wrap(file.readBytes())
            if (buffer.get() != FORMAT_VERSION) return Secrets()
            val iv = ByteArray(buffer.get().toInt()).also { buffer.get(it) }
            val sealed = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            json.decodeFromString(Secrets.serializer(), String(cipher.doFinal(sealed), Charsets.UTF_8))
        } catch (e: Exception) {
            // Key lost or file damaged: discard it; the passwords must be entered again.
            file.delete()
            Secrets()
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "wristrature_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val FORMAT_VERSION: Byte = 1
    }
}

/** In-memory [SecretStorage] for tests and screenshots. */
class MemorySecrets(initial: Secrets = Secrets()) : SecretStorage {
    private val state = MutableStateFlow(initial)
    override val secrets: StateFlow<Secrets> = state.asStateFlow()
    override fun save(secrets: Secrets) { state.value = secrets }
    override fun clear() { state.value = Secrets() }
}
