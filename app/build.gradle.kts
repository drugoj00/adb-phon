import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.adbphon"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.adbphon"
        minSdk = 26
        targetSdk = 34
        versionCode = 38
        versionName = "6.0"
        // оставляем только языки программы (ru, uk, en): строки библиотек на других языках не нужны
        resourceConfigurations += listOf("ru", "uk", "en")
    }

    // ключ подписи лежит в keystore/ (не публикуется); без него release собирается неподписанным
    val ksFile = rootProject.file("keystore/keystore.properties")
    val ks = Properties().apply { if (ksFile.exists()) ksFile.inputStream().use { load(it) } }
    signingConfigs {
        if (ksFile.exists()) create("release") {
            storeFile = rootProject.file("keystore/adbphon-release.jks")
            storePassword = ks.getProperty("storePassword")
            keyAlias = ks.getProperty("keyAlias")
            keyPassword = ks.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            // R8: сжатие кода и ресурсов (правила в proguard-rules.pro); mapping.txt и usage.txt лежат в app/build/outputs/mapping/release
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ksFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf("META-INF/*", "org/bouncycastle/pqc/**", "org/bouncycastle/x509/**", "**/*.kotlin_builtins", "kotlin/**", "DebugProbesKt.bin")
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.github.mwiede:jsch:0.2.20")   // SSH: терминал и файлы на компьютерах
    testImplementation("junit:junit:4.13.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // WebDAV
    implementation("androidx.media3:media3-exoplayer:1.4.1")   // встроенный плеер видео и аудио (только системные кодеки)
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("org.tukaani:xz:1.10")
    implementation("com.github.junrar:junrar:7.5.5")
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10") { exclude(group = "org.bouncycastle") }
}
