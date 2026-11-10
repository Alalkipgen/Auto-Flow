plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}
dependencies {
    implementation(compose.desktop.currentOs)
    implementation("uk.co.caprica:vlcj:4.8.3") // spike only; D-07 (VLCJ first)
}
compose.desktop { application { mainClass = "MainKt" } }
tasks.withType<JavaExec>().configureEach { workingDir = projectDir }

// Prints jar sizes that VLCJ adds to the app bundle (native VLC is measured separately).
val runtimeCp = configurations.named("runtimeClasspath")
tasks.register("depSizes") {
    doLast {
        runtimeCp.get().files.filter { f -> listOf("vlcj", "jna").any { f.name.startsWith(it) } }
            .sortedBy { it.name }.forEach { println("${it.name}\t${it.length()}") }
    }
}
