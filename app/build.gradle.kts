plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Ключ подписи хранится только в GitHub Secrets. Локально можно задать те же
// переменные окружения и указать SIGNING_KEYSTORE_PATH на файл вне репозитория.
// Без них release-сборка получается неподписанной (например, в PR из форка).
val signingStorePassword = System.getenv("SIGNING_STORE_PASSWORD")
val signingKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")
val signingKeyAlias = System.getenv("SIGNING_KEY_ALIAS")

android {
    namespace = "app.rkn.vpn"
    compileSdk = 35

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("SIGNING_KEYSTORE_PATH") ?: "release.keystore")
            storePassword = signingStorePassword.orEmpty()
            keyAlias = signingKeyAlias.orEmpty()
            keyPassword = signingKeyPassword.orEmpty()
        }
    }

    defaultConfig {
        applicationId = "app.rkn.vpn"
        minSdk = 24
        targetSdk = 35
        versionCode = 27
        versionName = "1.0.26"

        // Репозиторий для проверки обновлений через GitHub Releases API.
        buildConfigField("String", "UPDATE_REPO", "\"leo0797/RKN-VPN\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Подписывается только когда ключ реально передан в окружении.
            if (!signingStorePassword.isNullOrBlank() && !signingKeyPassword.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    splits {
        abi {
            // Эмуляторы в CI работают на x86, для них нужен один APK со всеми
            // ABI. Релизы по-прежнему собираются с двумя split-APK.
            isEnable = providers.gradleProperty("rkn.splits.enabled")
                .map { it.toBoolean() }.getOrElse(true)
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
        // В CI lint обязателен: ошибки не должны попадать в релиз. Локально
        // оставляем режим предупреждений, чтобы не блокировать быстрые сборки.
        abortOnError = System.getenv("GITHUB_ACTIONS") == "true"
        checkReleaseBuilds = true
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
    implementation("androidx.work:work-runtime-ktx:2.10.5")
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

    // Эмуляторный smoke-тест для API 24–28: проверяет, что overrideLibrary
    // для hev-socks5-tunnel не ломает запуск на старых устройствах.
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
