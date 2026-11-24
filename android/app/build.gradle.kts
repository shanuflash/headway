import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.flash.headway"
    // Material3 1.5 (Expressive) needs compileSdk 37; targetSdk stays 36, so runtime behaviour is unchanged.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.flash.headway"
        minSdk = 26
        targetSdk = 36
        // CI run number, so each build is a newer version than the last.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // One fixed key, committed on purpose: CI otherwise signs every build with a fresh random
    // key, and Android refuses to update an app whose signature changed. Release builds use it
    // too, so they install over the earlier debug builds without losing saved routes.
    signingConfigs {
        getByName("debug") {
            storeFile = file("headway-debug.keystore")
            storePassword = "android"
            keyAlias = "headway"
            keyPassword = "android"
        }
    }

    buildTypes {
        // What CI ships: not debuggable and shrunk by R8, so Compose runs at full speed.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}


kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    implementation("androidx.compose.ui:ui:1.12.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.12.0")
    implementation("androidx.compose.foundation:foundation:1.12.0")
    implementation("androidx.compose.animation:animation:1.12.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.material3:material3:1.5.0")
    debugImplementation("androidx.compose.ui:ui-tooling:1.12.0")

    implementation("androidx.datastore:datastore-preferences:1.2.1")
    // Refreshes the advisory feed in the background, so the widget and countdown stay current.
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // Google code scanner: scans the card's QR in a Play services UI, no camera permission.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    testImplementation("junit:junit:4.13.2")
}
