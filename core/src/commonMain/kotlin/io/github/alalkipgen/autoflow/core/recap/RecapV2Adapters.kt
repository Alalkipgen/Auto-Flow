package io.github.alalkipgen.autoflow.core.recap

/**
 * Adapters from v2 types to v1 types for backward-compat rendering / rules code that hasn't been ported to v2 yet.
 * Remove each adapter once the consumer is fully ported.
 */

/** Converts [RecapV2] to a v1 [Recap] suitable for [RecapRules], [RecapFit], and [RecapRender]. */
fun RecapV2.toV1(story: RecapStory? = null): Recap = Recap(
    rightsConfirmed = rightsConfirmed,
    movie = movie,
    targetMs = targetMs,
    scenes = scenes,
    sceneThreshold = sceneThreshold,
    subtitles = sources.subtitles,
    subtitleTrack = sources.subtitleTrack,
    style = story?.style ?: RecapStyle.PLAIN,
    register = story?.register ?: RecapRegister.SPOKEN,
    script = this.story, // the ArtifactId of the story artifact
    scriptApproved = storyApproved,
    voiceSource = VoiceSource.TTS,
    ttsVoice = voiceLock?.voice ?: Recap.DEFAULT_TTS_VOICE,
    voiceLock = voiceLock,
    takes = takes.mapIndexedNotNull { i, bt ->
        // Map BlockTake back to VoiceTake by block index
        val segIndex = story?.blocks?.indexOfFirst { it.id == bt.block }?.takeIf { it >= 0 } ?: i
        VoiceTake(
            segment = segIndex,
            audio = bt.audio,
            source = when (bt.source) {
                TakeSource.TTS, TakeSource.POLISHED, TakeSource.IMPORTED -> VoiceSource.TTS
                TakeSource.RECORDED -> VoiceSource.RECORDED
            },
            provider = bt.provider,
            model = bt.model,
            voice = bt.voice,
            durationMs = bt.durationMs,
            textHash = bt.spokenHash,
        )
    },
    fit = fit.mapNotNull { (blockId, choiceName) ->
        val idx = story?.blocks?.indexOfFirst { it.id == blockId }?.takeIf { it >= 0 } ?: return@mapNotNull null
        val choice = runCatching { FitChoice.valueOf(choiceName) }.getOrNull() ?: return@mapNotNull null
        idx to choice
    }.toMap(),
    audio = audio,
    movieVolume = audio.movieVolume,
    burnCaptions = captions.burn,
    captionStyle = captionStyle,
    looks = looks,
    quality = quality,
    visual = visual,
    publishKit = publishKit,
)

/** Converts [RecapStory] to a v1 [RecapScript] for backward-compat rendering. */
fun RecapStory.toV1Script(): RecapScript = RecapScript(
    source = source,
    style = style,
    segments = blocks.mapIndexed { i, b ->
        val clip = b.clips.firstOrNull()
        RecapSegment(
            startMs = clip?.inMs ?: (i * 10_000L),
            endMs = clip?.outMs ?: ((i + 1) * 10_000L),
            textMy = b.textMy,
        )
    },
)
