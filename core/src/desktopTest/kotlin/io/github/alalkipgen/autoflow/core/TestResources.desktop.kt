package io.github.alalkipgen.autoflow.core

actual fun readTestResource(path: String): String {
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(path)
        ?: error("test resource not found: $path")
    return stream.use { it.readBytes().decodeToString() }
}
