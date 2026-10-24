package com.alldoc.app.core.engine.text

import com.alldoc.app.core.model.DocumentFormat
import com.alldoc.app.core.model.TextDocumentModel
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.Charset

object TextEngine {

    fun parse(inputStream: InputStream, uriString: String, format: DocumentFormat): TextDocumentModel {
        val rawBytes = inputStream.readBytes()
        val encoding = detectCharset(rawBytes)
        val text = if (format == DocumentFormat.RTF) {
            stripRtf(String(rawBytes, encoding))
        } else {
            String(rawBytes, encoding)
        }

        return TextDocumentModel(
            uriString = uriString,
            format = format,
            text = text
        )
    }

    fun save(model: TextDocumentModel, outputStream: OutputStream) {
        val writer = OutputStreamWriter(outputStream, Charsets.UTF_8)
        writer.write(model.text)
        writer.flush()
    }

    private fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Charsets.UTF_8
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Charsets.UTF_16BE
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Charsets.UTF_16LE
        }
        return Charsets.UTF_8
    }

    private fun stripRtf(rtf: String): String {
        // Strip control words (\b, \par, \tab, etc.)
        val clean = rtf
            .replace(Regex("""\{\*?\\[^{}]+;?\}"""), "") // Remove fonttbl, colortbl groups
            .replace(Regex("""\\[a-z]{1,32}(-?\d{1,10})?[ ]?"""), " ") // Strip control words
            .replace("{", "")
            .replace("}", "")
            .replace("\\par", "\n")
            .replace("\\tab", "\t")
            .trim()
        return clean
    }
}
