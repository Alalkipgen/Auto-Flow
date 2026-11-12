# Spec: Render Graph → ffmpeg

```kotlin
// :media render/RenderGraph.kt (kotlinx.serialization, schemaVersion 1)
data class RenderGraph(val clips: List<Clip>, val audio: AudioTrack?, val overlays: List<Overlay>, val output: Output,
                       val voice: VoiceTrack? = null /* Movie Recap */, val looks: List<Look> = emptyList())
data class Clip(val artifact: ArtifactId? /* null = placeholder */, val source: String? = null /* project file, e.g. source/movie.mp4 */, val trimInMs: Long, val trimOutMs: Long,
                val transition: Transition /* CUT|CROSSFADE, from the previous clip */, val transitionMs: Int, val label: String)
data class AudioTrack(val file: String, val offsetMs: Long, val fadeInMs: Int, val fadeOutMs: Int, val muteClips: Boolean = true, val clipsVolume: Double = 0.3)
data class Overlay(val text: String, val startMs: Long, val endMs: Long, val font: String /* family */, val sizePx: Int, val color: String, val position: Pos /* TOP|CENTER|BOTTOM */, val anim: Anim /* NONE|FADE */)
data class VoiceTrack(val cues: List<VoiceCue>, val clipsVolume: Double = 0.2)   // narration; never together with AudioTrack
data class VoiceCue(val file: String, val startMs: Long)
data class Output(val ratio: Ratio, val width: Int, val height: Int, val fps: Int, val quality: Quality /* PREVIEW|EXPORT */, val preset: String /* yt1080|tiktok540|ig1x1-1080… */)
```
Output length = sum of clip lengths minus crossfade overlaps. `RenderPresets`: Preview short side 540 px, Export 1080 px (16:9, 9:16, 1:1, 4:5, 21:9; even sizes), 24 fps.
Compiler produces a single `ffmpeg -filter_complex` command: scale/pad each clip to output, `xfade` for crossfades, text overlays as below, `amix`/`adelay` for audio, `-t` = graph length (`RenderCompiler`; the ffmpeg build needs `tpad`, `apad=whole_dur`, `amix normalize`: FFmpeg ≥ 5). Placeholder clip (dark card with "#NN — pending") for shots without accepted video. Generated pictures and sound (the placeholder card, silence for clips without audio) are source filters inside the graph (`color=…:d=`, `anullsrc=…:d=`), never `-f lavfi` inputs: the Android ffmpeg is built without input devices.

### Text overlays (S4, Q-02)
- **Primary — libass.** The compiler writes `exports/<name>.ass` (`PlayResX/Y` = output size; one `Style` per font/size/colour; one `Dialogue` per overlay with `\an`/`\pos` from `position`, `\fad(ms,ms)` for `FADE`, `\k` for karaoke later) and applies `ass=<name>.ass:fontsdir=<fonts dir>` after scale/xfade. Fonts always come from the app/project fonts dir, never system fonts. ASS `Fontsize = sizePx × (usWinAscent + usWinDescent) / unitsPerEm` of that font (`FontInfo` reads it from the font file), so export matches the Compose preview (Noto Sans Myanmar ×2.184, Pyidaungsu ×1.724).
- **Fallback — Skia PNG + `overlay`**, used when the ffmpeg check finds no `ass` filter / `--enable-libass`: each overlay is rasterised by Compose `ImageComposeScene` at output size into a transparent PNG (same shaping as the UI), added as `-loop 1 -i ovl_NN.png` and composited with `overlay=0:0:enable='between(t,start,end)'`; `FADE` = `fade=t=in/out:alpha=1` on that input.
- **Never `drawtext`** for user text: FFmpeg ≤ 8.0 forces Latin script in HarfBuzz and breaks Burmese stacking/reordering (S4).
- Long Burmese lines are broken explicitly (`\N` / separate overlays); no reliance on auto-wrap, since lyrics often lack spaces.

### Movie Recap (docs/specs/recap.md §6)
- A clip with `source` (and no `artifact`) reads that file from `trimInMs` to `trimOutMs` (`-ss`/`-t` on the input, `-threads 2`); past the file end the last frame holds (`tpad`). It is never a placeholder card.
- `looks` apply only to `source` clips, before scale/pad: `MIRROR` `hflip`, `ZOOM` `crop=iw*0.9:ih*0.9`, `FILL` scale to cover + crop to the output (no bars), `WARM` `eq=saturation=1.15:contrast=1.05`.
- `voice`: the clips' own sound at `clipsVolume` (0 = silence) mixed with each cue's file delayed to `startMs` (`adelay`, `amix duration=first normalize=0`). A graph with both `audio` and `voice` is invalid; so is a missing source or cue file.
- `RenderJob.srt` (captions) is written as `exports/<name>.srt` next to the mp4 after a successful render.

Preview = 540p, `-preset ultrafast`. Export = `libx264 -crf 18 -preset slow`, AAC 192k.
Each render writes `exports/<name>.mp4` + `exports/<name>.rendergraph.json` (artifact kind `rg`).
`EndFrame.extract(video, ms)` → png artifact for Frames-to-Video chaining.
