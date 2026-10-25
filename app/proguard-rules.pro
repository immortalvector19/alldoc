# AllDoc R8 / ProGuard Optimization Rules

# Keep Room generated code
-keep class androidx.room.RoomDatabase { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep ML Kit Document Scanner & Vision models
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.vision.** { *; }
-dontwarn com.google.mlkit.**

# Keep PDFBox Android
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-keep class com.tomroush.** { *; }
-dontwarn com.tomroush.**

# Keep Kotlinx Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }

# Keep Domain & State models for Room, JSON and serialization
-keep class com.alldoc.app.data.local.entity.** { *; }
-keep class com.alldoc.app.core.model.** { *; }
