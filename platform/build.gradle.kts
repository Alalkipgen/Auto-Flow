import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    // D-18: java.nio code (JvmFs) is shared by desktop and Android in `jvmShared`. The AGP KMP library target is not a
    // KotlinAndroidTarget, so it is matched by name instead of withAndroidTarget().
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                withCompilations { it.target.name == "android" }
            }
        }
    }

    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    android {
        namespace = "io.github.alalkipgen.autoflow.platform"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
        }
        // D-19: DPAPI (CryptProtectData) for the API key. Desktop only; Android uses its Keystore in phase 3.
        named("desktopMain").dependencies {
            implementation(libs.jna)
            implementation(libs.jna.platform)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

tasks.withType<Test>().configureEach {
    // Clipboard, browser and file dialogs must report Unsupported in tests, never open real windows.
    systemProperty("java.awt.headless", "true")
    // Burmese file names need a UTF-8 file-name encoding on Linux (a POSIX locale cannot encode them; Windows can).
    environment("LC_ALL", "C.UTF-8")
}

