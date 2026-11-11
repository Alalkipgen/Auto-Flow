# Spec: Render Graph → ffmpeg

```kotlin
// :media render/RenderGraph.kt (kotlinx.serialization, schemaVersion 1)
data class RenderGraph(val clips: List<Clip>, val audio: AudioTrack?, val overlays: List<Overlay>, val output: Output)
data class Clip(val artifact: ArtifactId? /* null = placeholder */, val trimInMs: Long, val trimOutMs: Long,
                val transition: Transition /* CUT|CROSSFADE, from the previous clip */, val transitionMs: Int, val label: String)
data class AudioTrack(val file: String, val offsetMs: Long, val fadeInMs: Int, val fadeOutMs: Int, val muteClips: Boolean = true, val clipsVolume: Double = 0.3)
data class Overlay(val text: String, val startMs: Long, val endMs: Long, val font: String /* family */, val sizePx: Int, val color: String, val position: Pos /* TOP|CENTER|BOTTOM */, val anim: Anim /* NONE|FADE */)
data class Output(val ratio: Ratio, val width: Int, val height: Int, val fps: Int, val quality: Quality /* PREVIEW|EXPORT */, val preset: String /* yt1080|tiktok540|ig1x1-1080… */)
```
Output length = sum of clip lengths minus crossfade overlaps. `RenderPresets`: Preview short side 540 px, Export 1080 px (16:9, 9:16, 1:1, 4:5, 21:9; even sizes), 24 fps.
Compiler produces a single `ffmpeg -filter_complex` command: scale/pad each clip to output, `xfade` for crossfades, text overlays as below, `amix`/`adelay` for audio, `-t` = graph length (`RenderCompiler`; the ffmpeg build needs `tpad`, `apad=whole_dur`, `amix normalize`: FFmpeg ≥ 5). Placeholder clip (dark card with "#NN — pending") for shots without accepted video.

### Text overlays (S4, Q-02)
- **Primary — libass.** The compiler writes `exports/<name>.ass` (`PlayResX/Y` = output size; one `Style` per font/size/colour; one `Dialogue` per overlay with `\an`/`\pos` from `position`, `\fad(ms,ms)` for `FADE`, `\k` for karaoke later) and applies `ass=<name>.ass:fontsdir=<fonts dir>` after scale/xfade. Fonts always come from the app/project fonts dir, never system fonts. ASS `Fontsize = sizePx × (usWinAscent + usWinDescent) / unitsPerEm` of that font (`FontInfo` reads it from the font file), so export matches the Compose preview (Noto Sans Myanmar ×2.184, Pyidaungsu ×1.724).
- **Fallback — Skia PNG + `overlay`**, used when the ffmpeg check finds no `ass` filter / `--enable-libass`: each overlay is rasterised by Compose `ImageComposeScene` at output size into a transparent PNG (same shaping as the UI), added as `-loop 1 -i ovl_NN.png` and composited with `overlay=0:0:enable='between(t,start,end)'`; `FADE` = `fade=t=in/out:alpha=1` on that input.
- **Never `drawtext`** for user text: FFmpeg ≤ 8.0 forces Latin script in HarfBuzz and breaks Burmese stacking/reordering (S4).
- Long Burmese lines are broken explicitly (`\N` / separate overlays); no reliance on auto-wrap, since lyrics often lack spaces.

Preview = 540p, `-preset ultrafast`. Export = `libx264 -crf 18 -preset slow`, AAC 192k.
Each render writes `exports/<name>.mp4` + `exports/<name>.rendergraph.json` (artifact kind `rg`).
`EndFrame.extract(video, ms)` → png artifact for Frames-to-Video chaining.
