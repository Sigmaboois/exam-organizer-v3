plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** The Gemini key comes from the desktop app's gitignored src/api_key.py, so it is never committed. */
fun geminiKey(): String {
    System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it.trim() }
    val file = rootProject.file("../src/api_key.py")
    if (!file.exists()) return ""
    val match = Regex("""GEMINI_API_KEY\s*=\s*["']([^"']*)["']""").find(file.readText())
    return match?.groupValues?.get(1)?.trim() ?: ""
}

android {
    namespace = "com.gasserelshafei.examorganizer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gasserelshafei.examorganizer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "GEMINI_API_KEY", "\"${geminiKey()}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the build machine's key so the APK can be installed directly on phones
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module")
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.systemProperty("corpus", System.getProperty("corpus") ?: "") }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Reading and writing PDFs (text for detecting papers, the marked copy)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Google's document scanner (camera, edge detection, PDF) and on-device text recognition
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.apache.pdfbox:pdfbox:2.0.32")
    testImplementation("org.json:json:20240303")
}
