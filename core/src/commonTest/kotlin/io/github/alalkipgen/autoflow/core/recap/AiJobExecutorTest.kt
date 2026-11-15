package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

/**
 * Audit F11 / Android job risks: the one job loop of both runners ([AiJobExecutor]) and its store — no processor is
 * a failure, a kill between an item's output and its checkpoint does not redo the item, a Cancel during an item is
 * not overwritten, busy pauses, a damaged job file is reported and kept, a crash mid-save keeps the old state.
 */
class AiJobExecutorTest {
    private val fs = InMemoryFileSystem()
    private val store = AiJobStore(fs, "/jobs")
    private val fast = RpmPacer(1_000)

    private fun job(id: String = "j", items: List<String> = listOf("a", "b", "c"), state: AiJobState = AiJobState.QUEUED) =
        AiJob(id, AiJobKind.TTS_ALL, "/projects/P.autoflow", items = items, state = state)

    /** A processor whose outputs live in [outputs] (like takes in a project) and that knows them ([alreadyDone]). */
    private class Project(val fail: (String) -> Throwable? = { null }) : AiItemProcessor {
        val outputs = linkedSetOf<String>()
        val calls = mutableListOf<String>()
        override suspend fun process(job: AiJob, item: String) {
            calls += item
            fail(item)?.let { throw it }
            outputs += item
        }
        override suspend fun alreadyDone(job: AiJob, item: String) = item in outputs
    }

    private fun executor(p: AiItemProcessor?) = AiJobExecutor(store, p, pacer = { fast })

    @Test fun withoutAProcessorTheJobFailsAndIsNeverDone() = runTest {
        store.save(job())
        val out = executor(null).run("j")
        assertEquals(AiJobExecutor.Outcome.Failed(AiJobExecutor.NO_PROCESSOR), out)
        assertEquals(AiJobState.FAILED, store.load("j")!!.state)
        assertEquals(listOf("a", "b", "c"), store.load("j")!!.items, "nothing is checked off")
    }

    @Test fun everyItemIsCheckpointedThenTheJobIsDone() = runTest {
        store.save(job())
        val p = Project()
        val seen = mutableListOf<List<String>>()
        val out = AiJobExecutor(store, p, pacer = { fast }, onChange = { seen += it.items }).run("j")
        assertEquals(AiJobExecutor.Outcome.Done, out)
        assertEquals(listOf("a", "b", "c"), p.calls)
        assertEquals(listOf(listOf("a", "b", "c"), listOf("b", "c"), listOf("c"), emptyList(), emptyList()), seen)
        assertEquals(AiJobState.DONE, store.load("j")!!.state)
    }

    /** The process dies after item b's output is saved but before the checkpoint: the next run does not redo b. */
    @Test fun aKillBetweenOutputAndCheckpointDoesNotRedoTheItem() = runTest {
        store.save(job())
        val p = Project()
        val dying = object : AiItemProcessor by p {
            override suspend fun process(job: AiJob, item: String) {
                p.process(job, item)
                if (item == "b") throw CancellationException("process killed")
            }
        }
        assertFailsWith<CancellationException> { executor(dying).run("j") }
        assertEquals(AiJobState.RUNNING, store.load("j")!!.state, "left RUNNING: a restart resumes it")
        assertEquals(listOf("b", "c"), store.load("j")!!.items, "b's checkpoint was lost")

        assertEquals(AiJobExecutor.Outcome.Done, executor(p).run("j"))
        assertEquals(listOf("a", "b", "c"), p.calls, "b is found in the project, not made again")
        assertEquals(setOf("a", "b", "c"), p.outputs)
    }

    /** A Cancel saved while an item runs (Android: only the flag) is not overwritten by the item's checkpoint. */
    @Test fun aCancelDuringAnItemIsNotOverwritten() = runTest {
        store.save(job())
        val p = Project()
        val cancelling = object : AiItemProcessor by p {
            override suspend fun process(job: AiJob, item: String) {
                p.process(job, item)
                if (item == "a") store.update("j") { it.copy(state = AiJobState.CANCELLED) }
            }
        }
        assertEquals(AiJobExecutor.Outcome.Cancelled, executor(cancelling).run("j"))
        val saved = store.load("j")!!
        assertEquals(AiJobState.CANCELLED, saved.state)
        assertEquals(listOf("b", "c"), saved.items, "a was made, b and c were not")
        assertEquals(listOf("a"), p.calls)
        assertEquals(AiJobExecutor.Outcome.Cancelled, executor(p).run("j"), "a cancelled job never runs again")
    }

    @Test fun busyPausesAndOtherErrorsFailKeepingTheRemainingItems() = runTest {
        store.save(job("busy"))
        val busy = Project { if (it == "b") AiItemPaused("model is busy · Resume") else null }
        assertEquals(AiJobExecutor.Outcome.Paused("model is busy · Resume"), executor(busy).run("busy"))
        assertEquals(AiJobState.PAUSED, store.load("busy")!!.state)
        assertEquals(listOf("b", "c"), store.load("busy")!!.items)

        store.save(job("bad"))
        val bad = Project { if (it == "a") IllegalStateException("Model unavailable") else null }
        assertEquals(AiJobExecutor.Outcome.Failed("Model unavailable"), executor(bad).run("bad"))
        assertEquals(listOf("a", "b", "c"), store.load("bad")!!.items)

        store.save(job("kind").copy(kind = AiJobKind.PUBLISH_KIT))
        val unsupported = AiItemProcessor { j, _ -> throw UnsupportedAiJob(j.kind) }
        assertIs<AiJobExecutor.Outcome.Failed>(executor(unsupported).run("kind"))
        assertEquals(AiJobState.FAILED, store.load("kind")!!.state, "an unsupported kind is never Done")
    }

    @Test fun aDamagedJobFileIsReportedAndKept() = runTest {
        store.save(job("ok"))
        fs.writeDurably("/jobs/bad.json", "{\"id\":\"bad\",\"kind\":\"TTS_".encodeToByteArray())
        val out = executor(Project()).run("bad")
        assertIs<AiJobExecutor.Outcome.Damaged>(out)
        assertIs<AiJobStore.Read.Damaged>(store.read("bad"))
        assertEquals(AiJobStore.Read.Missing, store.read("nope"))
        assertContentEquals("{\"id\":\"bad\",\"kind\":\"TTS_".encodeToByteArray(), fs.read("/jobs/bad.json"), "left as it is")
        assertEquals(listOf("bad"), store.damaged())
        assertEquals(listOf("ok"), store.loadAll().map { it.id })
    }

    /** A save that dies half way (power cut) leaves the previous state, not a truncated file. */
    @Test fun aCrashMidSaveKeepsThePreviousState() {
        val crashing = HalfWrite(InMemoryFileSystem())
        val s = AiJobStore(crashing, "/jobs")
        s.save(job(items = listOf("a", "b")))
        crashing.crashNext = true
        runCatching { s.save(job(items = listOf("b"), state = AiJobState.RUNNING)) }
        assertEquals(listOf("a", "b"), s.load("j")!!.items, "the last complete save")
        assertEquals(emptyList(), s.damaged())
    }

    @Test fun jobsOfOneKindAndLimitShareOnePacer() {
        assertSame(RpmPacers.forJob(job("x")), RpmPacers.forJob(job("y")))
        assertNotSame(RpmPacers.forJob(job("x")), RpmPacers.forJob(job("x").copy(kind = AiJobKind.TRANSCRIBE)))
    }

    /** Writes the first half of the bytes, then fails, when [crashNext] is set. */
    private class HalfWrite(val fs: InMemoryFileSystem) : FileSystem by fs {
        var crashNext = false
        override fun writeDurably(path: String, bytes: ByteArray) {
            if (!crashNext) return fs.writeDurably(path, bytes)
            crashNext = false
            fs.writeDurably(path, bytes.copyOf(bytes.size / 2))
            error("power cut")
        }
    }
}
