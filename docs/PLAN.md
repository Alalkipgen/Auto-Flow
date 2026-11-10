# Auto Flow — Plan

Status legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[!]` blocked

## Phase 0 — Spikes (must pass before Phase 1 UI work)
Goal: de-risk the three things that can invalidate the architecture. Each spike is a
throwaway module under `spikes/` with a README recording the result.

- [x] Bootstrap: Gradle/KMP skeleton (core, ai, media, platform, ui, app-desktop, app-android), version catalog, desktop shell window, CI (D-13)
- [x] S1 Gemini free-tier text: generate a Burmese storyboard JSON (schema in specs/prompt-compiler.md §1) from sample lyrics using a user API key. Record: model used, latency, rate-limit headers, Burmese quality notes. → PASS with `gemini-3.5-flash` (12.6 s / 18.4 s); frequent 503s; no rate-limit headers. See spikes/s1-gemini-text/README.md.
- [x] S2 Gemini free-tier image: generate one 9:16 reference image from an English prompt. Record: daily/minute limits observed, output size. → Free tier blocked (every image model `limit: 0`, runs #1–#5); resolved by D-14 (manual reference-image handoff, API image provider optional). See spikes/s2-gemini-image/README.md.
- [x] S3 Compose Desktop video playback: play a local mp4 in a Compose window (candidate: VLCJ). Record: bundle size impact, seeking, 9:16 rendering. → PASS 7/7 on Linux and Windows (VLCJ callback surface → Compose Image); trimmed VLC ≈ 95 MB + 3.7 MB JARs (Q-05). See spikes/s3-vlcj-playback/README.md.
- [~] S4 Burmese text in Compose Desktop and in `ffmpeg drawtext` (Pyidaungsu / Noto Sans Myanmar). Record screenshots; confirm stacked consonants/medials render correctly.
- [ ] S5 Gemini TTS: synthesize one Burmese sentence; attempt voice replication once. Record whether replication is available to this account. (Optional — non-blocking.)

Exit criteria: S1–S4 pass. If S3 fails, see DECISIONS.md D-07 fallback.

## Phase 1 — Windows MVP (fake providers first, then real Gemini)
Goal: full MV pipeline end-to-end on Windows with manual Flow handoff.

### 1A :core
- [ ] Project folder create/open, `project.json` atomic I/O (specs/project-format.md)
- [ ] ArtifactStore: put(bytes/kind) → content-hash id, get, list by shot
- [ ] Domain models: Project, Shot, ShotSpec, GlobalStyle, Character, Artifact, Approval
- [ ] WorkflowRules: gate checks + stale detection (specs/workflow-and-approval.md)
- [ ] PromptCompiler: ShotSpec → PromptIR → Veo/Flow dialect string + golden tests
- [ ] Shot auto-splitting from duration + clip length + section hints

### 1B :ai
- [ ] Interfaces: TextProvider, ImageProvider, TtsProvider, TranscribeProvider
- [ ] Fake* providers (deterministic, offline, with artificial latency)
- [ ] GeminiTextProvider (structured JSON output), retry/backoff, daily counter — per D-15
- [ ] GeminiImageProvider — optional, capability-gated by a quota probe (D-14)
- [ ] `config/models.json` loader + ModelCapability

### 1C :ui (desktop)
- [ ] Shell: sidebar (pipeline order), stepper, title-bar provider status
- [ ] Input screen (lyrics, duration, song style, video style, ratio, clip length, mood)
- [ ] Storyboard screen (Burmese cards, edit, regenerate, approve all / per shot)
- [ ] Shots screen (grid, badges: Accepted / Needs Flow / Stale / Draft)
- [ ] Shot detail panel: PromptIR view, Ref A/B picker, handoff buttons
- [ ] Flow Handoff screen: copy prompt, open Flow, export shot package, import video, draft/final
- [ ] Reference-image handoff (D-14): copy ImageDialect prompt, open AI Studio, import image as Ref A/B
- [ ] Inbox: folder watcher → "attach to shot" dialog
- [ ] Settings: API key (secure storage), ffmpeg path, fonts, model config

### 1D :media (desktop)
- [ ] ffmpeg locator + version check
- [ ] RenderGraph → filtergraph compiler (concat, trim, audio, text overlay)
- [ ] Preview (proxy 540p) and Export (1080p) presets: 16:9, 9:16, 1:1
- [ ] End-frame extraction for shot chaining

Exit criteria: create project → storyboard → approve → prompts → ref images → handoff 3 shots to Flow by hand → import → export mp4 with music and lyric overlay.

## Phase 2 — Quality
- [ ] Character Bible + token expansion in compiler
- [ ] Lyrics timing via Gemini transcription (fallback: manual tap-to-sync)
- [ ] Draft (Fast) / Final (Quality) two-pass per shot
- [ ] Batch handoff sheet (HTML export of all pending shots)
- [ ] Flow credit manual counter
- [ ] Version browser per shot (artifact history)
- [ ] Beat detection → snap cut points (optional)

## Phase 3 — Android companion
- [ ] Shared Compose UI on Android: Projects, Storyboard (edit/approve), Shots, Inbox, Settings
- [ ] Project folder via SAF; Syncthing documented as the sync method
- [ ] Share-sheet import → Inbox
- [ ] No rendering on Android (preview only via Media3)

## Phase 4 — Movie Recap mode
- [ ] Import video, scene detect (ffmpeg scdet), scene picker, target duration
- [ ] Recap script (Burmese, commentary-style option) + approval
- [ ] VoiceSource: Recorded | Tts | Replicated(optional, capability-gated)
- [ ] Narration-to-scene fit check, captions (SRT + burn-in), visual transform presets
- [ ] Rights/usage disclaimer in UI

## Open Questions
- Q-02 S4: Linux FFmpeg 5.1 and stock 7.1.1 drawtext showed incorrect stacked characters; an experimental Myanmar-script patch improved the sample. What supported approach handles Burmese and mixed-script text for Windows export? See spikes/s4-burmese-rendering/README.md.
- Q-01 Windows installer: spec asks for MSIX, but Compose `jpackage` cannot produce MSIX. Bootstrap configures MSI. Decide: keep MSI, or add a separate MSIX packaging step later (e.g. MSIX Packaging Tool / `makeappx`).
- ~~Q-03 S2: Gemini image models have zero free-tier quota for this key.~~ Resolved → D-14.
- ~~Q-04 S1: model ids drift and frequent 503s.~~ Resolved → D-15.
- Q-05 S3 packaging: ship a trimmed portable VLC 3.0.x (≈95 MB, verified) inside the MSI, or require an installed VLC? Also decide on further `codec/` trimming and record VLC plugin licences (LGPL/GPL) in the About screen.
- (agents append here)

## Changelog
- 2026-11-10 plan created
- 2026-11-10 phase 0 bootstrap: Gradle/KMP skeleton (D-13)
- 2026-11-10 S4 partial: Compose and FFmpeg Linux rendering comparison; FFmpeg 5.1 drawtext needs shaping follow-up (Q-02).
- 2026-11-10 S1 pass (gemini-3.5-flash); S2 blocked on free-tier image quota (Q-03); model drift noted (Q-04).
- 2026-11-10 D-14 (manual reference-image handoff) and D-15 (text provider resilience) recorded; S2 closed via D-14.
- 2026-11-10 S3 pass: VLCJ playback, pause/seek, and 9:16 verified on Linux and Windows CI; bundle size measured (Q-05).
