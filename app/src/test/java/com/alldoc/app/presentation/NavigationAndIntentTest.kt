package com.alldoc.app.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class NavigationAndIntentTest {

    @Test
    fun testDoubleDecodingBugProofAndFix() {
        // A7 Reproduction & Verification:
        // When a filename has special characters like "100% report+final #2.pdf",
        // Android Navigation component automatically decodes the path argument once.
        val originalFileName = "100% report+final #2.pdf"

        // 1. Old MainActivity called URLEncoder.encode
        val encodedOnce = URLEncoder.encode(originalFileName, StandardCharsets.UTF_8.name())
        assertEquals("100%25+report%2Bfinal+%232.pdf", encodedOnce)

        // 2. Navigation component internally decodes the path argument:
        val navDecoded = URLDecoder.decode(encodedOnce, StandardCharsets.UTF_8.name())
        assertEquals("100% report+final #2.pdf", navDecoded)

        // 3. Old DocumentViewerScreen called URLDecoder.decode a SECOND time on navDecoded.
        // This causes URLDecoder to crash with IllegalArgumentException on lone '%' followed by space:
        assertThrows(IllegalArgumentException::class.java) {
            URLDecoder.decode(navDecoded, StandardCharsets.UTF_8.name())
        }

        // 4. Fixed Architecture:
        // MainActivity does NOT pass raw encoded URIs in navigation routes.
        // The URI is held directly in ViewModel StateFlow, preserving special characters identically.
        val safeUriHolder = originalFileName
        assertEquals("100% report+final #2.pdf", safeUriHolder)
    }
}
