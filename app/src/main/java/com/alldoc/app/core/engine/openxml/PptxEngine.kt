package com.alldoc.app.core.engine.openxml

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import com.alldoc.app.core.model.PresentationDocumentModel
import com.alldoc.app.core.model.PresentationSlide
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object PptxEngine {

    fun parse(inputStream: InputStream, uriString: String): PresentationDocumentModel {
        val slideBytesMap = mutableMapOf<Int, ByteArray>()
        val notesBytesMap = mutableMapOf<Int, ByteArray>()

        val zipIn = ZipInputStream(inputStream)
        var entry: ZipEntry? = zipIn.nextEntry

        val slideRegex = Regex("""ppt/slides/slide([0-9]+)\.xml""")
        val notesRegex = Regex("""ppt/notesSlides/notesSlide([0-9]+)\.xml""")

        while (entry != null) {
            val name = entry.name
            val slideMatch = slideRegex.matchEntire(name)
            val notesMatch = notesRegex.matchEntire(name)

            if (slideMatch != null) {
                val num = slideMatch.groupValues[1].toInt()
                val out = ByteArrayOutputStream()
                zipIn.copyTo(out)
                slideBytesMap[num] = out.toByteArray()
            } else if (notesMatch != null) {
                val num = notesMatch.groupValues[1].toInt()
                val out = ByteArrayOutputStream()
                zipIn.copyTo(out)
                notesBytesMap[num] = out.toByteArray()
            }

            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }

        val sortedSlideNums = slideBytesMap.keys.sorted()
        val slides = mutableListOf<PresentationSlide>()

        for (num in sortedSlideNums) {
            val bytes = slideBytesMap[num] ?: continue
            val (title, textBlocks) = parseSlideXml(bytes)
            val notes = notesBytesMap[num]?.let { parseNotesXml(it) } ?: ""

            slides.add(
                PresentationSlide(
                    slideNumber = num,
                    title = title,
                    textBlocks = textBlocks,
                    notes = notes
                )
            )
        }

        return PresentationDocumentModel(
            uriString = uriString,
            slides = slides.ifEmpty { listOf(PresentationSlide(slideNumber = 1, title = "Empty Presentation")) }
        )
    }

    private fun parseSlideXml(bytes: ByteArray): Pair<String, List<String>> {
        var title = ""
        val textBlocks = mutableListOf<String>()

        val parser = XmlParserUtil.createPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")

        var eventType = parser.eventType
        val currentParagraph = StringBuilder()

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = (parser.name ?: "").substringAfterLast(':')
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (name == "t") {
                        currentParagraph.append(parser.nextText())
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (name == "p") {
                        val text = currentParagraph.toString().trim()
                        if (text.isNotEmpty()) {
                            if (title.isEmpty()) {
                                title = text
                            } else {
                                textBlocks.add(text)
                            }
                        }
                        currentParagraph.clear()
                    }
                }
            }
            eventType = parser.next()
        }

        return Pair(title, textBlocks)
    }

    private fun parseNotesXml(bytes: ByteArray): String {
        val sb = StringBuilder()
        val parser = XmlParserUtil.createPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "t") {
                sb.append(parser.nextText()).append("\n")
            }
            eventType = parser.next()
        }
        return sb.toString().trim()
    }
}
