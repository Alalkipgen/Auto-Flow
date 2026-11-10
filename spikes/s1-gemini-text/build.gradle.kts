plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}
dependencies { implementation(libs.kotlinx.serialization.json) }
sourceSets { main { kotlin.srcDir("../gemini-common") } }
application { mainClass = "MainKt" }
tasks.named<JavaExec>("run") { workingDir = projectDir }
