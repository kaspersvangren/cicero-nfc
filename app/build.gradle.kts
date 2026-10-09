plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dk.kasvan.ciceronfc"
    compileSdk = 35

    defaultConfig {
        applicationId = "dk.kasvan.ciceronfc"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0-lokal"
    }

    // Fast nøgle, så nye versioner kan installeres oven på de gamle
    // uden at Cicero-indstillinger og login i app'en går tabt.
    signingConfigs {
        create("fast") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "release.keystore")
            // Adgangskoden ligger i GitHubs hemmelige boks (KEYSTORE_PASSWORD), ikke i koden
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = "ciceronfc"
            keyPassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fast")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
