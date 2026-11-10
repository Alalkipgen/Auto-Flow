plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}
dependencies { implementation(compose.desktop.currentOs) }
compose.desktop { application { mainClass = "MainKt" } }
