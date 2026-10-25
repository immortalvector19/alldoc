package com.alldoc.app.core.util

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UriSecurityUtilsTest {

    @Test
    fun testIsSafeIncomingUri_allowedSchemes() {
        val contentUriStr = "content://com.android.providers.media.documents/document/123"
        val fileUriStr = "file:///sdcard/Download/report.pdf"

        assertTrue(UriSecurityUtils.isSafeIncomingUri(contentUriStr))
        assertTrue(UriSecurityUtils.isSafeIncomingUri(fileUriStr))
    }

    @Test
    fun testIsSafeIncomingUri_rejectsDangerousSchemes() {
        assertFalse(UriSecurityUtils.isSafeIncomingUri("http://malicious.com/payload.apk"))
        assertFalse(UriSecurityUtils.isSafeIncomingUri("https://malicious.com/payload.apk"))
        assertFalse(UriSecurityUtils.isSafeIncomingUri("javascript:alert(1)"))
        assertFalse(UriSecurityUtils.isSafeIncomingUri("intent:#Intent;action=android.intent.action.VIEW;end"))
    }

    @Test
    fun testIsSafeIncomingUri_rejectsPathTraversalInFileUri() {
        val traversalUriStr = "file:///data/data/com.alldoc.app/../../etc/passwd"
        assertFalse(UriSecurityUtils.isSafeIncomingUri(traversalUriStr))
    }

    @Test
    fun testSanitizeFileName_neutralizesDirectoryTraversal() {
        val dirtyName = "../../private_db.realm"
        val sanitized = UriSecurityUtils.sanitizeFileName(dirtyName)
        assertEquals("private_db.realm", sanitized)
    }

    @Test
    fun testSanitizeFileName_handlesBlankAndNull() {
        val fallbackNull = UriSecurityUtils.sanitizeFileName(null)
        assertTrue(fallbackNull.startsWith("shared_"))

        val fallbackBlank = UriSecurityUtils.sanitizeFileName("   ")
        assertTrue(fallbackBlank.startsWith("shared_"))
    }

    @Test
    fun testIsPathWithinDir() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "alldoc_test_dir").apply { mkdirs() }
        val childFile = File(tempDir, "sample.txt")
        val outsideFile = File(tempDir, "../sample.txt")

        assertTrue(UriSecurityUtils.isPathWithinDir(childFile, tempDir))
        assertFalse(UriSecurityUtils.isPathWithinDir(outsideFile, tempDir))

        tempDir.deleteRecursively()
    }
}
