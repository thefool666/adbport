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

        // Dynamically accept CI properties or fall back to local defaults
        versionCode = (project.findProperty("CI_VERSION_CODE") as? String)?.toIntOrNull() ?: 1
        versionName = project.findProperty("CI_VERSION_NAME") as? String ?: "1.0.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
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
