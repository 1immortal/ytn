// 1.12.0: what the phone and the Wear OS app say to each other over the Wearable Data Layer (paths and the small
// status snapshot). Pure Kotlin data, shared by :app and :wear so both sides always agree on the format.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.truenascompanion.wearlink"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
