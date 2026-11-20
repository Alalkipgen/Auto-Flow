# Changelog

## Unreleased

- **Subtitles fit the frame** (F-1, D-91): burned Burmese subtitles in Dubbing and Subtitles export show at most 1 or 2 lines (Short / Long) at a time — a longer line is shown in parts one after another. Landscape videos start at 1 short line, portrait at 2. Dubbing export now also has Size chips.
- **Tools on by default** (D-84): `tools.subtitles`, `tools.voiceAudio` and `labs.dubbing` are now on in `config/features.json`, so the Home tiles Subtitles, Voice & Audio and Dubbing open on a fresh install (they showed "Planned" before). Self-test → Preview features can still turn them off.
- **Edge voices** (V-1b, D-86, D-90): free Microsoft Edge Burmese voices Thiha and Nilar, no API key, as "Thiha · Edge" / "Nilar · Edge" chips next to the Gemini voices in Voice & Audio and Dubbing (flag `labs.edgeVoices`). Unofficial: if Microsoft refuses, the item fails visibly and Gemini voices stay available.

## v0.4.2

- **BLUR feather fix** (5A-4): `geq=lum` + a real 0–255 alpha fade; `LayerFfmpegRenderTest` renders with a real ffmpeg (#135).
- **Desktop Main dispatcher** (D-61): `kotlinx-coroutines-swing` in the desktop app; CI `desktop-smoke` launches the packaged app under Xvfb (#136).
- **Voice lock** (5A-1, D-35): one model per TTS call, first take sets the lock, busy model → job PAUSED, Burmese-only S5 list; `VoiceLockTest`, `ModelsConfigTest`, `RecapVoiceLockTest` (#137).

## v0.2.6

- **Watermark import UI** (5B-3, D-54): Recap → 5 · Fit check → "Set watermark…" lets you pick a PNG/JPG that
  appears in the bottom-right of every rendered frame. "Change…" and "Remove" manage it after it is set. The
  watermark file is also saved into the Brand Kit the next time you save it from Library.
- **Clean / Polish a take** (D-54): Recap → Voice → Clean tab → "✨ Polish" trims long pauses out of an already-
  recorded or spoken take so it matches the fit check length without re-recording.
- **Exit dialog bigger** (D-54): the "Exit Auto Flow?" dialog is now 85% of the screen width with larger text
  and full-width Exit / Stay buttons, so it is easy to tap on small phones.

## v0.2.5

- **Share-sheet → Inbox auto-navigate** (D-52 bug fix): sharing a file into Auto Flow now jumps straight to
  Projects → Inbox, so the file is immediately visible. Previously the app opened but stayed on the last screen.
- **Exit dialog on Back** (D-52 bug fix): pressing Back from the Home screen now shows "Exit Auto Flow?" /
  "Renders keep going in the background." instead of quitting silently. Exit calls `finishAffinity()` on Android.
- **Three-pane layout ≥ 840 dp** (5A-6, D-52): on wide tablets and desktop windows wider than 840 dp a compact
  60 dp icon-only rail (🏠 📁 📚 ⚙) appears alongside the content, leaving more room for the work area.
- **SmartFit SPEED** (5B-6, D-53): when a voice take runs over the scene length, Fit check can speed up the
  voice (up to 1.5×) using FFmpeg `atempo` chains, so the voice fits without re-recording.
- **SmartFit SLOW_VIDEO** (5B-6, D-53): alternatively, the scene video can be slowed down (down to 0.7×) using
  `setpts` so the voice and video end at the same time.
- **Brand Kit watermark render layer** (5B-3, D-53): when a watermark file is set it is composited as an IMAGE
  layer in the bottom-right corner (22 % wide, 14 % tall, 75 % opacity) of every rendered frame.
- **Per-chunk caption timing edit** (D-53): Recap → Voice → Speak tab shows Start / End time fields for each
  segment; tap Save to shift the caption burn-in timing without re-rendering the whole video.
- **QuickRecap stage list live** (5B-11, D-53): the stage list in Home → QuickRecap now updates in real time as
  AI jobs run (Speak All, TTS), showing state, items left, and ETA.

## v0.2.4

- **Brand Kit** (5B-3, D-51): Library → save the current project's caption style and colours as a Brand Kit;
  one-tap apply restores caption style and copies logo/watermark files into the project. Also save Brand Kit
  directly from Recap → 5 · Fit check.
- **Caption style presets** (5B-8, D-51): Library → save the current caption style as a preset; apply restores
  size, colour, and box. Also save from the Fit check's caption controls.
- **Library BGM picker** (5B-4, D-51): Library → Music → "Set as BGM" copies the track to the project and wires
  it into the Recap audio settings.
- **Two-pass loudnorm** (5B-4, D-51): when "Even loudness" is on, the render now runs a fast measure pass first
  (audio only) to get accurate LUFS numbers, then applies them in the main render. Falls back to single-pass
  when the measure pass fails.
- **Background image** (5B-2, D-51): Recap → 5 · Fit check → Space around the movie → "Background image" lets
  you pick a still image (PNG/JPG) that fills the frame behind the movie. The image is copied into the project.
- **Android WorkManager AI chain** (5A-5, D-51): AI jobs (Speak All / TTS) now run in a WorkManager foreground
  worker on the "ai" chain, separate from the render chain, so both can run at once and TTS keeps going with
  the screen off. The job resumes where it stopped after a process kill (each spoken segment is crossed off as
  it finishes).
- **Speak All — ETA before start** (5A-5, D-51): Recap → Voice → "Speak all" shows an ETA card ("N scenes,
  ~T min — Start / Cancel") before the loop begins, so the owner knows how many Gemini requests will be used.

## v0.2.3

- **Hide things on the movie** (5B-2, D-50): Recap → 5 · Fit check → blur boxes for the movie's own subtitles
  (bottom strip), a top strip and logos (top-left / top-right), Light / Strong blur, ▲ ▼ Taller / Shorter to move
  each box, and a frame of the movie with the boxes drawn on it. The boxes follow the movie at any video shape.
- **Space around the movie**: Black bars · Blurred movie · Colour, for a wide movie in a 9:16 video (Fill frame
  still crops instead).
- **Captions on the box**: with a bottom box, the captions sit on it (switch to turn off).
- **Caption style** (5B-8): Small / Medium / Large, White / Yellow / Cyan, and "Dark box behind the captions". The
  `.srt` uses the same lines as the burned-in captions.
- **Cut long pauses in new takes** (5B-4): Sound → switch; pauses longer than 0.35 s become 0.2 s when you record,
  import or speak a take, so the fit check shows the real length.

## v0.2.2

- **Built-in Burmese fonts** (D-49): Pyidaungsu and Noto Sans Myanmar (regular + bold) ship inside the app, so the
  app text, lyrics and captions show Burmese without adding fonts in Settings. Fonts added in Settings still work.
- **Jobs tab** (5A-5): lists everything that is running — finding scenes, writing the script, voice, storyboard and
  renders (with progress) — with Open and Cancel; the tab badge counts them. No more "not built yet".
- **Settings in Studio v2**: Home → ⚙ Settings. Home also lists recent projects (tap to open).
- **Project screens in Studio v2**: a project bar with ← Projects and chips for every screen (Movie Recap: Recap ·
  Export; Music Video: Input · Storyboard · Shots · References · Flow Handoff · Inbox · Export). Android Back steps
  back (Settings → project screen → project list → Home) instead of leaving the app.
- **Sound for Movie Recap** (5B-4): Recap → 5 · Fit check has "Clean up the voice" (on), "Even loudness −14 LUFS"
  (on), and background music (Add music…, Quiet / Medium / Loud, lower the music while the voice speaks). Applied
  when the video is rendered.

## v0.2.1 (bug fixes)

- **Old Movie Recap projects open again**: v0.2.0 migrated them to recap v2 on open, but the app still reads v1, so
  they failed with "schemaVersion 2; this app supports up to 1". Migration on open is off until the v2 reader lands;
  opening such a project puts `project.v1.bak.json` back (the v2 file is kept as `project.v2.json`).
- **Quick Recap names**: new projects are "Quick Recap", "Quick Recap 2", … instead of a timestamp.

## v0.2.0 (Phase 5 — Studio v2)

### New features
- **Studio v2 navigation** (5A-6): Home · Projects · Library · Jobs with bottom nav / rail / three-pane adaptive layout
- **AI Jobs** (5A-5): AiJob model, RpmPacer, AiJobStore — resumable AI pipelines with RPM pacing
- **Layer system** (5A-4): BLUR / IMAGE / TEXT / BORDER / BACKGROUND layers, LayerCompiler with ffmpeg filtergraphs
- **Library** (5A-7): BrandKits, Voices, Lexicon, Music, Fonts, Presets store
- **Live preview geometry** (5A-8): PreviewGeometry — drag, pinch, clamp, pixel conversion
- **Export pre-flight** (5B-9): PreflightCheck object with 7 blocking checks
- **Publish Kit UI** (5B-10): edit title / description / hashtags, copy each, share text file
- **Register & Lexicon UI** (5B-7): SPOKEN / WRITTEN chips, shared lexicon in Gemini prompt
- **Export quality picker** (5B-9): AUTO / 720p / 1080p / 1440p chips
- **Feature-flag toggles** (5A-3): Preview features section in Settings
- **Recap v2 migration** (5A-2): migration-on-open guard
- **Quick Recap model** (5B-11): `recap.quick` wizard stages (SCENES → DONE)
- **Subtitle tracks + Transcript** (5B-1): SubtitleTrack / Transcript models, `recap.subtitleTrack/transcript`
- **RecapRegister + Lexicon** (5B-7): SpokenText lexicon application, register in script
- **RecapV2 migration helper** (5A-2): `RecapMigration.migrateIfNeeded`

### Flags enabled in this release
- `studioV2 = true` (Studio v2 navigation and feature set)
- `quickRecap = false` (enabled per-device via Preview features)

### Internal
- `RpmPacer`: token-bucket rolling-60s RPM limiter
- `AiJobStore`: persistent job files at `filesDir/ai-jobs/<id>.json`
- `LibraryStore`: atomic save of library.json
- `PreviewGeometry`: pure geometry helpers for Compose layer overlay
- All new units covered by tests in `:core:check`
