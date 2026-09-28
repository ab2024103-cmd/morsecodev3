import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ---------------------------------------------------------------------------
// Signing (§19.3 SIGNING KEY RULE).
// One stable upload key, supplied by CI secrets as a base64 keystore. Building
// a throwaway key per run is FORBIDDEN: every build would carry a different
// signature and no device could update an install. When the secrets are absent
// the release variant is built with the debug key and the artifact is renamed
// `morsecode-<version>-preview-unsigned.apk` by CI, and §19.3 / A18 are
// reported as NOT satisfied.
// ---------------------------------------------------------------------------
val keystoreFile: File = rootProject.file("upload-keystore.jks")
val keystorePropsFile: File = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}
val hasUploadKey: Boolean = keystoreFile.exists() && keystoreProps.getProperty("keyAlias") != null

android {
    namespace = "app.morsecode.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.morsecode.android"   // §1.1.1 — single source of identity
        minSdk = 21                               // §3.2
        targetSdk = 35                            // §3.2 "targetSdk 34+"
        versionCode = 1                           // §19.1 — 1.0.0 (1)
        versionName = "1.0.0"

        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasUploadKey) {
            create("upload") {
                storeFile = keystoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ""   // one installable identity; debug installs over release deliberately
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasUploadKey) {
                signingConfigs.getByName("upload")
            } else {
                // Preview build only — CI renames the artifact and flags §19.3 as unmet.
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // No core library desugaring: §3.2 requires Java-8 default collection
        // methods to be SDK_INT-guarded by hand, enforced by the §20.8 gate.
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // The binding gate is tools/lintgate.py (§20.8); AGP lint is advisory.
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/*.kotlin_module",
            "DebugProbesKt.bin",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.media)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.collection)
    implementation(libs.androidx.preference.ktx)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.play.services.nearby)
    implementation(libs.nanohttpd)

    // §3.3 [CHANGED]: Material is REQUIRED, not optional — the mocks are
    // Material surfaces, and hand-rolling them is named as the biggest cause
    // of a UI that is "similar but not the same" (§4.15).
    implementation(libs.material)

    // §3.3: Media3/ExoPlayer for BOTH players. MediaPlayer/VideoView is not
    // sufficient — codec coverage on API 23 and seekTo semantics (§6.11).
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    // §3.3 ALLOWED IF USEFUL: a real loader, rather than a hand-rolled LRU,
    // for grids of thousands of thumbnails (§20.10, and Stage 21's blur).
    implementation(libs.coil)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)

    // §21.3: the emulator suite. These run ON a device, which is the only way
    // to find out whether a screen renders at all.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.espresso.contrib)
    androidTestImplementation(libs.uiautomator)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
