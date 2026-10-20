plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.vsmobile.editor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vsmobile.editor"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // Backend base URL (server/app, see server/README.md). Point this at your
        // deployed server, or http://10.0.2.2:8000 to reach a local server from the emulator.
        buildConfigField("String", "SERVER_BASE_URL", "\"https://server-5c4l.onrender.com\"")
        // OAuth *web* client ID from Google Cloud Console — must match GOOGLE_WEB_CLIENT_ID
        // on the server. Leave empty to disable the "Войти через Google" button.
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"\"")
    }

    // Release-подпись берётся из ~/.gradle/gradle.properties (или -P), в репозиторий не попадает:
    //   VSMOBILE_STORE_FILE, VSMOBILE_STORE_PASSWORD, VSMOBILE_KEY_ALIAS, VSMOBILE_KEY_PASSWORD
    // Без этих свойств release собирается неподписанным.
    signingConfigs {
        val storePath = providers.gradleProperty("VSMOBILE_STORE_FILE").orNull
        if (storePath != null) {
            create("release") {
                storeFile = file(storePath)
                storePassword = providers.gradleProperty("VSMOBILE_STORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("VSMOBILE_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("VSMOBILE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // Emulator loopback to a server running on your dev machine (python -m app.main 8000).
            buildConfigField("String", "SERVER_BASE_URL", "\"http://10.0.2.2:8000\"")
            buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"\"")
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
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui:1.6.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.1")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("androidx.compose.material:material-icons-extended:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    debugImplementation("androidx.compose.ui:ui-tooling:1.6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    // Networking for the account/subscription/AI backend (server/app).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("app.cash.quickjs:quickjs-android:0.9.2")
    // Google Sign-In.
    implementation("com.google.android.gms:play-services-auth:21.2.0")
}
