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
        versionCode = 13
        versionName = "0.8.0"
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

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
