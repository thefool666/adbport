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
            // 开启 R8 代码收缩 + 资源收缩
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
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
