import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Upload/release key for Google Play. keystore.properties is git-ignored; see docs/android-companion.md.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "io.github.caesarde.syriamaps"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.caesarde.syriamaps"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Default API server; users can change it in the app. Override: -PsyriaMapsServer=https://…
        val server = (project.findProperty("syriaMapsServer") as String?) ?: "https://maps.example.org"
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$server\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Reports and edits from test builds go to the OSM development server, never to the real map.
            buildConfigField("String", "OSM_API", "\"https://master.apis.dev.openstreetmap.org\"")
            buildConfigField("String", "OSM_WEB", "\"https://master.apis.dev.openstreetmap.org\"")
            buildConfigField("String", "OSM_CLIENT_ID", "\"${project.findProperty("osmDevClientId") ?: ""}\"")
        }
        release {
            buildConfigField("String", "OSM_API", "\"https://api.openstreetmap.org\"")
            buildConfigField("String", "OSM_WEB", "\"https://www.openstreetmap.org\"")
            // OAuth2 app registered on openstreetmap.org (public client). Empty = direct editing hidden.
            buildConfigField("String", "OSM_CLIENT_ID", "\"${project.findProperty("osmClientId") ?: ""}\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without keystore.properties the release build falls back to the debug key (installable, not uploadable).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":osmand-aidl"))

    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (Android's copy is stubbed there).
    testImplementation("org.json:json:20240303")
}
