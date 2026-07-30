# Keep Room entities/DAO metadata (Room's generated *_Impl classes live here too)
-keep class com.billing.pos.data.** { *; }

# ViewModels are constructed reflectively by androidx's AndroidViewModelFactory
# (modelClass.getConstructor(Application::class.java)). Without this, R8 strips the
# constructor and every screen crashes on open.
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class * extends androidx.lifecycle.AndroidViewModel { <init>(...); }
-keepclassmembers class * extends androidx.lifecycle.ViewModel { <init>(...); }

# ML Kit loads its models and native pipelines reflectively.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.odml.** { *; }
-dontwarn com.google.mlkit.**

# ZXing barcode scanning/generation
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**

# Coroutines
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# Tesseract (Malayalam OCR). The native layer looks these classes and their fields up by
# name through JNI, so renaming or stripping them breaks recognition at runtime.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
-dontwarn com.googlecode.tesseract.android.**
-dontwarn com.googlecode.leptonica.android.**
