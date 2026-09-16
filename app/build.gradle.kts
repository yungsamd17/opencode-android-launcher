plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.s17labs.opencodelauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.s17labs.opencodelauncher"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.5.0"
    }

    signingConfigs {
        // Shared debug key (committed on purpose): CI and local builds share one
        // signature so debug APKs install as updates. Same pattern as RevNotify.
        // NOT for release — standard android/androiddebugkey credentials.
        // NOTE: AGP pre-creates a "debug" config, so configure it via getByName.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
            isMinifyEnabled = false
            resValue("string", "app_name", "OpenCode Launcher Debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.navigation.compose)
    implementation(libs.browser)
    implementation(libs.work.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
}
