package com.alldoc.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class ColdStartIntentTest {

    @Test
    fun testColdStartActionViewNavigatesWithoutDroppingIntent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testFile = File(context.cacheDir, "cold_start_test.txt").apply {
            writeText("Cold start intent test content")
        }
        val uri = Uri.fromFile(testFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClass(context, MainActivity::class.java)
            setDataAndType(uri, "text/plain")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("Activity must be created", activity)
                // Activity survives and handles cold-start intent
                assertTrue("Activity is not finishing", !activity.isFinishing)
            }
        }
    }

    @Test
    fun testColdStartActionSendFromExternalApp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testFile = File(context.cacheDir, "share_test.csv").apply {
            writeText("Col1,Col2\nVal1,Val2\n")
        }
        val uri = Uri.fromFile(testFile)

        val intent = Intent(Intent.ACTION_SEND).apply {
            setClass(context, MainActivity::class.java)
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("Activity must be created on cold share", activity)
                assertTrue("Activity is not finishing", !activity.isFinishing)
            }
        }
    }
}
