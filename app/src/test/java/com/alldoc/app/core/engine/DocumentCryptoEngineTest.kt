package com.alldoc.app.core.engine

import com.alldoc.app.core.engine.security.DocumentCryptoEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class DocumentCryptoEngineTest {

    @Test
    fun testEncryptionAndDecryption() = runBlocking {
        val originalText = "Sensitive financial report and passwords"
        val originalBytes = originalText.toByteArray(Charsets.UTF_8)
        val password = "SuperSecretPassword123!".toCharArray()

        val encrypted = DocumentCryptoEngine.encrypt(originalBytes, password)
        assertFalse(originalBytes.contentEquals(encrypted))

        val decrypted = DocumentCryptoEngine.decrypt(encrypted, password)
        assertArrayEquals(originalBytes, decrypted)
    }

    @Test
    fun testDecryptionWithWrongPasswordFails() {
        runBlocking {
            val originalBytes = "Top Secret Document".toByteArray(Charsets.UTF_8)
            val correctPassword = "CorrectPassword123".toCharArray()
            val wrongPassword = "WrongPassword456".toCharArray()

            val encrypted = DocumentCryptoEngine.encrypt(originalBytes, correctPassword)

            assertThrows(Exception::class.java) {
                runBlocking {
                    DocumentCryptoEngine.decrypt(encrypted, wrongPassword)
                }
            }
        }
    }
}
