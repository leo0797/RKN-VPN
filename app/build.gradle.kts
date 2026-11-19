plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.rkn.vpn"
    compileSdk = 35

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "rknvpn2026"
            keyAlias = "rknvpn"
            keyPassword = "rknvpn2026"
        }
    }

    defaultConfig {
        applicationId = "app.rkn.vpn"
        minSdk = 24
        targetSdk = 35
        versionCode = 20
        versionName = "1.0.19"

        // Репозиторий для проверки обновлений через GitHub Releases API.
        buildConfigField("String", "UPDATE_REPO", "\"leo0797/RKN-VPN\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // Подписывается release-ключом, чтобы APK из CI сразу устанавливался.
            signingConfig = signingConfigs.getByName("release")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            // Ядро mihomo лежит в jniLibs как libmihomo.so и запускается как процесс.
            // useLegacyPackaging гарантирует распаковку .so на диск при установке.
            useLegacyPackaging = true
            keepDebugSymbols += "**/libmihomo.so"
        }
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(files("libs/hev-socks5-tunnel.aar"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // JVM-тесты парсеров (Robolectric даёт настоящие android.net.Uri и org.json).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
