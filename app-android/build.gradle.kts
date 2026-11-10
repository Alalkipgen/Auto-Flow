plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// D-24: CI passes the run number and commit; a stable signing key comes from Actions secrets (never committed).
val ciVersionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: 1
val gitSha = providers.gradleProperty("gitSha").orNull ?: "local"
val keystoreFile = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull?.takeIf { file(it).isFile }

android {
    namespace = "io.github.alalkipgen.autoflow"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.alalkipgen.autoflow"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = ciVersionCode
        versionName = "0.1.0"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "SIGNING", "\"${if (keystoreFile != null) "stable" else "runner-debug"}\"")
        // D-23: the bundled ffmpeg exists for arm64-v8a only.
        ndk { abiFilters += "arm64-v8a" }
    }

    // D-23: extract native libs to nativeLibraryDir, the only place Android 10+ lets the app exec libffmpeg.so.
    packaging { jniLibs { useLegacyPackaging = true } }

    // CI-generated assets: ffmpeg BUILDINFO + licenses (D-23), self-test fixtures (S6). Absent in local builds.
    // A plain File: AGP rejects Provider instances in the SourceSet API.
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/bundled").get().asFile)

    signingConfigs {
        if (keystoreFile != null) {
            create("stable") {
                storeFile = file(keystoreFile)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("stable")?.let { signingConfig = it }
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(libs.androidx.activity.compose)
}
