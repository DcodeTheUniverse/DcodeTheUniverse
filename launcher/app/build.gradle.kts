plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.axiom.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.axiom.launcher"
        minSdk = 30
        targetSdk = 36
        versionCode = 6
        versionName = "2.3.1"
        // LiteRT-LM (on-device models) ships 64-bit ARM and x86_64 code; leaving out x86_64 saves 26 MB. 32-bit ARM
        // phones still install Axiom: they just can't run on-device models (the settings say so).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Release key is kept out of git. Point AXIOM_KEYSTORE at it (or drop it in keystore/);
    // without it, release builds fall back to the debug key.
    val releaseKeystore = (System.getenv("AXIOM_KEYSTORE")?.let { file(it) } ?: rootProject.file("keystore/axiom-release.jks"))
    signingConfigs {
        if (releaseKeystore.exists()) create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("AXIOM_KEYSTORE_PASSWORD") ?: "dcodelauncher"
            keyAlias = "dcode"
            keyPassword = System.getenv("AXIOM_KEYSTORE_PASSWORD") ?: "dcodelauncher"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("roborazzi.test.record", "true")
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
            it.maxHeapSize = "3g"
            // Robolectric keeps a lot per JVM; a fresh JVM every few classes keeps the whole suite in memory bounds.
            it.forkEvery = 6
        }
    }
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module", "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/native-image/**")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")

    implementation("com.anthropic:anthropic-java:3.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    // On-device models (Gemma 4) via Google's LiteRT-LM; runs in its own process (see ondevice/).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.77.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.77.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
