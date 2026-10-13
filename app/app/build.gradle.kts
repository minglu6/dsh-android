import java.time.Duration

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.github.takahirom.roborazzi")
}

val releaseStoreFile = providers.environmentVariable("DSH_ANDROID_KEYSTORE_FILE")
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD")
    .orElse(providers.environmentVariable("DSH_ANDROID_KEYSTORE_PASSWORD"))
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS")
    .orElse(providers.environmentVariable("DSH_ANDROID_KEY_ALIAS"))
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD")
    .orElse(providers.environmentVariable("DSH_ANDROID_KEY_PASSWORD"))
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it.isPresent && it.get().isNotBlank() }

val appVersionName = "0.1.0-m3"

android {
    namespace = "dev.dsh.pocket.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.dsh.pocket"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "APP_VERSION", "\"$appVersionName\"")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests { isIncludeAndroidResources = true } }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/{AL2.0,LGPL2.1}") } }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Fall back to the debug keystore when CI secrets are absent so PRs stay green.
            signingConfig = if (releaseSigningConfigured) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}
dependencies {
    implementation(project(":core:protocol"))
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    // Pure-JVM GFM markdown (tables) — no GMS / WebView.
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    // Bundled ML Kit barcode model — works on Chinese ROMs without Google Play services.
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // Host-provided plugin data-URI artwork; no WebView or network image loader.
    implementation("com.caverock:androidsvg-aar:1.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.39.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.39.0")
    testImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    // Idle regressions used to look like a 40-minute hang (60s Espresso policy × dozens of tests).
    // Keep the suite bounded so a future spinner/clock leak fails the job in minutes.
    timeout.set(Duration.ofMinutes(12))
    // Robolectric NATIVE Skia/Choreographer state leaks across classes in one JVM and
    // makes later Compose waitForIdle spin until Espresso times out. A new worker
    // every few methods keeps that leak from accumulating across the suite.
    forkEvery = 25
}
