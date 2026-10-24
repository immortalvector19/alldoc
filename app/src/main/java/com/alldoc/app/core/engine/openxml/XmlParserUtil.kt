package com.alldoc.app.core.engine.openxml

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

object XmlParserUtil {

    fun createPullParser(): XmlPullParser {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            if (factory != null) {
                factory.isNamespaceAware = false
                val parser = factory.newPullParser()
                if (parser != null) return parser
            }
            createFallbackParser()
        } catch (_: Throwable) {
            createFallbackParser()
        }
    }

    private fun createFallbackParser(): XmlPullParser {
        return try {
            val clazz = Class.forName("org.kxml2.io.KXmlParser")
            clazz.getDeclaredConstructor().newInstance() as XmlPullParser
        } catch (e: Exception) {
            throw IllegalStateException("No XmlPullParser available", e)
        }
    }
}
