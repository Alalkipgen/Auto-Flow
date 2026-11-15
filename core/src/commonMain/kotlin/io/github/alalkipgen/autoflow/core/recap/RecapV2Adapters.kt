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
    // By block id only (a take of a block not in [story] is left out, never mapped by its position in the list), and
    // per block the take that plays last: v1 plays a segment's newest take, v2 the chosen one (audit F06 / F07).
    takes = takes.mapNotNull { bt ->
        val segIndex = story?.blocks?.indexOfFirst { it.id == bt.block }?.takeIf { it >= 0 } ?: return@mapNotNull null
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
    }.sortedBy { t -> story?.blocks?.getOrNull(t.segment)?.let { takeFor(it.id)?.audio == t.audio } == true },
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

/**
 * The lossless direction (audit F07): a v1 recap and its script as v2 — one block per segment (id as the migration
 * makes it, [migratedBlockId]), one clip each, the takes by block id in the same order (v1's newest = v2's newest),
 * fit by block id. v1-shaped callers (and their tests) use the v2 rules and render path through this instead of a
 * second implementation.
 */
fun Recap.toV2(script: RecapScript): Pair<RecapV2, RecapStory> {
    val ids = script.segments.mapIndexed { i, s -> migratedBlockId(i, s.startMs, s.endMs) }
    val story = RecapStory(
        source = script.source, style = script.style, register = register,
        blocks = script.segments.mapIndexed { i, s -> RecapBlock(ids[i], listOf(RecapClip(s.startMs, s.endMs)), s.textMy) },
    )
    val recap = RecapV2(
        rightsConfirmed = rightsConfirmed, movie = movie, targetMs = targetMs, scenes = scenes, sceneThreshold = sceneThreshold,
        sources = RecapSources(subtitles = subtitles, subtitleTrack = subtitleTrack, transcript = transcript),
        story = this.script, storyApproved = scriptApproved, voiceLock = voiceLock,
        takes = takes.mapNotNull { t ->
            BlockTake(
                block = ids.getOrNull(t.segment) ?: return@mapNotNull null, audio = t.audio,
                source = if (t.source == VoiceSource.RECORDED) TakeSource.RECORDED else TakeSource.TTS,
                provider = t.provider, model = t.model, voice = t.voice, durationMs = t.durationMs, spokenHash = t.textHash,
            )
        },
        fit = fit.mapNotNull { (i, c) -> ids.getOrNull(i)?.let { it to c.name } }.toMap(),
        audio = audio.copy(movieVolume = movieVolume),
        captions = RecapCaptions(burn = burnCaptions),
        looks = looks, quality = quality, visual = visual, captionStyle = captionStyle, publishKit = publishKit,
    )
    return recap to story
}

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
