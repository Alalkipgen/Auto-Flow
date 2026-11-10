# Auto Flow — Plan

Status legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[!]` blocked

## Phase 0 — Spikes (must pass before Phase 1 UI work)
Goal: de-risk the three things that can invalidate the architecture. Each spike is a
throwaway module under `spikes/` with a README recording the result.

- [x] Bootstrap: Gradle/KMP skeleton (core, ai, media, platform, ui, app-desktop, app-android), version catalog, desktop shell window, CI (D-13)
- [x] S1 Gemini free-tier text: generate a Burmese storyboard JSON (schema in specs/prompt-compiler.md §1) from sample lyrics using a user API key. Record: model used, latency, rate-limit headers, Burmese quality notes. → PASS with `gemini-3.5-flash` (12.6 s / 18.4 s); frequent 503s; no rate-limit headers. See spikes/s1-gemini-text/README.md.
- [x] S2 Gemini free-tier image: generate one 9:16 reference image from an English prompt. Record: daily/minute limits observed, output size. → Free tier blocked (every image model `limit: 0`, runs #1–#5); resolved by D-14 (manual reference-image handoff, API image provider optional). See spikes/s2-gemini-image/README.md.
- [x] S3 Compose Desktop video playback: play a local mp4 in a Compose window (candidate: VLCJ). Record: bundle size impact, seeking, 9:16 rendering. → PASS 7/7 on Linux and Windows (VLCJ callback surface → Compose Image); trimmed VLC ≈ 95 MB + 3.7 MB JARs (Q-05). See spikes/s3-vlcj-playback/README.md.
- [x] S4 Burmese text in Compose Desktop and in ffmpeg overlays (Pyidaungsu / Noto Sans Myanmar). Record screenshots; confirm stacked consonants/medials render correctly. → PASS on Linux and Windows CI via libass `ass=` (primary) and Compose/Skia PNG + `overlay` (fallback); plain `drawtext` is correct only on FFmpeg ≥ 9.0 (≤ 8.0 forces Latin script) and is not used. See spikes/s4-burmese-rendering/README.md.
- [ ] S5 Gemini TTS: synthesize one Burmese sentence; attempt voice replication once. Record whether replication is available to this account. (Optional — non-blocking.)

Exit criteria: S1–S4 pass. If S3 fails, see DECISIONS.md D-07 fallback.

## Phase 1 — Windows MVP (fake providers first, then real Gemini)
Goal: full MV pipeline end-to-end on Windows with manual Flow handoff.

### 1A :core
- [x] Project folder create/open, `project.json` atomic I/O (specs/project-format.md) — `ProjectStore` over the `core.io.FileSystem` port
- [x] ArtifactStore: put(bytes/kind) → content-hash id, get, list by shot
- [x] Disk `FileSystem` implementation (fsync via `FileChannel.force`, `ATOMIC_MOVE`) for desktop + Android → `:platform` `JvmFs` in 1C-a (D-18)
- [x] Domain models: Project, Shot, ShotSpec, GlobalStyle, Character, Artifact, Approval (`core/model/`, strict `CoreJson`, fixture `fixtures/project-v1.json`)
- [x] WorkflowRules: gate checks + stale detection (specs/workflow-and-approval.md) — `WorkflowRules`, `Stale`, `approveAll`
- [x] PromptCompiler: ShotSpec → PromptIR → Veo/Flow dialect string + golden tests (`VeoFlowDialect`, `ImageDialect`; goldens: narrative, performance, abstract)
- [x] Shot auto-splitting from duration + clip length + section hints (`ShotSplitter`: remainder ≥ 2 s own shot, else merged; 1.5 s section snap)

### 1B :ai
- [x] Interfaces: TextProvider, ImageProvider, TtsProvider, TranscribeProvider (`AiResult` incl. `Deferred`)
- [x] FakeTextProvider (schema-driven JSON, deterministic, offline, artificial latency, simulated 503) + ManualImageProvider
- [x] FakeImageProvider (PNG), FakeTtsProvider (WAV), FakeTranscribeProvider (pure-Kotlin PNG/WAV codecs)
- [x] ModelChain (candidates chain, backoff, short-429 wait, 120 s attempt timeout, cancellation) + UsageCounter — per D-15
- [x] GeminiTextProvider (structured JSON output) on ModelChain; opt-in live test (`-PliveAi=true` + `GEMINI_API_KEY`)
- [!] GeminiImageProvider — deferred (D-14); keep the `ImageProvider` interface, ship `ManualImageProvider` (prompt → clipboard, returns `AiResult.Deferred`)
- [x] `config/models.json` loader + ModelCapability (lenient; placeholders dropped; image stays MANUAL unless configured and probed)
- [x] ProviderRegistry + Koin `aiModule`: `AiHost` port (settings flag, API key, clipboard, open-URL); fake/real chosen per call; real TTS/transcribe answer `unavailable` until phase 4

### 1C :ui (desktop)
- [x] `:platform`: `JvmFs` (shared `jvmShared` source set, desktop + Android), AppDirs, Clipboard, UrlOpener, FilePicker (desktop) (D-18) — `PlatformResult`; one contract test for `InMemoryFileSystem` and `JvmFs`
- [ ] `:platform` SecureStorage: Windows DPAPI via JNA, `~/.autoflow/secrets.bin` (D-19); Windows CI job
- [ ] Dashboard: project list from `~/.autoflow/projects.json` (D-20), New Music Video, Open folder
- [ ] Shell: sidebar (pipeline order), stepper, title-bar provider status
- [ ] Input screen (lyrics, duration, song style, video style, ratio, clip length, mood)
- [ ] Storyboard screen (Burmese cards, edit, regenerate, approve all / per shot)
- [ ] Shots screen (grid, badges: Accepted / Needs Flow / Stale / Draft)
- [ ] Shot detail panel: PromptIR view, Ref A/B: Copy image prompt · Import image (file/inbox), handoff buttons
- [ ] Flow Handoff screen: copy prompt, open Flow, export shot package, import video, draft/final
- [ ] Reference-image handoff (D-14): copy ImageDialect prompt, open AI Studio, import image as Ref A/B
- [ ] Inbox: folder watcher → "attach to shot" dialog (video → `vd`, png/jpg → Ref A/B `im`)
- [ ] Settings: API key (secure storage), Fake/Real AI providers toggle (`AiHost.useFakeProviders`), ffmpeg path, VLC path (auto-detect; "Install VLC" link + playback disabled when missing, D-16), fonts, model config

### 1D :media (desktop)
- [ ] ffmpeg locator + version check (`ass` filter / `--enable-libass` → overlay backend)
- [ ] RenderGraph → filtergraph compiler (concat, trim, audio, text overlay via libass; Skia PNG fallback)
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
- ~~Q-02 S4: supported approach for Burmese text in exports?~~ Resolved → libass primary, Skia PNG overlay fallback (S4 README); specs updated (render-graph.md overlays, flow-handoff.md image inbox, ai-providers.md ManualImageProvider).
- Q-01 Windows installer: spec asks for MSIX, but Compose `jpackage` cannot produce MSIX. Bootstrap configures MSI. Decide: keep MSI, or add a separate MSIX packaging step later (e.g. MSIX Packaging Tool / `makeappx`).
- ~~Q-03 S2: Gemini image models have zero free-tier quota for this key.~~ Resolved → D-14.
- ~~Q-04 S1: model ids drift and frequent 503s.~~ Resolved → D-15.
- ~~Q-05 S3 packaging: bundle trimmed VLC or require an installed VLC?~~ Resolved → D-16 (separate install).
- ~~Q-06 1A: where should the disk `FileSystem` implementation live?~~ Resolved → D-18 (`:platform` `JvmFs`, port stays in `:core`).
- ~~Q-07 1A: sidecar naming `<kind>_<hash12>.meta.json`?~~ Resolved → accepted; project-format.md §1 made explicit; `ArtifactStore.scan()` rebuilds the list from the folder.
- ~~Q-08 1A: user-uploaded refs always stale?~~ Resolved → stale only when a recorded input exists and differs; user-provided artifacts record no inputs and are never stale (workflow-and-approval.md, `Stale`).
- (agents append here)

## Changelog
- 2026-11-10 plan created
- 2026-11-10 phase 0 bootstrap: Gradle/KMP skeleton (D-13)
- 2026-11-10 S4 partial: Compose and FFmpeg Linux rendering comparison; FFmpeg 5.1 drawtext needs shaping follow-up (Q-02).
- 2026-11-10 S1 pass (gemini-3.5-flash); S2 blocked on free-tier image quota (Q-03); model drift noted (Q-04).
- 2026-11-10 D-14 (manual reference-image handoff) and D-15 (text provider resilience) recorded; S2 closed via D-14.
- 2026-11-10 S3 pass: VLCJ playback, pause/seek, and 9:16 verified on Linux and Windows CI; bundle size measured (Q-05).
- 2026-11-10 S4 pass: libass (primary) and Skia PNG overlay (fallback) verified on Linux and Windows; Q-02 closed. Phase 0 exit criteria met (S1–S4).
- 2026-11-10 D-14 follow-ups in 1B/1C; D-15 amended (120 s + Cancel, `candidates` chain); D-16 VLC separate install (Q-05 closed).
- 2026-11-10 Specs updated for D-14/D-15/S4: flow-handoff (image import/inbox), ai-providers (Deferred, ManualImageProvider, candidates chain, 120 s + Cancel), render-graph (libass primary, Skia PNG fallback, no drawtext).
- 2026-11-10 Phase 0 merged (PR #2); S3 manual Windows audio check deferred by owner. 1A domain models added.
- 2026-11-10 1A ProjectStore (atomic save + .bak, crash-safe) and ArtifactStore (content-hash, write-once, dedupe); pure-Kotlin SHA-256; Q-06, Q-07.
- 2026-11-10 1A ShotSplitter.
- 2026-11-10 1A WorkflowRules gates, Stale functions, approve-all; Q-08.
- 2026-11-10 1A PromptCompiler, VeoFlowDialect, ImageDialect + 3 golden cases. 1A :core complete except the disk FileSystem (Q-06).
- 2026-11-10 1A merged (PRs #3–#8). 1B interfaces, ModelConfig loader, ModelCapability; `config/models.json` candidates from S1.
- 2026-11-10 1B interfaces merged (PR #9). FakeTextProvider + ManualImageProvider; D-17 test-only libraries.
- 2026-11-10 1B fake media providers: placeholder PNG (shot number), sine-tone WAV, timed fake transcript.
- 2026-11-10 1B ModelChain + UsageCounter (quota day = midnight Pacific).
- 2026-11-10 1B GeminiTextProvider (Ktor, header key, thought parts skipped, `limit: 0` → next candidate); tests on real S1/S2 responses via ktor-client-mock (D-17).
- 2026-11-10 1B ProviderRegistry + Koin aiModule. 1B complete (GeminiImageProvider deferred per D-14).
- 2026-11-10 Q-06/Q-07/Q-08 resolved: D-18 (FileSystem port + `:platform` JvmFs), sidecar naming + `ArtifactStore.scan()`, stale only on recorded inputs; D-19 DPAPI SecureStorage; D-20 JSON project index.
- 2026-11-10 PR #16 merged. 1C-a2 `:platform` part 1: `JvmFs` (fsync + `ATOMIC_MOVE`, short retry on Windows `AccessDenied`), `PlatformResult`, `AppDirs` (`~/.autoflow/{projects,settings,usage}.json`, `secrets.bin`; `-Dautoflow.home` override); contract + disk round-trip tests; CI runs `:platform:check` and compiles `androidMain`. 1A complete.
- 2026-11-10 1C-a2 `:platform` part 2: `AwtClipboard` (retry while Windows locks the clipboard), `DesktopUrlOpener` (http/https only, no userinfo; AWT `Desktop` or OS opener without a shell), `SwingFilePicker` (native file dialog, Swing folder chooser, extension filter); headless tests report `Unsupported`.
