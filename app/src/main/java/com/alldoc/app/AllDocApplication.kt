package com.alldoc.app

import android.app.Application
import android.util.Log
import com.alldoc.app.core.data.CacheManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class AllDocApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize PDFBox resource loader safely with Throwable guard
        try {
            PDFBoxResourceLoader.init(applicationContext)
            CacheManager.pruneCache(applicationContext)
        } catch (t: Throwable) {
            Log.w("AllDocApplication", "PDFBoxResourceLoader init deferred or failed: ${t.message}")
        }
    }
}
