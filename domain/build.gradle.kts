plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// The rules are plain Kotlin shared by the Android app and the iOS app. The iOS targets only exist on a Mac (that is
// where Xcode and Kotlin/Native's iOS toolchain are); Android builds on Linux use the jvm() target.
kotlin {
    jvmToolchain(17)
    jvm()
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        iosArm64()
        iosSimulatorArm64()
        iosX64()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.datetime)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlin.test)
        }
    }
}

// Tests that format or read money assume the US style unless one names its own (Money.withStyle).
tasks.withType<Test>().configureEach {
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
}
