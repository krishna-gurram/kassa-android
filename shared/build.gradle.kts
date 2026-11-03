plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// What the iOS app calls: :domain and :importer behind one static framework, KassaShared. The iOS targets only exist on a
// Mac; on Linux this module has just the jvm() target (for its test), like the others.
kotlin {
    jvmToolchain(17)
    jvm()
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework {
                baseName = "KassaShared"
                isStatic = true
                export(project(":domain"))
                export(project(":importer"))
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            api(project(":importer"))
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
