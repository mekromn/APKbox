plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val apkboxSigningKeystore = System.getenv("APKBOX_KEYSTORE")

android {
    namespace = "com.mekromn.apkbox"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mekromn.apkbox"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "0.9.0"
    }

    signingConfigs {
        getByName("debug") {
            if (!apkboxSigningKeystore.isNullOrBlank()) {
                storeFile = rootProject.file(apkboxSigningKeystore)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Verified stable BOM published for the Android 16 / API 36 generation.
    // Compose 1.12+ moves to compileSdk 37 / AGP 9, so APKbox deliberately stays below it.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Local Wireless Debugging / ADB client. The upstream project is dual licensed;
    // APKbox consumes it under Apache-2.0. Conscrypt keeps TLSv1.3 pairing/connection
    // independent of hidden Android crypto APIs.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    // Shizuku and Sui are first-class privileged transports alongside Wireless ADB. APKbox uses a
    // UserService rather than deprecated Shizuku.newProcess so shell/root execution can support
    // streaming raw captures and verified package-install sessions without Binder-size limits.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Android's local-unit-test android.jar contains a throwing org.json stub. Use the real JVM
    // implementation so protocol round-trip tests exercise the same JSON semantics as the app.
    testImplementation("org.json:json:20250517")
}
