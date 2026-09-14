import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Load release-signing credentials from keystore.properties (gitignored).
// File is generated locally; back up alongside release.keystore.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.gimy.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gimy.tv"
        minSdk = 28
        targetSdk = 35
        versionCode = 324
        versionName = "3.1.14"
    }

    signingConfigs {
        create("release") {
            // 缺 keystore.properties 時刻意留空，交給下面的 taskGraph 檢查直接讓建置失敗。
            // 原本這裡會退回 debug keystore，結果是 BUILD SUCCESSFUL 卻打出一個
            // 裝不上既有機器、App 內自我更新也整條斷掉的 APK——沉默的錯比失敗更貴。
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // 沒開這個的話 R8 只縮程式碼、資源照單全收。兩者本來就該成對。
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    lint {
        // release 建置不跑 lint。
        //
        // 不是懶得修：目前的 AGP 8.7.3 + Compose BOM 組合下，lint 自己會掛——
        // NullSafeMutableLiveData、RememberInComposition、FrequentlyChangingValue
        // 三個 detector 都拋 IncompatibleClassChangeError（lint 版本與 Compose lint
        // 產出的 API 對不上）。一條一條 disable 等於把 Compose 的檢查關光，
        // 開了也檢查不到東西。
        //
        // 要解開這條得升 AGP 或 Compose BOM——那是工具鏈升級，不該混在清理裡做。
        checkReleaseBuilds = false
    }
}

// 沒有簽章憑證就不該打包 release。檢查放在 taskGraph（執行前、設定後）而不是設定期，
// 新 clone 才能照常跑 assembleDebug、跑測試、做 IDE 同步，只有真的要打 release 時才擋下來。
gradle.taskGraph.whenReady {
    val packagingRelease = allTasks.any { task ->
        task.name.contains("Release") &&
            listOf("assemble", "bundle", "package", "install").any { task.name.startsWith(it) }
    }
    if (packagingRelease && !keystorePropertiesFile.exists()) {
        throw GradleException(
            "找不到 keystore.properties，無法簽 release。" +
                "請從備份還原 keystore.properties 與 release.keystore 再重試。"
        )
    }
}

// Room 的 schema JSON 輸出位置。開了 exportSchema 卻不指定這個會出編譯警告，
// 而且 MigrationTestHelper 要靠這些檔案才跑得起來，所以 app/schemas 要進版控。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)

    // Compose
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // Compose for TV
    // tv-foundation 零引用（Google 也已停更），只留 tv-material3
    implementation(libs.tv.material)

    // Media3 ExoPlayer
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.datasource.okhttp)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // Navigation
    implementation(libs.navigation.compose)

    // Coil
    implementation(libs.coil.compose)

    // Network / Scraping
    implementation(libs.jsoup)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)

    // Coroutines
    implementation(libs.coroutines.android)

    // Lifecycle
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // Activity
    implementation(libs.activity.compose)

    // Window Size Class
    implementation(libs.material3.window.size)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    // android.jar 裡的 org.json 是會丟 "not mocked" 的 stub，JSON 解析的測試需要真實作。
    testImplementation("org.json:json:20240303")
}
