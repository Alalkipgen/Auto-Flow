package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiJobStoreTest {

    private fun makeStore(): AiJobStore {
        val fs = InMemoryFileSystem()
        return AiJobStore(fs, "/jobs")
    }

    private fun job(id: String, kind: AiJobKind = AiJobKind.TTS_ALL) = AiJob(
        id = id,
        kind = kind,
        project = "projects/p1",
        items = listOf("0", "1", "2"),
    )

    @Test
    fun equivalentInputsShareOneActiveOwnerEvenAfterCheckpointAndReopen() {
        val fs = InMemoryFileSystem()
        val first = AiJobStore(fs, "/jobs")
        val request = job("original").copy(inputKey = "fingerprint")
        assertEquals(AiJobStore.Enqueue("original", true), first.prepareEnqueue(request))
        first.update("original") { it.copy(state = AiJobState.RUNNING, items = listOf("2")) }
        val reopened = AiJobStore(fs, "/jobs")
        assertEquals(AiJobStore.Enqueue("original", false), reopened.prepareEnqueue(request.copy(id = "duplicate")))
        assertEquals(3, reopened.loadAll().single().initialItemCount)
        assertEquals(listOf("2"), reopened.loadAll().single().items)
        assertTrue(reopened.prepareEnqueue(request.copy(id = "different", inputKey = "edited-inputs")).shouldLaunch)
        assertTrue(reopened.prepareEnqueue(request.copy(id = "another", project = "projects/p2")).shouldLaunch)
    }

    @Test
    fun saveAndLoad_roundTrip() {
        val store = makeStore()
        val j = job("j_001")
        store.save(j)
        val loaded = store.load("j_001")
        assertEquals(j, loaded)
    }

    @Test
    fun load_missing_returnsNull() {
        val store = makeStore()
        assertNull(store.load("nope"))
    }

    @Test
    fun loadAll_returnsAllJobs() {
        val store = makeStore()
        val j1 = job("j_001", AiJobKind.TTS_ALL)
        val j2 = job("j_002", AiJobKind.PUBLISH_KIT)
        store.save(j1)
        store.save(j2)
        val all = store.loadAll()
        assertEquals(2, all.size)
        assertTrue(all.any { it.id == "j_001" })
        assertTrue(all.any { it.id == "j_002" })
    }

    @Test
    fun save_updatesExistingJob() {
        val store = makeStore()
        val j = job("j_001")
        store.save(j)
        val updated = j.copy(state = AiJobState.DONE, items = emptyList())
        store.save(updated)
        val loaded = store.load("j_001")
        assertEquals(AiJobState.DONE, loaded?.state)
        assertEquals(emptyList(), loaded?.items)
    }

    @Test
    fun delete_marksJobCancelled() {
        val store = makeStore()
        store.save(job("j_001"))
        store.delete("j_001")
        val loaded = store.load("j_001")
        assertEquals(AiJobState.CANCELLED, loaded?.state)
    }

    @Test fun terminalHistoryOnlyAndStaleWritesCannotBringItBack() {
        val fs = InMemoryFileSystem()
        val store = AiJobStore(fs, "/jobs")
        for (state in AiJobState.entries) {
            val j = job(state.name).copy(state = state)
            store.save(j)
            assertFalse(store.removeFinished(j.id, "wrong project"))
            assertEquals(state.finished, store.removeFinished(j.id, j.project))
            if (state.finished) {
                assertEquals("""{"removed":true}""", fs.read("/jobs/${j.id}.json").decodeToString())
                store.save(j)
                assertFalse(store.prepareEnqueue(j).shouldLaunch)
                assertNull(store.update(j.id) { it.copy(state = AiJobState.DONE) })
            }
        }
        assertFalse(store.removeFinished("../outside", "projects/p1"))
        val reopened = AiJobStore(fs, "/jobs")
        assertEquals(setOf(AiJobState.QUEUED, AiJobState.RUNNING, AiJobState.PAUSED), reopened.loadAll().map { it.state }.toSet())
        assertTrue(reopened.damaged().isEmpty())
    }

    @Test
    fun loadAll_emptyDir_returnsEmpty() {
        val store = makeStore()
        assertEquals(emptyList(), store.loadAll())
    }
}
