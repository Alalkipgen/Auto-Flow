package io.github.alalkipgen.autoflow.platform

/** Platform hook: called when the user confirms "Exit Auto Flow?" in the exit dialog (D-52). */
fun interface AppExit {
    fun exit()
}
