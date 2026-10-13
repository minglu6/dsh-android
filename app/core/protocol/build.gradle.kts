plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}
kotlin { jvmToolchain(17) }

// Shared language-neutral contract fixtures (also consumed by plugin node tests).
sourceSets {
    test {
        resources.srcDir("${rootProject.projectDir}/../contract")
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
tasks.test {
    useJUnitPlatform()
    environment("DSH_POCKET_URL", System.getenv("DSH_POCKET_URL") ?: "")
    environment("DSH_POCKET_TOKEN", System.getenv("DSH_POCKET_TOKEN") ?: "")
    environment("E2E_FRAMES", System.getenv("E2E_FRAMES") ?: "")
    environment("DSH_E2E_PROMPT", System.getenv("DSH_E2E_PROMPT") ?: "")
    environment("E2E_FRAMES_OUT", System.getenv("E2E_FRAMES_OUT") ?: "")
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
