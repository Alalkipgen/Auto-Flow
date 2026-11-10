# Architecture

## 1. Principles
1. **Project is a folder.** Source of truth is `project.json` + immutable `artifacts/`. No hidden DB state. The project index (`~/.autoflow/projects.json`, D-20) is only a rebuildable list.
2. **Shot is the source of truth; prompt is compiled.** `ShotSpec` (structured, Burmese + English fields) → `PromptIR` → model dialect string.
3. **Approval is domain logic.** `WorkflowRules` in `:core` decides what may happen. UI never bypasses it.
4. **Stale is computed, not stored.** Every generated artifact records the artifact ids/hashes it was derived from. "Stale" = pointer mismatch at read time. No cascade updates.
5. **Video is manual.** Flow has no API. The app prepares, tracks, and ingests; the human generates.
6. **Windows renders. Android reviews.**
7. **Everything runs with fakes.** No network needed for dev/test.

## 2. Modules
```
:core        model · project I/O · ArtifactStore · WorkflowRules · PromptCompiler · ShotSplitter
:ai          provider interfaces · Gemini impls · Fake impls · ModelCapability config
:media       RenderGraph → ffmpeg compiler (common, D-23) · expect/actual: ffmpeg runner (desktop: located binary; android: bundled binary) · probe · frame extract · player · folder watcher
:platform    JvmFs (core FileSystem on java.nio, D-18) · secure storage (DPAPI, D-19) · file pickers · open-URL · clipboard · app dirs
:ui          Compose Multiplatform screens + ViewModels (shared)
:app-desktop Compose Desktop entry, jpackage (MSIX)
:app-android Android entry, bundled ffmpeg (D-23), share-sheet receiver, WorkManager render worker, Self-test (1F, D-22)
spikes/      throwaway experiments (not in main build)
```
Dependency direction: `app-* → ui → (core, ai, media, platform)`; `ai → core`; `media → core`; `platform → core` (D-18). `:core` depends on nothing but kotlinx.

## 3. Runtime flow (MV mode)
```
Input ──► ShotSplitter ──► Shot[] (empty specs, timecodes)
      ──► TextProvider.analyze(lyrics) ──► SongAnalysis (sections, arc, motifs)      [artifact]
      ──► TextProvider.storyboard(analysis, shots, style) ──► ShotSpec[] (my + en)   [artifact per storyboard version]
User approves storyboard (per shot or all)
      ──► PromptCompiler(spec, globalStyle, characters) ──► PromptIR                 [artifact]
User approves prompt
      ──► ImageProvider(promptIR.toImagePrompt()) ×2 ──► Ref A, Ref B               [artifacts]
User picks ref
      ──► Handoff: copy prompt / export package / open Flow        (status: SENT_TO_FLOW)
Human generates in Flow, downloads mp4 → inbox/
      ──► FolderWatcher → attach dialog → Artifact(video, inputs{promptIR, ref})     [artifact]
User accepts (draft/final)
      ──► RenderGraph(clips, audio, overlays) ──► ffmpeg ──► exports/
```

## 4. Key types (sketch)
```kotlin
@Serializable data class Project(
  val schemaVersion: Int = 1, val id: String, val name: String, val mode: Mode,
  val settings: ProjectSettings, val globalStyle: GlobalStyle, val characters: List<Character>,
  val shots: List<Shot>, val currentStoryboard: ArtifactId?, val timeline: RenderGraph?
)
@Serializable data class Shot(
  val number: Int, val startMs: Long, val durationMs: Long, val section: Section?,
  val lyricsMy: String,
  val spec: ArtifactId?,          // ShotSpec artifact (from storyboard version)
  val promptIr: ArtifactId?,
  val refImages: List<ArtifactId>, val chosenRef: ArtifactId?,
  val videos: List<ArtifactId>, val acceptedVideo: ArtifactId?,
  val approvals: Approvals, val handoff: HandoffState
)
@Serializable data class Artifact(
  val id: ArtifactId /* "<kind>_<sha256-12>" */, val kind: Kind, val file: String,
  val shot: Int?, val inputs: Map<String, String> /* name → artifactId|hash */,
  val provider: String?, val model: String?, val createdAt: Instant, val note: String?
)
```

## 5. Stale detection
`isStale(shot.promptIr)` = `artifact(promptIr).inputs["spec"] != shot.spec` or `inputs["globalStyle"] != hash(project.globalStyle)`. Same pattern for refs (depend on promptIr) and videos (depend on promptIr + chosenRef). Pure function; unit-tested.

## 6. Concurrency & jobs
Only short API jobs exist (text/image/TTS). A small in-process `JobRunner` with per-provider concurrency = 1, exponential backoff on 429/5xx, and a persisted daily counter (`~/.autoflow/usage.json`). No long-poll video jobs.

## 7. Persistence & sync
- `project.json` atomic writes; artifacts write-once.
- Android ↔ Windows: user runs Syncthing (or copies folder). App is sync-agnostic. Artifacts never conflict; `project.json` conflict → keep both, show chooser (1F).

## 8. Security
- API key: `:platform` SecureStorage (Windows DPAPI → `~/.autoflow/secrets.bin`, D-19). Never serialized.
- Voice samples/consent recordings (phase 4): stored in project folder only; user is warned before upload.

## 9. Non-goals
Backend, accounts, billing, cloud render, Flow automation, multi-user, plugin system.
