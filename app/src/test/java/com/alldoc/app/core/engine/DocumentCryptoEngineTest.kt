package com.alldoc.app.core.engine

import com.alldoc.app.core.engine.security.DocumentCryptoEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DocumentCryptoEngineTest {

    @Test
    fun testEncryptionAndDecryptionWithMagicHeader() {
        runBlocking {
            val originalText = "Sensitive financial report and passwords"
            val originalBytes = originalText.toByteArray(Charsets.UTF_8)
            val password = "SuperSecretPassword123!".toCharArray()

            val encrypted = DocumentCryptoEngine.encrypt(originalBytes, password)
            assertFalse(originalBytes.contentEquals(encrypted))
            assertTrue(DocumentCryptoEngine.isVaultFile(encrypted))

            val decrypted = DocumentCryptoEngine.decrypt(encrypted, password)
            assertArrayEquals(originalBytes, decrypted)
            assertEquals(originalText, String(decrypted, Charsets.UTF_8))
        }
    }

    @Test
    fun testDecryptionWithWrongPasswordFails() {
        runBlocking {
            val originalBytes = "Top Secret Document".toByteArray(Charsets.UTF_8)
            val correctPassword = "CorrectPassword123".toCharArray()
            val wrongPassword = "WrongPassword456".toCharArray()

            val encrypted = DocumentCryptoEngine.encrypt(originalBytes, correctPassword)

            assertThrows(DocumentCryptoEngine.CryptoException::class.java) {
                runBlocking {
                    DocumentCryptoEngine.decrypt(encrypted, wrongPassword)
                }
            }
        }
    }

    @Test
    fun testCiphertextTamperingDetected() {
        runBlocking {
            val originalBytes = "Document content to be tamper tested".toByteArray(Charsets.UTF_8)
            val password = "PasswordTest999!".toCharArray()

            val encrypted = DocumentCryptoEngine.encrypt(originalBytes, password)
            // Flip one bit in the ciphertext / tag region
            val tampered = encrypted.copyOf()
            tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()

            assertThrows(DocumentCryptoEngine.CryptoException::class.java) {
                runBlocking {
                    DocumentCryptoEngine.decrypt(tampered, password)
                }
            }
        }
    }

    @Test
    fun testFileEncryptionAndDecryption() {
        runBlocking {
            val tempSrc = File.createTempFile("crypto_src_", ".txt")
            val tempEnc = File.createTempFile("crypto_enc_", ".vault")
            val tempDec = File.createTempFile("crypto_dec_", ".txt")

            try {
                val testContent = "AllDoc Secure Vault on-disk test content"
                tempSrc.writeText(testContent)
                val password = "StrongPassword456!".toCharArray()

                DocumentCryptoEngine.encryptFile(tempSrc, tempEnc, password)
                assertTrue(DocumentCryptoEngine.isVaultFile(tempEnc))

                DocumentCryptoEngine.decryptFile(tempEnc, tempDec, password)
                assertEquals(testContent, tempDec.readText())
            } finally {
                tempSrc.delete()
                tempEnc.delete()
                tempDec.delete()
            }
        }
    }
}
