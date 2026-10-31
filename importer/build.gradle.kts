plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":domain"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

// Tests that format or read money assume the US style unless one names its own (Money.withLocale).
tasks.test {
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
}
