plugins {
    id("com.android.library")
}

android {
    namespace = "dev.drecorder.data"
    compileSdk = 37
    buildToolsVersion = "36.1.0"

    defaultConfig {
        minSdk = 29
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
    // android.jar ships org.json as an unimplemented stub, so unit tests that parse
    // JSON need the real implementation on the classpath.
    testImplementation("org.json:json:20250107")
}
