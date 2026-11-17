import java.io.File
import java.util.Base64
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
    // Dispatchers.Main on desktop (D-61): :ui models use MainScope(); without this the packaged app dies at start
    // with "Module with the Main dispatcher is missing". Same version as kotlinx-coroutines-core.
    implementation(libs.kotlinx.coroutines.swing)
    testImplementation(libs.kotlin.test)
}

// Built-in Burmese fonts (D-49): fonts/*.ttf.b64 are base64 text; decoded here into build/generated/fonts/fonts/*.ttf.
val decodeBuiltInFonts = tasks.register("decodeBuiltInFonts") {
    val src = rootProject.layout.projectDirectory.dir("fonts")
    val out = layout.buildDirectory.dir("generated/fonts/fonts")
    inputs.dir(src)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        src.asFile.listFiles { f -> f.name.endsWith(".ttf.b64") }.orEmpty().forEach { f ->
            File(dir, f.name.removeSuffix(".b64")).writeBytes(Base64.getMimeDecoder().decode(f.readText().trim()))
        }
    }
}

// Real packaged identity for Settings/About; a local build is labelled local, never a guessed commit.
val desktopVersion = "0.4.2"
val desktopSha = providers.gradleProperty("gitSha").orElse(providers.environmentVariable("GITHUB_SHA")).getOrElse("local")
val writeBuildIdentity = tasks.register("writeBuildIdentity") {
    val out = layout.buildDirectory.file("generated/build-info/build.txt")
    val identity = "$desktopVersion ($desktopSha)\n"
    inputs.property("version", desktopVersion)
    inputs.property("source", desktopSha)
    outputs.file(out)
    doLast {
        out.get().asFile.apply { parentFile.mkdirs(); writeText(identity) }
    }
}

// Ship config/models.json (D-10) on the classpath as /config/models.json; Settings can point at an override file.
// config/features.json (D-41) ships the same way, as /config/features.json.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.file("config/models.json")) { into("config") }
    from(rootProject.layout.projectDirectory.file("config/features.json")) { into("config") }
    // Built-in Burmese fonts (D-49), as /config/fonts/*.ttf.
    from(decodeBuiltInFonts) { into("config/fonts") }
    from(writeBuildIdentity) { into("config") }
}

compose.desktop {
    application {
        mainClass = "io.github.alalkipgen.autoflow.desktop.MainKt"
        nativeDistributions {
            // Q-01 / D-65 / D-66: a real Windows EXE installer built by jpackage + WiX on Windows (CI job
            // `windows-installer`). jpackage cannot cross-build it, so `packageExe` only works on a Windows host.
            targetFormats(TargetFormat.Exe)
            packageName = "AutoFlow"
            // Same as app-android versionName (an upgrade from the stale 0.2.0; Windows: MAJOR.MINOR.BUILD).
            packageVersion = desktopVersion
            description = "Auto Flow"
            vendor = "Auto Flow"
            windows {
                menuGroup = "Auto Flow"
                // D-66: per-user install (no admin/UAC prompt) into %LOCALAPPDATA%\AutoFlow, Start menu + desktop
                // shortcut. User data stays in ~/.autoflow and is never removed by uninstall.
                perUserInstall = true
                menu = true
                shortcut = true
                // Fixed forever so future installer versions upgrade in place.
                upgradeUuid = "5f0c6a3e-2b8d-4c1e-9a77-3d6b1f0e8c42"
            }
        }
    }
}
