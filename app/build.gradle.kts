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
        versionCode = 49
        versionName = "9.2"
        // РѕСЃС‚Р°РІР»СЏРµРј С‚РѕР»СЊРєРѕ СЏР·С‹РєРё РїСЂРѕРіСЂР°РјРјС‹ (ru, uk, en): СЃС‚СЂРѕРєРё Р±РёР±Р»РёРѕС‚РµРє РЅР° РґСЂСѓРіРёС… СЏР·С‹РєР°С… РЅРµ РЅСѓР¶РЅС‹
        resourceConfigurations += listOf("ru", "uk", "en")
    }

    // РєР»СЋС‡ РїРѕРґРїРёСЃРё Р»РµР¶РёС‚ РІ keystore/ (РЅРµ РїСѓР±Р»РёРєСѓРµС‚СЃСЏ); Р±РµР· РЅРµРіРѕ release СЃРѕР±РёСЂР°РµС‚СЃСЏ РЅРµРїРѕРґРїРёСЃР°РЅРЅС‹Рј
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
            // R8: СЃР¶Р°С‚РёРµ РєРѕРґР° Рё СЂРµСЃСѓСЂСЃРѕРІ (РїСЂР°РІРёР»Р° РІ proguard-rules.pro); mapping.txt Рё usage.txt Р»РµР¶Р°С‚ РІ app/build/outputs/mapping/release
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

// :shizuku (зависимость Shizuku) тянет androidx.core 1.16, ему нужен compileSdk 35: держим прежнюю версию
configurations.all { resolutionStrategy.force("androidx.core:core:1.13.1", "androidx.core:core-ktx:1.13.1") }

dependencies {
    implementation(project(":shizuku"))   // 7.0: сервер и API Shizuku (Apache-2.0)
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
    implementation("com.github.mwiede:jsch:0.2.20")   // SSH: С‚РµСЂРјРёРЅР°Р» Рё С„Р°Р№Р»С‹ РЅР° РєРѕРјРїСЊСЋС‚РµСЂР°С…
    testImplementation("junit:junit:4.13.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // WebDAV
    implementation("androidx.media3:media3-exoplayer:1.4.1")   // РІСЃС‚СЂРѕРµРЅРЅС‹Р№ РїР»РµРµСЂ РІРёРґРµРѕ Рё Р°СѓРґРёРѕ (С‚РѕР»СЊРєРѕ СЃРёСЃС‚РµРјРЅС‹Рµ РєРѕРґРµРєРё)
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("org.tukaani:xz:1.10")
    implementation("com.github.junrar:junrar:7.5.5")
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10") { exclude(group = "org.bouncycastle") }
}
