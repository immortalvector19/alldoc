package com.alldoc.app.core.engine.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AllDoc Document Vault Cryptographic Engine
 *
 * Security Specifications:
 * - Cipher: AES-256-GCM (Galois/Counter Mode with 128-bit AEAD authentication tag).
 * - Key Derivation: PBKDF2WithHmacSHA256 with 600,000 iterations (OWASP recommendation).
 * - Salt: 16 bytes (128-bit) cryptographically secure random per encrypted file.
 * - IV / Nonce: 12 bytes (96-bit) cryptographically secure random per encrypted file.
 * - Container Magic: "ALLDOC_VAULT_V1\n" (16 bytes) at file header.
 *
 * Format Specification:
 * +-----------------------+------------------+----------------+-------------------------------+
 * | 16-byte Magic Header  | 16-byte Salt     | 12-byte IV     | AES-GCM Ciphertext + 16B Tag  |
 * +-----------------------+------------------+----------------+-------------------------------+
 */
object DocumentCryptoEngine {

    val VAULT_MAGIC: ByteArray = "ALLDOC_VAULT_V1\n".toByteArray(StandardCharsets.US_ASCII)
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128
    private const val IV_LENGTH_BYTE = 12
    private const val SALT_LENGTH_BYTE = 16
    private const val ITERATIONS = 600000
    private const val KEY_LENGTH_BIT = 256

    class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Checks if the given byte array begins with the AllDoc Vault magic header.
     */
    fun isVaultFile(headerBytes: ByteArray): Boolean {
        if (headerBytes.size < VAULT_MAGIC.size) return false
        for (i in VAULT_MAGIC.indices) {
            if (headerBytes[i] != VAULT_MAGIC[i]) return false
        }
        return true
    }

    /**
     * Checks if the given file begins with the AllDoc Vault magic header.
     */
    fun isVaultFile(file: File): Boolean {
        if (!file.exists() || file.length() < VAULT_MAGIC.size) return false
        return try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(VAULT_MAGIC.size)
                val read = input.read(buffer)
                read == VAULT_MAGIC.size && isVaultFile(buffer)
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Encrypts plaintext bytes using AES-256-GCM derived from a user password via PBKDF2-HMAC-SHA256 (600,000 iterations).
     * Output format: [16B Magic] + [16B Salt] + [12B IV] + [Ciphertext + 16B GCM auth tag]
     */
    suspend fun encrypt(plainBytes: ByteArray, password: CharArray): ByteArray = withContext(Dispatchers.Default) {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH_BYTE).apply { random.nextBytes(this) }
        val iv = ByteArray(IV_LENGTH_BYTE).apply { random.nextBytes(this) }

        val secretKey = deriveKey(password, salt)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BIT, iv))

        val cipherBytes = cipher.doFinal(plainBytes)

        val output = ByteArray(VAULT_MAGIC.size + SALT_LENGTH_BYTE + IV_LENGTH_BYTE + cipherBytes.size)
        System.arraycopy(VAULT_MAGIC, 0, output, 0, VAULT_MAGIC.size)
        System.arraycopy(salt, 0, output, VAULT_MAGIC.size, SALT_LENGTH_BYTE)
        System.arraycopy(iv, 0, output, VAULT_MAGIC.size + SALT_LENGTH_BYTE, IV_LENGTH_BYTE)
        System.arraycopy(cipherBytes, 0, output, VAULT_MAGIC.size + SALT_LENGTH_BYTE + IV_LENGTH_BYTE, cipherBytes.size)

        output
    }

    /**
     * Decrypts ciphertext bytes using the user password.
     * Throws CryptoException if the password is incorrect or the data is tampered with (GCM authentication failure).
     */
    suspend fun decrypt(encryptedBytes: ByteArray, password: CharArray): ByteArray = withContext(Dispatchers.Default) {
        val hasMagic = isVaultFile(encryptedBytes)
        val headerOffset = if (hasMagic) VAULT_MAGIC.size else 0

        val minExpectedSize = headerOffset + SALT_LENGTH_BYTE + IV_LENGTH_BYTE + 16 // 16 bytes minimum auth tag
        if (encryptedBytes.size < minExpectedSize) {
            throw CryptoException("Invalid encrypted payload size")
        }

        val salt = ByteArray(SALT_LENGTH_BYTE)
        val iv = ByteArray(IV_LENGTH_BYTE)
        val cipherLength = encryptedBytes.size - headerOffset - SALT_LENGTH_BYTE - IV_LENGTH_BYTE
        val cipherBytes = ByteArray(cipherLength)

        System.arraycopy(encryptedBytes, headerOffset, salt, 0, SALT_LENGTH_BYTE)
        System.arraycopy(encryptedBytes, headerOffset + SALT_LENGTH_BYTE, iv, 0, IV_LENGTH_BYTE)
        System.arraycopy(encryptedBytes, headerOffset + SALT_LENGTH_BYTE + IV_LENGTH_BYTE, cipherBytes, 0, cipherLength)

        try {
            val secretKey = deriveKey(password, salt)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BIT, iv))
            cipher.doFinal(cipherBytes)
        } catch (e: GeneralSecurityException) {
            throw CryptoException("Decryption failed. Incorrect password or corrupted vault file.", e)
        }
    }

    /**
     * Encrypts a source file into a destination vault file.
     */
    suspend fun encryptFile(sourceFile: File, destFile: File, password: CharArray) = withContext(Dispatchers.IO) {
        val plainBytes = sourceFile.readBytes()
        val encrypted = encrypt(plainBytes, password)
        FileOutputStream(destFile).use { it.write(encrypted) }
    }

    /**
     * Decrypts a source vault file into a destination plaintext file.
     */
    suspend fun decryptFile(sourceFile: File, destFile: File, password: CharArray) = withContext(Dispatchers.IO) {
        val encryptedBytes = sourceFile.readBytes()
        val decrypted = decrypt(encryptedBytes, password)
        FileOutputStream(destFile).use { it.write(decrypted) }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH_BIT)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }
}
