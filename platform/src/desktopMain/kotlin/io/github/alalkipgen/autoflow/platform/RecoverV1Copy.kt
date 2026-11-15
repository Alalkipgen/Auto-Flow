package io.github.alalkipgen.autoflow.platform

import java.io.File
import kotlin.system.exitProcess

/** Offline command only; no recovery UI or automatic replacement workflow. */
object RecoverV1Copy {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 2 || args.any { it.isBlank() }) {
            System.err.println("Supply -PrecoverySource=/path/Original.autoflow -PrecoveryDestination=/path/Recovered.autoflow")
            exitProcess(2)
        }
        when (val result = V1ProjectCopy.recover(File(args[0]), File(args[1]))) {
            is V1ProjectCopy.Result.Recovered -> println("RECOVERED COPY: $result")
            is V1ProjectCopy.Result.Refused -> {
                System.err.println("RECOVERY REFUSED: ${result.reason}")
                exitProcess(1)
            }
        }
    }
}
