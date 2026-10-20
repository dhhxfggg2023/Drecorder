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

    // Editing: trim/concat without re-encoding, and the Composition model that both
    // the exporter and the preview player consume (DESIGN §12.1).
    // `api`, not `implementation`: EditComposition.build() returns a media3
    // Composition and EditExporter accepts one, so callers in :app need the
    // media3-transformer types on their own compile classpath.
    api("androidx.media3:media3-transformer:1.11.1")
}
