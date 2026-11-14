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
