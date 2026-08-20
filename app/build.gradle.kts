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
        // Overridable for one-off builds: ./gradlew assembleRelease -PfmVersionCode=8 -PfmVersionName=1.7.9
        versionCode = (project.findProperty("fmVersionCode") as String?)?.toInt() ?: 34
        versionName = (project.findProperty("fmVersionName") as String?) ?: "1.26.0"
    }

    // One shared signing key for every build (local and CI), so phones can
    // update in place. Keystore comes from the repo root locally or from the
    // FM_KEYSTORE/FM_KEYSTORE_PASSWORD env vars in GitHub Actions.
    signingConfigs {
        create("shared") {
            val ksFile = file(System.getenv("FM_KEYSTORE") ?: "${rootDir}/foldmessenger-release.keystore")
            if (ksFile.exists()) {
                val password = System.getenv("FM_KEYSTORE_PASSWORD")
                    ?: rootProject.file("keystore-password.txt").takeIf { it.exists() }?.readText()?.trim()
                storeFile = ksFile
                storePassword = password
                keyAlias = "foldmessenger"
                keyPassword = password
            }
        }
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
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // ntfy access token: lifts the anonymous rate and bandwidth limits. Read
    // from the gitignored ntfy-token.txt locally, or FM_NTFY_TOKEN in CI — it
    // must never be committed, since this repo is public.
    defaultConfig {
        val ntfyToken = System.getenv("FM_NTFY_TOKEN")
            ?: rootProject.file("ntfy-token.txt").takeIf { it.exists() }?.readText()?.trim()
            ?: ""
        buildConfigField("String", "NTFY_TOKEN", "\"$ntfyToken\"")
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
