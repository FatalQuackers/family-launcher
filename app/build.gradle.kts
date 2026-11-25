plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.fatalquackers.familylauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fatalquackers.familylauncher"
        minSdk = 28
        targetSdk = 34
        versionCode = run
        versionName = "1.0.$run"
    }

    signingConfigs {
        create("family") {
            storeFile = file("family.keystore")
            storePassword = "familylauncher"
            keyAlias = "family"
            keyPassword = "familylauncher"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("family")
        }
        debug {
            signingConfig = signingConfigs.getByName("family")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
