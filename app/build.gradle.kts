plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Commit count on main: always increases with history, so a newer build can always update an older one.
val buildNumber = System.getenv("KASSA_BUILD")?.toIntOrNull() ?: 1
val keystorePath: String? = System.getenv("KASSA_KEYSTORE")

android {
    namespace = "app.kassa.money"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.kassa.money"
        minSdk = 29
        targetSdk = 36
        versionCode = 100 + buildNumber
        versionName = "0.2.$buildNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Where feedback emails go. Set in CI; empty means the user types the address.
        buildConfigField("String", "SUPPORT_EMAIL", "\"${System.getenv("KASSA_SUPPORT_EMAIL") ?: ""}\"")
    }

    // Migration tests build old databases from these exported schemas.
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KASSA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KASSA_KEY_ALIAS")
                keyPassword = System.getenv("KASSA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // A debug build installs beside the real app, so tests on a phone or emulator never touch its data.
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // R8 strips the thousands of unused icons and shrinks the APK; see proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // CI signs with the stable release key so updates install over each other.
            // A local build without the key falls back to the debug key.
            signingConfig = signingConfigs.getByName(if (keystorePath != null) "release" else "debug")
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

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":importer"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite)

    implementation(libs.coroutines.android)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.org.json)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.kotlin.test)
}
