# Spec: Render Graph → ffmpeg

```kotlin
data class RenderGraph(val clips: List<Clip>, val audio: AudioTrack?, val overlays: List<Overlay>, val output: Output)
data class Clip(val artifact: ArtifactId, val trimInMs: Long, val trimOutMs: Long, val transition: Transition /* CUT|CROSSFADE(ms) */)
data class AudioTrack(val file: String, val offsetMs: Long, val fadeInMs: Int, val fadeOutMs: Int, val muteClips: Boolean = true)
data class Overlay(val text: String, val startMs: Long, val endMs: Long, val font: String, val sizePx: Int, val color: String, val position: Pos, val anim: Anim /* NONE|FADE */)
data class Output(val ratio: Ratio, val width: Int, val height: Int, val fps: Int, val preset: String /* yt1080|tiktok|ig1x1 */)
```
Compiler produces a single `ffmpeg -filter_complex` command: scale/pad each clip to output, `xfade` for crossfades, `drawtext` with `fontfile` (Burmese font) per overlay, `amix`/`adelay` for audio, `-shortest`. Placeholder clip (dark card with "#NN — pending") for shots without accepted video.
Preview = 540p, `-preset ultrafast`. Export = `libx264 -crf 18 -preset slow`, AAC 192k.
Each render writes `exports/<name>.mp4` + `exports/<name>.rendergraph.json` (artifact kind `rg`).
`EndFrame.extract(video, ms)` → png artifact for Frames-to-Video chaining.
