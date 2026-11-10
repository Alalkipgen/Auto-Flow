package io.github.alalkipgen.autoflow.core

actual fun readTestResource(path: String): String {
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(path)
        ?: error("test resource not found: $path")
    return stream.use { it.readBytes().decodeToString() }
}

actual fun updateGolden(path: String, content: String): Boolean {
    if (System.getenv("UPDATE_GOLDEN") != "1") return false
    val file = java.io.File("src/commonTest/resources/$path") // Gradle runs tests from the module directory
    file.parentFile.mkdirs()
    file.writeText(content)
    return true
}
