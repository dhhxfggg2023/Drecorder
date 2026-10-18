plugins {
    id("com.android.library")
}

android {
    namespace = "dev.drecorder.audio"
    compileSdk = 37
    buildToolsVersion = "36.1.0"

    defaultConfig {
        minSdk = 29
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
}
