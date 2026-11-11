plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("dev.rikka.tools.refine")   // подменяет заглушки скрытых классов (ActivityManagerHidden и др.) на настоящие
}

// Совместимость с Shizuku (ADB-phon 7.0): исходники сервера и API Shizuku (RikkaApps, Apache-2.0), см. licenses/.
// Менеджером выступает сама программа (MANAGER_APPLICATION_ID = app.adbphon).
android {
    namespace = "app.adbphon.shizuku"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures {
        buildConfig = false
        aidl = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.annotation:annotation:1.8.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.code.gson:gson:2.11.0")
    api("dev.rikka.rikkax.parcelablelist:parcelablelist:2.0.1")
    implementation("dev.rikka.hidden:compat:4.4.0")
    implementation("dev.rikka.tools.refine:runtime:4.4.0")
    compileOnly("dev.rikka.hidden:stub:4.4.0")
}
