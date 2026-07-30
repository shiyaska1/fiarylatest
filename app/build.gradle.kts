plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.billing.pos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.billing.pos"
        minSdk = 26
        targetSdk = 36
        versionCode = 136
        versionName = "1.60.3"
        vectorDrawables { useSupportLibrary = true }

        // Real Android phones are arm. The x86/x86_64 native libs are emulator-only
        // and cost ~42 MB of APK for the two ML Kit engines. Ship arm only.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Play Store upload key — provided by CI via env vars (kept out of git). Falls back to the
    // committed "stable" key for local/debug builds so a plain ./gradlew still works.
    val uploadStoreFile = System.getenv("UPLOAD_STORE_FILE")
    val uploadStorePassword = System.getenv("UPLOAD_STORE_PASSWORD")
    val uploadKeyAlias = System.getenv("UPLOAD_KEY_ALIAS")
    val uploadKeyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
    val hasUploadKey = !uploadStoreFile.isNullOrBlank() && !uploadStorePassword.isNullOrBlank()

    signingConfigs {
        create("stable") {
            storeFile = file("keystore.jks")
            storePassword = "poskey123"
            keyAlias = "posbilling"
            keyPassword = "poskey123"
        }
        if (hasUploadKey) {
            create("upload") {
                storeFile = file(uploadStoreFile!!)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Sign with the committed stable key so customers can update without data loss.
            signingConfig = signingConfigs.getByName("stable")
            // CI ships this variant, so shrink it: strips unused code (material-icons-extended
            // alone bundles thousands of unreferenced icons) and unused resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Use the Play upload key when CI provides it, else the local stable key.
            signingConfig = signingConfigs.getByName(if (hasUploadKey) "upload" else "stable")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // Keep the Tesseract model uncompressed in the APK. A DEFLATED asset cannot be
        // opened with openFd(), and a compressed 12 MB model also costs a slow inflate
        // on the first scan.
        noCompress += "traineddata"
        noCompress += "tflite"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Store .so uncompressed and page-aligned — required for 16KB page devices
            // (and what Google Play checks for).
            useLegacyPackaging = false
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // App lock via the phone's own fingerprint / PIN / pattern
    implementation("androidx.biometric:biometric:1.1.0")

    // Room (local offline database)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Barcode scanning (camera) + generation
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")

    // On-device handwriting recognition (offline after one-time model download).
    // 19.0.0+ ships 16KB-page-aligned native libs, which Google Play now requires.
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")

    // On-device text recognition / OCR (bundled Latin model — fully offline)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Play in-app updates: prompt for a new version as soon as one is published.
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    // Tiny embedded HTTP server for offline LAN sync between two devices on the same WiFi.
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // On-device image embeddings, for "find this item by photo" in price search.
    implementation("com.google.mediapipe:tasks-vision:0.10.35")

    // Malayalam OCR. ML Kit cannot read Malayalam script at all, so Tesseract fills that
    // gap — still fully offline (the model ships in assets/tessdata).
    // 4.8.0+ ships 16 KB-page-aligned native libs, which Google Play requires.
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    // EXIF rotation, so a sideways photo still OCRs.
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
