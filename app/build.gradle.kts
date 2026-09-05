plugins {
    id("com.android.application")
}

android {
    namespace = "cd.fool.adbport"
    compileSdk = 35

    defaultConfig {
        applicationId = "cd.fool.adbport"
        minSdk = 26
        targetSdk = 35

        // 只打包 arm64 架构的 native 库，其余架构（armeabi-v7a/x86/x86_64）不打进 apk
        ndk {
            abiFilters.add("arm64-v8a")
        }

        // Dynamically accept CI properties or fall back to local defaults
        versionCode = (project.findProperty("CI_VERSION_CODE") as? String)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("CI_VERSION_NAME") as? String) ?: "1.0.0"
    }

    buildTypes {
        getByName("release") {
            // 不配置 signingConfig：CI 产出未签名包，下载后用 MT 管理器签名
            // 开启 R8 代码收缩 + 资源收缩
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 只保留中英文资源，剔除依赖库里其他语言的翻译资源
    // （AGP 9 新写法；resourceConfigurations 已废弃，上次构建日志中有明确提示）
    androidResources {
        localeFilters.addAll(listOf("zh", "en"))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // 不在 APK 中写入依赖信息块（纯减法）
    dependenciesInfo {
        includeInApk = false
    }
}

dependencies {
    implementation(libs.libadb)
    implementation(libs.conscrypt)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.androidx.annotation)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
