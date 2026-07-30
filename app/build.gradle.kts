plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.foldmessenger.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.foldmessenger.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.6.0"
    }

    // Versioned APK filename, e.g. FoldMessenger-v1.6.0.apk
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "FoldMessenger-v${defaultConfig.versionName}.apk"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
