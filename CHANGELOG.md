# Changelog

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
