# Auto Flow — Plan

Status legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[!]` blocked

## Phase 0 — Spikes (must pass before Phase 1 UI work)
Goal: de-risk the three things that can invalidate the architecture. Each spike is a
throwaway module under `spikes/` with a README recording the result.

- [x] Bootstrap: Gradle/KMP skeleton (core, ai, media, platform, ui, app-desktop, app-android), version catalog, desktop shell window, CI (D-13)
- [ ] S1 Gemini free-tier text: generate a Burmese storyboard JSON (schema in specs/prompt-compiler.md §1) from sample lyrics using a user API key. Record: model used, latency, rate-limit headers, Burmese quality notes.
- [ ] S2 Gemini free-tier image: generate one 9:16 reference image from an English prompt. Record: daily/minute limits observed, output size.
- [ ] S3 Compose Desktop video playback: play a local mp4 in a Compose window (candidate: VLCJ). Record: bundle size impact, seeking, 9:16 rendering.
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
- [ ] GeminiTextProvider (structured JSON output), retry/backoff, daily counter
- [ ] GeminiImageProvider
- [ ] `config/models.json` loader + ModelCapability

### 1C :ui (desktop)
- [ ] Shell: sidebar (pipeline order), stepper, title-bar provider status
- [ ] Input screen (lyrics, duration, song style, video style, ratio, clip length, mood)
- [ ] Storyboard screen (Burmese cards, edit, regenerate, approve all / per shot)
- [ ] Shots screen (grid, badges: Accepted / Needs Flow / Stale / Draft)
- [ ] Shot detail panel: PromptIR view, Ref A/B picker, handoff buttons
- [ ] Flow Handoff screen: copy prompt, open Flow, export shot package, import video, draft/final
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
- Q-02 S4: Linux FFmpeg 5.1 drawtext failed the stacked-character visual check. Which HarfBuzz-enabled FFmpeg build should be tested for Windows export? See spikes/s4-burmese-rendering/README.md.
- Q-01 Windows installer: spec asks for MSIX, but Compose `jpackage` cannot produce MSIX. Bootstrap configures MSI. Decide: keep MSI, or add a separate MSIX packaging step later (e.g. MSIX Packaging Tool / `makeappx`).
- (agents append here)

## Changelog
- 2026-11-10 plan created
- 2026-11-10 phase 0 bootstrap: Gradle/KMP skeleton (D-13)
- 2026-11-10 S4 partial: Compose and FFmpeg Linux rendering comparison; FFmpeg 5.1 drawtext needs shaping follow-up (Q-02).
