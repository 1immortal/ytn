import java.util.Properties

// 1.12.0: YTN for Wear OS. It shows what the phone app knows (status tile, complication, alerts) and asks the phone to
// refresh, wake the NAS or show an acknowledge confirmation. It never talks to the NAS and never holds credentials.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.truenascompanion.wear"
    compileSdk = 37

    defaultConfig {
        // Same application id and signing key as the phone app: the Wearable Data Layer only connects apps whose
        // package name and certificate match.
        applicationId = "app.truenascompanion"
        minSdk = 30
        targetSdk = 37
        versionCode = 32
        versionName = "1.12.0"
    }

    val keystorePropsFile = (findProperty("keystoreProperties") as String?)?.let { file(it) } ?: file("/home/box/secure/keystore.properties")
    val keystoreProps: Properties? = if (keystorePropsFile.isFile) Properties().also { p -> keystorePropsFile.inputStream().use { p.load(it) } } else null
    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile")!!)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Pairs with the phone's debug build (app.truenascompanion.debug); not debuggable, like the phone preview.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(project(":wearlink"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.tiles)
    implementation(libs.concurrent.futures)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.material)
    implementation(libs.wear.protolayout.expression)
    implementation(libs.wear.complications.data.source)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    if (!project.hasProperty("screenshots")) {
        exclude("**/screenshots/**")
    } else {
        systemProperty("roborazzi.test.record", "true")
        systemProperty("screenshot.dir", rootProject.layout.projectDirectory.dir("screenshots").asFile.absolutePath)
        maxHeapSize = "2g"
    }
}
