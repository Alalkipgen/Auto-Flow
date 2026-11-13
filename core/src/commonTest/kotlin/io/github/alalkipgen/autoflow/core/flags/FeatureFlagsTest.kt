package io.github.alalkipgen.autoflow.core.flags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureFlagsTest {
    private val file = """
        { "schemaVersion": 1, "studioV2": false, "quickRecap": true,
          "labs": { "voiceClone": false, "dubbing": false, "vocalSeparation": false, "smartReframe": false,
                    "autoCaptionPlacement": false, "wordKaraoke": true, "wholeMovieTranscribe": false, "edgeVoices": false } }
    """.trimIndent()

    @Test
    fun parsesTheBundledShape() {
        val f = FeatureFlags.parse(file)
        assertFalse(f.studioV2)
        assertTrue(f.quickRecap)
        assertTrue(f.labs.wordKaraoke)
        assertFalse(f.labs.voiceClone)
    }

    @Test
    fun missingOrBrokenFileTurnsEverythingOff() {
        assertEquals(FeatureFlags(), FeatureFlags.parse(null))
        assertEquals(FeatureFlags(), FeatureFlags.parse(""))
        assertEquals(FeatureFlags(), FeatureFlags.parse("{ not json"))
        assertTrue(FeatureFlags().asMap().values.none { it })
    }

    @Test
    fun unknownKeysInTheFileAreIgnored() {
        val f = FeatureFlags.parse("""{ "studioV2": true, "future": 3, "labs": { "somethingNew": true } }""")
        assertTrue(f.studioV2)
        assertFalse(f.labs.dubbing)
    }

    @Test
    fun overridesWinOverTheFile() {
        val f = FeatureFlags.load(file, mapOf("studioV2" to true, "quickRecap" to false, "labs.dubbing" to true))
        assertTrue(f.studioV2)
        assertFalse(f.quickRecap)
        assertTrue(f.labs.dubbing)
        assertTrue(f.labs.wordKaraoke) // not overridden: kept from the file
    }

    @Test
    fun unknownOverrideKeysAreIgnored() {
        assertEquals(FeatureFlags.parse(file), FeatureFlags.load(file, mapOf("labs.gone" to true, "old" to false)))
    }

    @Test
    fun everyFlagHasAnOverrideKey() {
        val keys = FeatureFlags().asMap().keys
        assertEquals(10, keys.size)
        val allOn = FeatureFlags().withOverrides(keys.associateWith { true })
        assertTrue(allOn.asMap().values.all { it })
    }
}
