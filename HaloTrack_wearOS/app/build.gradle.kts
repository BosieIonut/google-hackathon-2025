plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.old_pers"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.old_pers"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.play.services.wearable)

    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.compose.material)
    implementation(libs.compose.foundation)
    implementation(libs.wear.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.splashscreen)
    implementation(libs.appcompat)
    implementation(libs.media3.common.ktx)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.compose.material:material-icons-core:...") // Use the latest version compatible with your Compose version
    implementation("androidx.compose.material:material-icons-extended:...") // Optional, for more icons
    // Wear Compose Material
    implementation("androidx.wear.compose:compose-material:...")
    implementation("androidx.wear.compose:compose-material:...") // Use your version
    implementation("androidx.wear.compose:compose-foundation:...") // Use your version

// Add this line for the standard Material components like Icon, Text, etc.
    implementation("androidx.compose.material:material:...") // <-- ADD THIS LINE (Use compatible version)

// You need this for the actual icons (like Icons.Filled.Check)
    implementation("androidx.compose.material:material-icons-core:...") // <-- Ensure this is present


}