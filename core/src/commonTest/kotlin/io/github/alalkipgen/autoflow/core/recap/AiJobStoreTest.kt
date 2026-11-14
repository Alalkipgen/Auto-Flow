package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun loadAll_emptyDir_returnsEmpty() {
        val store = makeStore()
        assertEquals(emptyList(), store.loadAll())
    }
}
