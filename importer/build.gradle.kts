plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Imports from other apps, plain Kotlin shared with the iOS app (see :domain for the iOS targets).
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
            implementation(project(":domain"))
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
