package com.alldoc.app.core.engine.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object DocumentCryptoEngine {

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128
    private const val IV_LENGTH_BYTE = 12
    private const val SALT_LENGTH_BYTE = 16
    private const val ITERATIONS = 65536
    private const val KEY_LENGTH_BIT = 256

    /**
     * Encrypts plaintext bytes using AES-256-GCM derived from a user password via PBKDF2.
     * Output format: [16 bytes salt] + [12 bytes IV] + [ciphertext + 16 bytes GCM auth tag]
     */
    suspend fun encrypt(plainBytes: ByteArray, password: CharArray): ByteArray = withContext(Dispatchers.Default) {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH_BYTE).apply { random.nextBytes(this) }
        val iv = ByteArray(IV_LENGTH_BYTE).apply { random.nextBytes(this) }

        val secretKey = deriveKey(password, salt)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BIT, iv))

        val cipherBytes = cipher.doFinal(plainBytes)

        val output = ByteArray(SALT_LENGTH_BYTE + IV_LENGTH_BYTE + cipherBytes.size)
        System.arraycopy(salt, 0, output, 0, SALT_LENGTH_BYTE)
        System.arraycopy(iv, 0, output, SALT_LENGTH_BYTE, IV_LENGTH_BYTE)
        System.arraycopy(cipherBytes, 0, output, SALT_LENGTH_BYTE + IV_LENGTH_BYTE, cipherBytes.size)

        output
    }

    /**
     * Decrypts ciphertext bytes using the user password.
     * Throws exception if the password is incorrect or the data is tampered with (GCM authentication failure).
     */
    suspend fun decrypt(encryptedBytes: ByteArray, password: CharArray): ByteArray = withContext(Dispatchers.Default) {
        if (encryptedBytes.size < SALT_LENGTH_BYTE + IV_LENGTH_BYTE + 16) {
            throw IllegalArgumentException("Invalid encrypted payload size")
        }

        val salt = ByteArray(SALT_LENGTH_BYTE)
        val iv = ByteArray(IV_LENGTH_BYTE)
        val cipherLength = encryptedBytes.size - SALT_LENGTH_BYTE - IV_LENGTH_BYTE
        val cipherBytes = ByteArray(cipherLength)

        System.arraycopy(encryptedBytes, 0, salt, 0, SALT_LENGTH_BYTE)
        System.arraycopy(encryptedBytes, SALT_LENGTH_BYTE, iv, 0, IV_LENGTH_BYTE)
        System.arraycopy(encryptedBytes, SALT_LENGTH_BYTE + IV_LENGTH_BYTE, cipherBytes, 0, cipherLength)

        val secretKey = deriveKey(password, salt)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BIT, iv))

        cipher.doFinal(cipherBytes)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH_BIT)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }
}
