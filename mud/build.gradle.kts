// Pure Kotlin: telnet, ANSI, and the trigger/alias engine. No Android imports.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
}
