plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

import java.util.Properties
import java.io.FileInputStream

android {
    namespace = "com.ksjd.testem"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ksjd.testem"
        minSdk = 24
        targetSdk = 36
        versionCode = 9
        versionName = "2.4.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // emhub endpoint and app key: the private repo's secrets/hub.properties (outside this
        // public folder), else local.properties. Without a key, bug reports and community are off.
        val local = Properties().apply {
            listOf(rootProject.file("../secrets/hub.properties"), rootProject.file("local.properties"))
                .firstOrNull { it.exists() && it.readText().contains("hub.appKey") }
                ?.inputStream()?.use { load(it) }
        }
        buildConfigField("String", "HUB_URL", "\"${local.getProperty("hub.url", "https://emhub.karafa.network")}\"")
        buildConfigField("String", "HUB_APP_KEY", "\"${local.getProperty("hub.appKey", "")}\"")
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                val storeFilePath = (keystoreProperties["storeFile"] as String?)?.trim().orEmpty()
                val resolvedStoreFile = when {
                    storeFilePath.isBlank() -> rootProject.file("app/testEM-release.keystore")
                    storeFilePath.endsWith(".properties", ignoreCase = true) -> {
                        rootProject.file("app/testEM-release.keystore")
                    }
                    else -> rootProject.file(storeFilePath)
                }
                storeFile = resolvedStoreFile
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.4.0"))
    implementation("com.google.firebase:firebase-analytics")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Lifecycle / ViewModel
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.11.0")

    // HTML parsing
    implementation("org.jsoup:jsoup:1.18.1")

    // QR Code generation
    implementation("com.google.zxing:core:3.5.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
