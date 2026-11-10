import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "io.github.alalkipgen.autoflow.desktop.MainKt"
        nativeDistributions {
            // ARCHITECTURE.md §2 says MSIX, but jpackage only builds MSI/EXE — see docs/PLAN.md Q-01.
            targetFormats(TargetFormat.Msi)
            packageName = "AutoFlow"
            packageVersion = "0.1.0"
            windows {
                menuGroup = "Auto Flow"
                // Fixed forever so future MSI versions upgrade in place.
                upgradeUuid = "5f0c6a3e-2b8d-4c1e-9a77-3d6b1f0e8c42"
            }
        }
    }
}
