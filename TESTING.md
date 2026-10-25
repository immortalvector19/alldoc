# Testing Guide for AllDoc (Windows)

This document provides the exact commands and steps to execute unit and on-device instrumented tests for AllDoc on Windows.

---

## 1. Unit Tests (JVM)

Unit tests run locally on your development machine via JUnit 4, Kotlinx Coroutines Test, and AndroidX Core Testing (`InstantTaskExecutorRule`) without requiring a physical device or emulator.

### Run all unit tests:
```powershell
.\gradlew.bat testDebugUnitTest
```

### Run the real-world format matrix test:
Covers all supported formats (PDF, password PDF, DOCX with images, DOC, XLS, multi-sheet XLSX, inline strings, PPTX, PPT, CSV, MD, RTF, TXT, images):
```powershell
.\gradlew.bat testDebugUnitTest --tests "com.alldoc.app.core.engine.SampleFilesTest"
```

### View test report:
The HTML test report is located at:
```text
app\build\reports\tests\testDebugUnitTest\index.html
```
To open it directly in your browser:
```powershell
Start-Process app\build\reports\tests\testDebugUnitTest\index.html
```

---

## 2. On-Device Instrumented Tests (Android Device)

Instrumented tests execute directly on ART runtime on a real connected phone or running emulator.

### Step 1: Enable USB Debugging on Your Android Phone
1. Open **Settings** > **About phone**.
2. Tap **Build number** 7 times until you see the message *"You are now a developer!"*.
3. Go back to **Settings** > **System** > **Developer options** (or **Additional settings** > **Developer options** depending on OEM).
4. Turn ON **USB debugging**.

### Step 2: Connect Phone to Windows PC via USB
1. Plug the phone into your Windows PC with a data-capable USB cable.
2. On your phone screen, an authorization prompt will appear: **"Allow USB debugging?"**.
3. Check **"Always allow from this computer"** and tap **Allow**.

### Step 3: Verify Device Connection via ADB
Open PowerShell in the project directory and run:
```powershell
adb devices
```
Expected output:
```text
List of devices attached
988a1b2c3d4e5f    device
```
*(If it shows `unauthorized`, unlock your phone and accept the prompt. If it shows `offline` or empty, ensure OEM USB drivers are installed).*

### Step 4: Run All Instrumented Tests
Execute the full instrumented test suite across your connected device:
```powershell
.\gradlew.bat connectedDebugAndroidTest
```

### Step 5: Run Specific Test Classes
To run individual test classes:

- **Cold-Start Intent Verification** (proves WhatsApp, Gmail, and external "Open with" intents navigate directly to document viewer):
  ```powershell
  .\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.alldoc.app.ColdStartIntentTest
  ```

- **Document Viewer Flow Test** (verifies dirty-exit unsaved changes prompt and read-only save blocking on the UI thread):
  ```powershell
  .\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.alldoc.app.DocumentViewerFlowInstrumentedTest
  ```

### Step 6: View the Test Report
Upon test completion, Gradle generates a comprehensive HTML report:
```text
app\build\reports\androidTests\connected\index.html
```
Open the report in your browser:
```powershell
Start-Process app\build\reports\androidTests\connected\index.html
```
*(If per-variant directories are generated: `app\build\reports\androidTests\connected\debug\index.html`)*.

---

## 3. Manual Cold-Start Verification from WhatsApp / Files

To manually test cold-start "Open with" without ADB:
1. Build and install the fresh debug APK:
   ```powershell
   .\gradlew.bat installDebug
   ```
2. Force-stop AllDoc so it is completely unloaded from memory:
   ```powershell
   adb shell am force-stop com.alldoc.app
   ```
3. Open WhatsApp or your file manager, tap any supported document (`.pdf`, `.docx`, `.xlsx`, `.pptx`, `.csv`, `.md`, `.txt`, `.rtf`).
4. Select **AllDoc** from the "Open with" chooser.
5. AllDoc launches from a cold state, immediately loads the document, and displays the content viewer without dropping the intent or showing a blank screen.
