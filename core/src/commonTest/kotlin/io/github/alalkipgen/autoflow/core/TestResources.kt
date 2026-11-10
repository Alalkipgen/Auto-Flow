package io.github.alalkipgen.autoflow.core

/** Reads a UTF-8 file from `core/src/commonTest/resources`, e.g. `fixtures/project-v1.json`. */
expect fun readTestResource(path: String): String
