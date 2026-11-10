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
- [~] S6 Android on-device feasibility (D-22/D-23), shipped inside the app: hidden Self-test (Settings → long-press version) extracts fixtures (two 8 s 9:16 clips, 20 s mp3, the S4 Burmese .ass, a Burmese font), runs `ffmpeg -version` (`ass`, `xfade`, `libx264`, `aac`), renders concat → 540p, + .ass overlay, + mp3 mix → 1080p in a foreground service, shows one frame of each, measures wall time and peak RSS, writes `selftest/report.md` (Copy / Share); exec failure → exact error + Media3 Transformer fallback. Built by CI (D-24); the owner pastes the report back — Self-test shipped (`:media` `selftest/` report + verdict, `app-android` `selftest/` foreground service with wake lock and Cancel, Media3 path also on demand); waiting for the owner's report from v0.1.0

Exit criteria: S1–S4 pass. If S3 fails, see DECISIONS.md D-07 fallback.

## Phase 1 — MVP on Android and Windows (fake providers first, then real Gemini)
Goal: full MV pipeline end-to-end with manual Flow handoff — on the owner's Android phone (D-22) and on Windows (verified by CI).

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
- [x] `:platform` SecureStorage: Windows DPAPI via JNA, `~/.autoflow/secrets.bin` (D-19); Windows CI job — `FileSecureStorage` + `DpapiCipher`, `desktopSecureStorage(appDirs)`
- [x] Dashboard: project list from `~/.autoflow/projects.json` (D-20), New Music Video, Open folder — `DashboardScreen` + `DashboardModel`; New Movie Recap shown disabled until Phase 4
- [ ] UI Burmese font (DESIGN.md §1): Pyidaungsu → Noto Sans Myanmar from the Settings fonts for UI text, else the system fallback (Windows: Myanmar Text); screenshot test with a bundled test font
- [x] Shell: sidebar (pipeline order), stepper, title-bar provider status — `:core` `Pipeline` → `ShellModel`; locked entries dimmed with 🔒 and the reason on hover/click; stepper on pipeline screens; running AI job + Cancel in a shell-level `JobBanner`
- [x] Input screen (lyrics, duration, song style, video style, ratio, clip length, mood)
- [x] Storyboard screen (Burmese cards, edit, regenerate, approve all / per shot)
- [~] Shots screen (grid, badges: Accepted / Needs Flow / Stale / Draft) — `:core` `ShotStatus` badge done
- [ ] Shot detail panel: PromptIR view, Ref A/B: Copy image prompt · Import image (file/inbox), handoff buttons
- [ ] Flow Handoff screen: copy prompt, open Flow, export shot package, import video, draft/final
- [ ] Reference-image handoff (D-14): copy ImageDialect prompt, open AI Studio, import image as Ref A/B
- [ ] Inbox: folder watcher → "attach to shot" dialog (video → `vd`, png/jpg → Ref A/B `im`)
- [x] Settings: API key (secure storage), Fake/Real AI providers toggle (`AiHost.useFakeProviders`), ffmpeg path, VLC path (auto-detect; "Install VLC" link + playback disabled when missing, D-16), fonts, model config — `SettingsScreen` + `SettingsModel`; `SettingsUiState.vlcDir` is what playback (1D) checks

### 1D :media (desktop; the compiler is shared with Android, D-23)
- [ ] ffmpeg locator + version check (`ass` filter / `--enable-libass` → overlay backend)
- [ ] RenderGraph → filtergraph compiler in `:media` commonMain (concat, trim, audio, text overlay via libass; Skia PNG fallback) — one compiler for desktop and Android, no duplicated filtergraph code
- [ ] Preview (proxy 540p) and Export (1080p) presets: 16:9, 9:16, 1:1
- [ ] End-frame extraction for shot chaining

### 1F Android parity (was Phase 3, D-22)
- [~] CI debug APK artifact + GitHub Release on `v0.*` tags, stable signing from Actions secrets (D-24) — `android` job done; stable signing starts once the owner adds the four `ANDROID_*` secrets
- [x] Bundled ffmpeg/ffprobe for arm64-v8a built from source in CI (D-23); README GPL note — `tools/ffmpeg-android/` (pinned versions + SHA-256), CI cache keyed on the script; APK is arm64-v8a only with extracted native libs
- [~] Android bindings: AppDirs + project folders under the external files dir, `JvmFs`, Clipboard, UrlOpener, ToolLocator (bundled ffmpeg), bundled `config/models.json`; crash text shown with Copy/Share on the next launch — all but the project folders done (`androidModule`; AppDirs in the app files dir; file picker reports Unsupported until the system-picker item)
- [ ] SecureStorage on Android Keystore AES-GCM (D-25)
- [ ] File import via the system picker (video → `vd`, image → Ref A/B); SAF "Open folder" optional
- [ ] Share-sheet import → Inbox
- [ ] Phone layout for every screen (DESIGN.md §4: bottom nav, single column)
- [ ] `:media` Android actuals: FfmpegRunner (bundled binary, progress from stderr), MediaProbe (ffprobe, else MediaMetadataRetriever), EndFrame, VideoPlayer = ExoPlayer composable (D-25)
- [ ] Render in a WorkManager foreground worker (notification + progress, survives backgrounding, cancellable, one at a time) → `<project>/exports/`; warn when battery < 20 % and not charging; Preview 540p / Export 1080p, same presets as desktop
- [ ] Settings → "Backup all projects" (zip → share sheet), so a reinstall never loses data (D-25)

Exit criteria: create project → storyboard → approve → prompts → ref images → handoff 3 shots to Flow by hand → import → export mp4 with music and lyric overlay — on the owner's phone, and on Windows in CI.

## Phase 2 — Quality
- [ ] Character Bible + token expansion in compiler
- [ ] Lyrics timing via Gemini transcription (fallback: manual tap-to-sync)
- [ ] Draft (Fast) / Final (Quality) two-pass per shot
- [ ] Batch handoff sheet (HTML export of all pending shots)
- [ ] Flow credit manual counter
- [ ] Version browser per shot (artifact history)
- [ ] Beat detection → snap cut points (optional)

## Phase 3 — Android companion (superseded by D-22: merged into Phase 1 as 1F Android parity)
- ~~Shared Compose UI on Android: Projects, Storyboard (edit/approve), Shots, Inbox, Settings~~ → 1F (every screen)
- ~~Project folder via SAF~~ → 1F (external files dir by default, SAF optional, D-25); Syncthing stays the documented sync method
- ~~Share-sheet import → Inbox~~ → 1F
- ~~No rendering on Android (preview only via Media3)~~ — superseded by D-22/D-23: Android renders with the bundled ffmpeg

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
- 2026-11-10 1C-a3 SecureStorage (D-19): JNA 5.17.0 (desktop only); `secrets.bin` = `AFS1` + DPAPI blob (current user, app entropy, no UI) of a name → value map, atomic write; unreadable file → `Failed` and replaced by the next save, I/O error never overwrites; messages never quote decrypted text; non-Windows → `Unsupported`. New `windows` CI job runs `:platform:desktopTest` with real DPAPI.
- 2026-11-10 1C-a4 Settings part 1: `AppSettings` + `SettingsStore` (`~/.autoflow/settings.json`, atomic, lenient; first run = Fake mode; unreadable file → defaults + warning, replaced on next change) and `loadModelConfig` (override path from Settings, else the bundled `config/models.json`, with a problem message). `:ui` applies the kotlinx.serialization plugin (D-11).
- 2026-11-10 1C-a4 Settings part 2: `AppAiHost` (mode from settings, key from SecureStorage per call, clipboard/URL from `:platform`), live title-bar status (`AI ● Fake (offline)` / `Gemini API ● Free tier` / `No key` / `Windows only` · `Flow ● Manual`), Koin: `desktopModule` + `uiModule` + `aiModule`; `config/models.json` bundled in `app-desktop`; graph test with the real desktop bindings in CI.
- 2026-11-10 1C-a4 Settings part 3a: `SettingsModel` (key Save/Test/Remove — Test always calls Gemini once, even in Fake mode; Fake/Real toggle; default ratio/clip; ffmpeg path + Detect/Browse; VLC path/Browse/"Install VLC" link; fonts; models.json override; today's request count), `:platform` `ToolLocator` (PATH/`FFMPEG_PATH`, common VLC folders) and `ProviderRegistry.testGeminiKey()`.
- 2026-11-10 1C-a4 Settings part 3b-1: foundation-only controls (`AfText`, `AfButton` Primary/Secondary/Flow/Danger, `AfChip`, `AfSwitch`, `AfTextField` with masked secret mode, `AfCard`) and sidebar navigation with the selected entry highlighted (mockup `.side a.on`); unbuilt screens show a placeholder.
- 2026-11-10 1C-a4 Settings part 3b-2: `SettingsScreen` (AI card: fake switch, masked key field, Save/Test/Remove, today's requests; ratio/clip defaults; ffmpeg Detect/Browse; VLC folder + "Install VLC" when missing; fonts; models.json source, problem, override and read-only view), opened from the sidebar. Settings item done.
- 2026-11-10 1C-a5 Dashboard part 1: `ProjectIndex` (`~/.autoflow/projects.json`, D-20: path, name, mode, ratio, accepted/total shots, last opened; most recent first; unreadable file → empty list + warning), `DashboardModel` (New Music Video in `<parent>/<name>.autoflow` with the Settings defaults, Open folder, missing-folder rows, remove from list, corrupt-project message mentions the backup), `CurrentProject` holder; `AppSettings.projectsDir` remembers the last parent folder.
- 2026-11-10 1C-a5 Dashboard part 2: `DashboardScreen` (Projects header with Open folder / New Movie Recap (Phase 4, disabled) / New Music Video; inline new-project card with name, folder + Browse and a "Creates …" preview; rows with mode, ratio, accepted/total shots, relative last-opened time; missing folders show "Folder not found" + Remove from list); opening a project goes to Input and shows its name in the title bar. Dashboard item done. Noted a UI Burmese font task (headless Linux has no Myanmar font, so Burmese names render as boxes there).
- 2026-11-10 CI without a PC: the `windows` job also runs `:ui:desktopTest` with `AUTOFLOW_EXPECT_MYANMAR_FONT=1` (a system font must cover Myanmar) and uploads Dashboard/Settings screenshots (`windows-screenshots` artifact); new opt-in `live-ai.yml` runs `GeminiLiveTest` with the repository secret `GEMINI_API_KEY` (Actions → Run workflow, or a change to `.github/live-ai-run.txt`) and fails instead of skipping when the secret is missing.
- 2026-11-10 1C-b part 1: `:ai` `StoryboardGenerator` (Stage A analysis + Stage B one ShotSpec per shot on any TextProvider; prompts from spike S1 with colloquial-Burmese and `{{TOKEN}}` instructions; object-root schema with exact shot count; invalid output retried once with the error, then `Failed("invalid_output")`, never partial; per-shot regenerate keeps the shot number and the user's sceneMy as notes) and `:core` `StoryboardVersion` (the `sb` artifact: analysis id + shots with timing and spec).
- 2026-11-10 1C-b part 2: `ProjectSession` (open project: `source/lyrics.txt`, artifacts, saves that refresh the Dashboard row) and `StoryboardService` (generate all → `an` + `sb` + one `sp` per shot via ShotSplitter with analysis sections, one save; regenerate one shot, optionally keeping the user's sceneMy; edit; approve / approve all via WorkflowRules; switch version; failures and cancellation change nothing) — D-21.
- 2026-11-10 1C-b part 3a: `StoryboardJobs` (one app-wide AI job at a time with label, Cancel and last result, so it survives screen changes), `InputModel` (lyrics/duration/styles/mood are a draft saved on leave or generate; ratio/clip/mode chips save at once; m:ss parsing; blockers from WorkflowRules; edits locked while a job runs) and blocker texts.
- 2026-11-10 1C-b part 3b: `InputScreen` (Lyrics card with optional [Verse]/[Chorus] markers; Song card: duration m:ss, song style, mood; Video card: style chips + custom, ratio, clip length, storyboard mode; blockers in amber; "Storyboard ထုတ်မယ်" opens a confirm card with the shot estimate, AI request count and what becomes stale; running job label + Cancel; draft saved when leaving; Burmese empty state). `AfTextField` gains `lines`, `mono`, `enabled`. Input item done.
- 2026-11-10 1C-b part 4a: `StoryboardModel` (cards rebuilt from the open project after every save: timecode, lyric, spec, version tag with "edited", approval, stale, downstream flag; version list; approve / approve all / edit / switch version run at once, regenerate shot / all as jobs; quick actions wait for a running job). `ProjectSession.state`. Fix: a job cancelled before it started now ends as Cancelled instead of staying busy.
- 2026-11-10 1C-b part 4b: `StoryboardScreen` (header: x/y approved, version chips, Approve all, ↻ Regenerate all behind a confirm that names the kept version and how many shots become stale; cards: #NN, cyan timecode, ✓ Approved / ⚠ stale badges, version tag, lyric, Burmese scene, camera · mood tags; ✎ ပြင်မယ် inline edit of the scene and English fields, ↻ ပြန်ထုတ်မယ် confirm with "keep my Burmese scene", ✓ အတည်ပြုမယ် / Undo approval; running job + Cancel; Burmese empty state with Go to Input). Sidebar Storyboard opens it. Storyboard item done.
- 2026-11-11 1C-c Shell part 1: `:core` `Pipeline` (a step screen opens once one shot can work on it; done = every shot finished with current artifacts; current = first step not done) and `ShellModel` (sidebar locks: no project → every pipeline screen; Shots needs a storyboard, References an approved prompt, Flow Handoff an approved reference, Timeline/Export an accepted video; lock texts; stepper cells).
- 2026-11-11 1C-c Shell part 2: sidebar locks (dimmed + 🔒, `stateDescription = "locked"`; hover or click shows the reason and does not open the screen), mockup stepper (✓ done, current highlighted) above pipeline screens, and the running AI job + Cancel moved from Input/Storyboard into a shell `JobBanner` shown on every screen (Input keeps its button, disabled while a job runs). Windows screenshots add the Storyboard screen. Shell item done.
- 2026-11-11 1C-c Shots part 1: `:core` `ShotStatus` (Draft / Prompt ready / Needs Flow / Imported / Accepted / Stale; computed from pointers, approvals and handoff state; Stale wins) and `ImageHeader` (PNG IHDR / JPEG SOFn size without decoding, ratio match with 3 % tolerance for the "image ratio ≠ project ratio" warning).
- 2026-11-11 Android first-class (owner has no PC): D-22 (parity, amends D-08), D-23 (bundled ffmpeg built from source, Media3 fallback), D-24 (CI APK + releases, stable signing from secrets), D-25 (ExoPlayer, Keystore AES-GCM, external files dir, backup). Phase 3 merged into Phase 1 as 1F; S6 on-device self-test added.
- 2026-11-11 1F CI APK (D-24): `android` job assembles the debug APK on every push (`versionCode` = run number, `BuildConfig.GIT_SHA` / `SIGNING`), uploads `autoflow-debug-<sha7>.apk`, publishes a GitHub Release for `v0.*` tags or a `.github/release-tag.txt` change on main; signing key from Actions secrets, else the runner debug key with a warning; build errors are posted as an annotation.
- 2026-11-11 1F bundled ffmpeg (D-23): `tools/ffmpeg-android/build.sh` cross-compiles FFmpeg 9.0.2 + x264 (stable) + libass 0.17.5 (freetype 2.15.0, fribidi 1.0.18, harfbuzz 15.1.0; no fontconfig) with NDK r29 for arm64-v8a / API 26 (PIE, static third-party libs and libc++); CI caches it by script hash, copies `libffmpeg.so` / `libffprobe.so` into `jniLibs/arm64-v8a/` and BUILDINFO + licenses into the APK assets; `useLegacyPackaging` so Android extracts them to `nativeLibraryDir`. README GPL note.
- 2026-11-11 S6 Self-test (PR 4): `:media` `FfmpegCapabilities` (version / filters / encoders → libass, xfade, libx264, aac, overlay backend), `FfmpegBenchmark` (maxrss), `selftest/SelfTestRenders` (r1 concat 540p, r2 + Burmese `.ass` 540p, r3 + mp3 mix 1080p export preset; frame at 4 s; ffprobe summary) and `SelfTestReport` (sealed `RenderOutcome`, verdict, Markdown); `:media:check` in CI. `:platform` Android bindings (clipboard, URL opener, tool locator for `libffmpeg.so` / `libffprobe.so`, unsupported file picker). Settings → About (version, long-press → Self-test). `app-android`: Koin `androidModule`, crash screen (Copy / Share / Open Self-test), Self-test screen + foreground service (dataSync / mediaProcessing, partial wake lock, progress notification with Cancel), device / binary info, exec failure → Media3 Transformer 1.11.1 fallback (D-23); CI generates the fixtures (`tools/selftest-fixtures.sh`).
