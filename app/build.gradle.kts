import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing credentials come from keystore.properties (gitignored) or, in CI, from
// environment variables. Never commit either. See README → Building a release.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(key: String, env: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(env)

val releaseStoreFile = signingValue("storeFile", "KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null

// Release builds get their version from the git tag (see .github/workflows/release.yml);
// local builds fall back to these defaults.
val appVersionName = (project.findProperty("appVersionName") as String?) ?: "0.1.0-wm26"
val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1026

android {
    namespace = "com.dashline.launcher"
    // Compile against current APIs, while preserving Android 6 head-unit behavior.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wmhome.launcher"
        // This WM adaptation is distributed directly for Android 6.0.1.
        minSdk = 23
        targetSdk = 23
        versionCode = appVersionCode
        versionName = appVersionName

        // Let the support library render vector drawables on pre-Lollipop devices.
        vectorDrawables.useSupportLibrary = true
    }

    // Preserve upstream task names; both WM variants require Android 6 or newer.
    flavorDimensions += "distribution"
    productFlavors {
        create("legacy") {
            dimension = "distribution"
            minSdk = 23
            // Upstream APKs must never overwrite the WM adaptation.
            buildConfigField("boolean", "UPDATE_CHECK", "false")
        }
        create("play") {
            dimension = "distribution"
            minSdk = 23
            buildConfigField("boolean", "UPDATE_CHECK", "false")
        }
        create("xxqg") {
            dimension = "distribution"
            applicationId = "com.wm.xxqg"
            minSdk = 23
            buildConfigField("boolean", "UPDATE_CHECK", "false")
        }
    }

    signingConfigs {
        // Only registered when credentials are actually available, so cloning the
        // repo and running assembleDebug works with no setup at all.
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Unsigned release builds still succeed; they just can't be installed.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // No applicationIdSuffix on debug: this app registers as HOME, and a
        // separate package would show up as a second launcher and lose settings.
    }

    lint {
        // A broken build should fail CI; style warnings shouldn't.
        abortOnError = true
        // Intentional for this Android 6.0.1 sideload-only vehicle build.
        disable += "ExpiredTargetSdkVersion"
        checkReleaseBuilds = false
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    // JVM layout checks need no device, emulator, or ADB connection.
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "2g"
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            it.jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    // Keep deps minimal — many cheap units lack Play Services and are slow.
    // These versions are the newest that still support minSdk 19 (KitKat).
    // Do NOT bump to appcompat 1.6+ / core 1.10+ — they require minSdk 21 and
    // will break the KitKat build.
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.4.2")
    implementation("androidx.recyclerview:recyclerview:1.2.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Pulled in by appcompat anyway; declared so the runtime icon tinting can
    // reference VectorDrawableCompat directly. 1.1.0 still supports minSdk 14.
    implementation("androidx.vectordrawable:vectordrawable:1.1.0")
}
