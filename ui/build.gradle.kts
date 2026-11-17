import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    android {
        namespace = "io.github.alalkipgen.autoflow.ui"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":ai"))
            implementation(project(":media"))
            implementation(project(":platform"))
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.koin.core)
            implementation(libs.koin.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        named("androidMain").dependencies {
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.ui)
        }
        named("desktopTest").dependencies {
            implementation(libs.compose.ui.test)
            implementation(libs.navigationevent.compose)
            implementation(compose.desktop.currentOs)
        }
    }
}

// Offline synthetic backup generation; test tooling is not part of either app distribution.
val qaTestCompilation = (kotlin.targets.getByName("desktop") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget).compilations.getByName("test")
tasks.register<JavaExec>("qaRecapFixture") {
    group = "verification"
    description = "Generate disposable native-v2 project folders and an Android-restorable QA backup"
    dependsOn(qaTestCompilation.compileTaskProvider)
    classpath(qaTestCompilation.output.allOutputs, qaTestCompilation.runtimeDependencyFiles)
    mainClass.set("io.github.alalkipgen.autoflow.ui.recap.QaRecapFixtureKt")
    args(providers.gradleProperty("qaFixtureDir").getOrElse(layout.buildDirectory.dir("qa-fixture").get().asFile.path))
}
