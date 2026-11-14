# Changelog

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
