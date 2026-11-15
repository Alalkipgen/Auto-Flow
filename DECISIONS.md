# Auto Flow — Architecture Decisions

Next decision: **D-54**

## D-52 — Bug fixes + 5A-6 three-pane (2025)

### Phone test results (v0.2.4, Redmi Note 13 Pro, Android 16)

| Test | Result |
|---|---|
| Back button navigation | ✅ |
| Speak All ETA card | ✅ |
| Brand Kit save + apply | ✅ |
| Background image pick | ✅ |
| Render Cancel (notification) | ✅ |
| Save to Gallery | ✅ (Movies/AutoFlow/) |
| SecureStorage (key persist) | ✅ |
| Share-sheet → Inbox | ❌ Bug: auto-navigate fails |
| S5 Gemini TTS | ✅ (gemini-3.8-flash-tts) |
| Backup zip | ✅ (1.58 GB, 3 projects, Download/AutoFlow/) |

### Bugs fixed

**Bug 1 — Share-sheet → Inbox auto-navigate (StudioV2App)**
- Root cause: StudioV2App did not observe SharedInbox.arrivals; only App() (classic shell) had the LaunchedEffect.
- Fix: added LaunchedEffect(arrivals.arrivals) in StudioV2App; on any new share it navigates to Projects/Inbox.

**Bug 2 — Home Back exits without dialog**
- Root cause: v2Back() returns null at Home → backHandler(false) = no intercept → system exits immediately.
- Fix: backHandler(true) always; if v2Back is null, sets showExitDialog = true. ExitDialog shows "Exit / Stay". Platform AppExit interface + Android finishAffinity() / desktop no-op.

### 5A-6 three-pane layout (≥840 dp)

StudioV2Shell now has three branches:
- < 600 dp → bottom nav (unchanged)
- 600–839 dp → nav rail 80 dp (unchanged)
- ≥ 840 dp → compact rail 60 dp (icon-only) + content fills rest

V2NavRailCompact uses emoji icons + purple top-line indicator.

## D-53 — 5B-6 SmartFit, Brand Kit watermark, caption timing, QuickRecap live (PR #132)

### 5B-6 SmartFit SPEED / SLOW_VIDEO
- FitChoice.SPEED: RecapFit.rows() computes voiceSpeed = (takeMs+TAIL)/sceneMs, clamped to maxVoiceSpeed (1.5×).
  VoiceCue.speedFactor → RenderCompiler.atempoChain() (chains atempo=2.0 when >2×).
- FitChoice.SLOW_VIDEO: videoSlow = scene/take, clamped to maxVideoSlow (0.7×).
  Clip.slowFactor → RenderCompiler: setpts=1/slowFactor*PTS.

### Brand Kit watermark IMAGE layer (5B-3)
- RecapVisual.watermarkFile: String? — set by LibraryModel.applyBrandKit when kit has a watermark.
- RecapRender.graph() adds Layer(IMAGE, rect=[76%,82%,22%,14%], opacity=0.75) for every frame.
- RenderJobRunner.resolveLayerImage resolves relative paths from workDir.

### Per-chunk caption timing edit
- RecapModel.saveSegmentTiming(index, startMs, endMs): shifts segment time, validated.
- TimingEditRow composable in VoiceBlockCard SpeakTab: mm:ss.s format, Save/Cancel.

### QuickRecap stage list (5B-11)
- QuickRecapStageList() observes AiJobRunner.jobs live (was stub).
- Filters QUICK_RECAP + TTS_ALL, shows state/items left/ETA per job.

## D-54 — Watermark import UI, Clean take, ExitDialog resize (v0.2.6)

### Watermark Import UI
- RecapModel.importWatermark(): opens file picker → copies file → sets visual.watermarkFile → saves
- RecapModel.removeWatermark(): clears watermarkFile, deletes local copy
- RecapPictureControls: "Set watermark…" button when none; "Change… / Remove" when set
- LibraryModel.saveBrandKit: reads recap.visual.watermarkFile into BrandKit

### Clean Take
- RecapModel.cleanTake(index): loads take audio → trimPauses → saves cleaned file back
- VoiceBlockCard Clean tab: "✨ Polish" button calls cleanTake; shows progress

### ExitDialog Resize
- padding 20dp → 32dp; title + 20.sp bold; subtitle muted text added
- Buttons: Row with weight(1f) each → full-width side-by-side Exit / Stay
- fillMaxWidth(0.85f) so dialog is 85% of screen width

## D-56 — Output ratio, watermark position, caption custom colour/size (v0.2.8)

### Output Video Ratio (Movie Recap)
- RecapVisual.outputRatio: Ratio? — overrides project.settings.ratio for the render.
  Null = use project default (9:16). Stored in visual so it travels with Brand Kit.
- ExportModel.recapJob: ratio = recap.visual.outputRatio ?: project.settings.ratio
- RecapPictureControls: "Output video ratio" chips (Default / 16:9 / 9:16 / 1:1 / 4:5 / 21:9)

### Watermark Position
- RecapVisual.watermarkAnchor: WatermarkAnchor (TOP_LEFT/TOP_RIGHT/BOTTOM_LEFT/BOTTOM_RIGHT/CENTER)
- WatermarkAnchor.rect(wmW, wmH): computes [x,y,w,h] with 2% padding from chosen corner.
- RecapRender: uses anchor.rect() instead of hardcoded bottom-right [0.76,0.82,0.22,0.14].
- RecapPictureControls: position chips appear when watermark is set.

### Caption Custom Colour / Size
- CaptionStyle.customColour: String? — free #RRGGBB; effectiveColour property picks it over preset.
- CaptionStyle.customSizeFactor: Float? (0.5–3.0); effectiveSizeFactor overrides size enum.
- RecapRender: captionSizePx uses effectiveSizeFactor; overlay colour uses effectiveColour.
- RecapModel: setCaptionCustomColour(), setCaptionCustomSize()
- RecapPictureControls: hex text field + size factor field, each with Apply / Clear.

## D-57 — Caption colours/stroke, section labels, live preview (v0.2.9)

### Caption Colours (expanded)
- CaptionStyle.COLOURS: 9 presets — White, Yellow, Cyan, Red, Orange, Green, Blue, Purple, Black.
- setCaptionColour(hex): sets colour + clears customColour (preset path).

### Caption Stroke / Outline
- CaptionStyle.strokeWidth: Int 0–8 (0 = auto, proportional to font size).
- CaptionStyle.strokeColour: #RRGGBB (default #000000).
- AssWriter: custom outline applied to Style line; StyleKey includes strokeWidth + strokeColour.
- RecapRender: passes strokeWidth/strokeColour to Overlay.
- UI: Off / Thin / Medium / Thick chips; stroke colour chips when width > 0.

### Section Labels (RecapPictureControls)
- Reorganised into 5 AfCard sections:
  🎬 Output (video ratio)
  🙈 Hide things (blur boxes)
  🖼 Background (space around movie)
  💧 Watermark (import, position)
  ✍️ Captions (colour presets + custom hex, size presets + custom factor, stroke, dark box)

### Live Preview (5A-8 / ExoPlayer)
- ExportScreen.RunStatus Done: VideoPreview(s.output) shown above action buttons.
- Android: ExoPlayer with playWhenReady=false (manual play), keepScreenOn=true.
- Desktop: placeholder text (video preview not supported on desktop).

## D-60 (2025) · STEP 1 — Recap v2 reader
- `Project.SCHEMA_VERSION` bumped 1 → 2; `Project.recap` changed to `RecapV2?`.
- `DashboardModel.open()`: replaces disabled migration guard with `migrateProject()` — JSON-level backup + `RecapMigration.migrate()` using `ModelConfig.tts.chainFor("my").first()` for the voice lock.
- `createMovieRecap` / `createQuickRecap`: new projects start as `RecapV2` (no v1 `Recap` created anymore).
- `RecapV2`: added `captionStyle`, `publishKit`, and backward-compat computed properties (`burnCaptions`, `movieVolume`, `script`, `picked`, `pickedMs`, `scriptApproved`, `subtitles`, `subtitleTrack`, `voiceSource`, `ttsVoice`, `style`, `register`, `fitChoice()`).
- `RecapV2Adapters.kt`: `RecapV2.toV1(story?)` and `RecapStory.toV1Script()` for rendering/rules backward compat.
- `RecapRulesV2.kt`: v2-native gate functions (`canImportMovie`, `canFindScenes`, `canVoice`, `canRender` etc.).
- `RecapRules.kt`: `RecapV2` overloads via `toV1()`; `RecapFit.rows(RecapV2, ...)` overload.
- `Scenes.kt`: `offTarget(RecapV2)` overload.
- `RecapModel`: reads/writes `RecapV2`; story artifact (`RecapStory`) replaces `RecapScript` internally; `storyToScript()` adapter for UI backward compat; `BlockTake` replaces `VoiceTake` in `storeTake()`; v2 field access (`storyApproved`, `sources.subtitles`, `voiceLock?.voice`).
- All composables updated to `RecapV2`; `recap.toV1()` used at `RecapRules` call sites.
- `ProjectStoreTest`: expected schema version for newer-schema test bumped 2 → 3.
