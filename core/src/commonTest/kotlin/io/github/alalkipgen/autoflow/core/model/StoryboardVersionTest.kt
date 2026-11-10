package io.github.alalkipgen.autoflow.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class StoryboardVersionTest {
    @Test
    fun roundTripsWithCoreJson() {
        val v = StoryboardVersion(
            analysis = ArtifactId("an_0123456789ab"),
            shots = listOf(StoryboardShot(1, 0, 8_000, Section.VERSE, ShotSpec(shot = 1, sceneMy = "မိုး", subjectEn = "rain"))),
        )
        val text = CoreJson.encodeToString(v)
        assertEquals(v, CoreJson.decodeFromString<StoryboardVersion>(text))
    }
}
