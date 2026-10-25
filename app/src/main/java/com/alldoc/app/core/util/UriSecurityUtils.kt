package com.alldoc.app.core.util

import android.net.Uri
import java.io.File
import java.util.Locale

object UriSecurityUtils {

    fun isSafeIncomingUri(uriString: String): Boolean {
        val scheme = uriString.substringBefore("://", "").lowercase(Locale.ROOT)
        if (scheme != "content" && scheme != "file") {
            return false
        }
        if (scheme == "file") {
            val path = uriString.removePrefix("file://")
            if (path.contains("..")) return false
        }
        return true
    }

    fun isSafeIncomingUri(uri: Uri): Boolean {
        val str = try { uri.toString() } catch (_: Exception) { "" }
        if (str.isNotBlank()) {
            return isSafeIncomingUri(str)
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "content" && scheme != "file") {
            return false
        }
        if (scheme == "file") {
            val path = uri.path ?: return false
            if (path.contains("..")) return false
        }
        return true
    }

    fun sanitizeFileName(rawName: String?, fallbackPrefix: String = "shared"): String {
        if (rawName.isNullOrBlank()) {
            return "${fallbackPrefix}_${System.currentTimeMillis()}"
        }
        val baseName = File(rawName).name.replace("..", "_").trim()
        return if (baseName.isBlank() || baseName == "." || baseName == "/") {
            "${fallbackPrefix}_${System.currentTimeMillis()}"
        } else {
            baseName
        }
    }

    fun isPathWithinDir(file: File, parentDir: File): Boolean {
        return try {
            file.canonicalPath.startsWith(parentDir.canonicalPath)
        } catch (_: Exception) {
            false
        }
    }
}
