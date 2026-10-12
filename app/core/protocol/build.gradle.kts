plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}
kotlin { jvmToolchain(17) }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
tasks.test {
    useJUnitPlatform()
    environment("DSH_POCKET_URL", System.getenv("DSH_POCKET_URL") ?: "")
    environment("DSH_POCKET_TOKEN", System.getenv("DSH_POCKET_TOKEN") ?: "")
    environment("E2E_FRAMES", System.getenv("E2E_FRAMES") ?: "")
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
