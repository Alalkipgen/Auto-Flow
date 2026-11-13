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
    // The app module binds the platform implementations for :ui and :ai (ARCHITECTURE §2: app-* → ui → …).
    implementation(project(":core"))
    implementation(project(":ai"))
    implementation(project(":media"))
    implementation(project(":platform"))
    implementation(compose.desktop.currentOs)
    testImplementation(libs.kotlin.test)
}

// Ship config/models.json (D-10) on the classpath as /config/models.json; Settings can point at an override file.
// config/features.json (D-41) ships the same way, as /config/features.json.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.file("config/models.json")) { into("config") }
    from(rootProject.layout.projectDirectory.file("config/features.json")) { into("config") }
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
