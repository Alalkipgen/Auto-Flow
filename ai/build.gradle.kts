import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    android {
        namespace = "io.github.alalkipgen.autoflow.ai"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            api(libs.koin.core)
        }
        named("desktopMain").dependencies {
            implementation(libs.ktor.client.cio)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        named("desktopTest").dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}

// Live Gemini tests are opt-in: ./gradlew :ai:desktopTest -PliveAi=true (needs GEMINI_API_KEY in the environment).
tasks.withType<Test>().configureEach {
    val liveAi = providers.gradleProperty("liveAi").orElse("false")
    inputs.property("liveAi", liveAi)
    systemProperty("autoflow.liveAi", liveAi.get())
}
