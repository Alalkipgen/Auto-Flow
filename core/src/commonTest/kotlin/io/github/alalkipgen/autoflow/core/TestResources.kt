package io.github.alalkipgen.autoflow.core

/** Reads a UTF-8 file from `core/src/commonTest/resources`, e.g. `fixtures/project-v1.json`. */
expect fun readTestResource(path: String): String

/**
 * With `UPDATE_GOLDEN=1` (desktop only), writes [content] to `core/src/commonTest/resources/[path]` and returns true;
 * otherwise returns false and the caller compares. Regenerate: `UPDATE_GOLDEN=1 ./gradlew :core:desktopTest`.
 */
expect fun updateGolden(path: String, content: String): Boolean
