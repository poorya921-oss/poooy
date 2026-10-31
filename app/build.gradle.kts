plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ABIs to package. Default: arm64-v8a only (smallest APK, covers virtually all current phones).
// Override:  gradle assembleRelease -Ppooy.abis=arm64-v8a,armeabi-v7a,x86_64
val abis = (providers.gradleProperty("pooy.abis").orNull ?: "arm64-v8a")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "com.pooy.vpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pooy.vpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        ndk { abiFilters += abis }
        externalNativeBuild {
            ndkBuild {
                arguments("NDK_APPLICATION_MK=${projectDir}/src/main/jni/Application.mk")
            }
        }
    }

    externalNativeBuild {
        ndkBuild { path = file("src/main/jni/Android.mk") }
    }

    androidResources { localeFilters += "en" }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal-use convenience: -Ppooy.debugSign=true signs the release APK with the debug key.
            if (providers.gradleProperty("pooy.debugSign").isPresent) {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    packaging {
        // Compressed native libs inside the APK => smaller download (larger install size).
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
        }
    }

    lint { abortOnError = false }
}

dependencies {
    // Xray-core (gomobile AAR from 2dust/AndroidLibXrayLite). Fetched by scripts/fetch-deps.*
    implementation(files("libs/libv2ray.aar"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // QR scanning: ZXing core (decoder only, ~0.5 MB) + CameraX
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// Fail early with a clear message if the two external pieces are missing.
val checkNativeDeps = tasks.register("checkNativeDeps") {
    val aar = file("libs/libv2ray.aar")
    val hev = file("src/main/jni/hev-socks5-tunnel/Android.mk")
    doLast {
        val missing = buildList {
            if (!aar.exists()) add("app/libs/libv2ray.aar  (Xray-core AAR)")
            if (!hev.exists()) add("app/src/main/jni/hev-socks5-tunnel  (tun2socks sources)")
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "POOOY is missing external dependencies:\n  - " + missing.joinToString("\n  - ") +
                    "\nRun scripts/fetch-deps.sh (Linux/macOS) or scripts/fetch-deps.ps1 (Windows), or see README.md."
            )
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(checkNativeDeps) }
