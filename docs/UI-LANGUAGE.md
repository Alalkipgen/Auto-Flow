# App-only UI language

## Contract

- English is the first-run and legacy-settings default. Settings → Language selects `en` or `my` and persists it atomically in app settings. An existing explicit choice is retained.
- `SettingsStore.state` is shared by the app graph. `AppLanguage`/`LocalUiLanguage` supply an observable composition value, not a mutable global locale. The Android Self-test activity observes that same store; the main Settings screen and Desktop observe it through the app composition.
- This is not the OS locale. It must not select a TTS language/model/voice, alter a project, invalidate a take, change an approval or reset navigation.
- `UiText` is the stable-key English/Burmese catalog. Missing/invalid translations fall back to English; placeholder sets must match. Arguments are substituted once, never reinterpreted as templates.
- App-owned results and in-flight labels use deferred `UiNotice` values so an already-visible message can retranslate. Legacy English job labels remain business identities, never translated keys. Enums do not capture translated strings at initialization.
- Native file-dialog titles read the current preference when opened. System-picker chrome, OS permissions and external applications remain controlled by their own language settings.

## Shipped surfaces covered

- Studio Home/Projects/Library/Jobs, project headers, Recap steps and Classic entry points.
- Dashboard create/open/remove dialogs and relative dates; References, shared Inbox and manual Flow handoff controls/results.
- Settings, backup/restore status, language selection, key/tool/font controls and the app-owned Self-test surface.
- Input/characters/lyric timing, Storyboard, Shots/Shot sheet, prompts, references, approvals and export gates.
- Studio and Classic Recap source/scenes/subtitles/transcription/story/voice/recording/takes, voice-lock/change-voice, Fit/sound/Looks/background/watermark/layers, progress and normal action results.
- Export/preflight/Exact Frame/Publish Kit/save/share controls and deferred completion notices.
- Classic phone tabs, More/sidebar/stepper, provider status, already-open lock explanations, storyboard progress/cancellation and playback availability.

### Deliberate non-translations

Project names, Burmese lyrics/story/captions/speak-as, English model prompts, pronunciation entries and other user-authored text are content, not UI templates. Preserve them exactly. Filenames/paths, IDs, hashes, serialized keys/enum values/routes, language codes, provider/model/voice IDs, preset names and technical units/formats remain stable. Product/workflow terms such as Auto Flow, Movie Recap, Shot, Storyboard, Flow, RGB, MB and ms can appear as names inside either UI language; this is not a parallel translated sentence.

Raw ffmpeg/provider/OS/validation diagnostics are preserved as data, with a localized explanatory prefix where appropriate. Do not machine-translate a diagnostic, a path, a prompt or the locked Burmese speech language merely to make the screen look monolingual. Test tags and raw route IDs remain English.

## Regression evidence

- `UiLanguageTest`: old settings/default/future-code fallback, persistence both ways, shared observers, catalog coverage/placeholder integrity and one-pass substitution.
- `UiLanguageVisibleTest` and the integrated `PhoneShellTest`: real displayed Settings choices update navigation/provider status in both directions without translating route IDs.
- `DashboardLanguageLayoutTest`, `AssetNoticeLanguageTest` and `ClassicLanguageLayoutTest`: each has 24 distinct cells (412/840 px × font scales 1.0/1.3/2.0 × English/Burmese × bundled/system fallback). They use displayed pointer clicks and real scrolling rather than invoking an offscreen semantics action. Asset and Classic cases also retranslate an already-open result/lock notice.
- Existing Studio/Recap/Shot-sheet/Input font/layout suites remain enabled. Real layout defects are fixed by wrapping, minimum targets or scrolling, not by larger test windows, smaller Burmese type or weaker assertions. Classic retains its earlier English single-line fit regression and permits Burmese syllable wrapping at the requested scale.
- `RecapNoticeLanguageTest`: actual Find scenes action on both shipped Recap surfaces, suspended media work, language switch in flight, actual scene/result save and live result retranslation. English job identity and unchanged project bytes are asserted.
- `UiLanguageContentTest`: production disk/project/artifact serialization for MV and Recap, real language clicks and fresh SettingsStore reloads; unchanged full file trees, project JSON, English PromptIR, Burmese content, approvals/takes/voice lock, spoken hashes and stale decisions.
- `CaptionLanguageIndependenceTest`: real output-frame painter with nonempty Burmese caption ink, both font families, both languages and all three UI scales. Each variant reconstructs the painter/frame so a cached bitmap cannot hide a font-scale dependency; pixel arrays and dimensions must be identical within each font family.

Linux acceptance hosts need a UTF-8 native filesystem charset and a Myanmar-capable system font for the fallback-font cell. CI ensures Xvfb/xauth and an OS fallback font are available. Missing prerequisites must fail, never pass with blank caption ink; the bundled-font cell is separate. These host prerequisites do not add an application dependency or select the app's UI language.

These are automation results, not physical-phone, Android process-death or owner-PC acceptance. See `PHONE-QA.md`; keep its reported restore FAIL and pending rows.

## Final build gate

The exact final remote source must pass the fresh Linux command below, not just a cached local target:

```sh
AUTOFLOW_EXPECT_FFMPEG=1 xvfb-run -a ./gradlew check --rerun-tasks --no-build-cache --continue --max-workers=2 -Dorg.gradle.jvmargs=-Xmx1600m -Pkotlin.compiler.execution.strategy=in-process
```

Android debug/lint/minSdk restore-linkage/instrumentation-build, packaged Desktop startup, dedicated real-VLC playback and Windows EXE install/launch/uninstall remain separate CI jobs. Four local VLC-prerequisite skips are not playback passes; the real-VLC gate must pass. Android instrumentation compilation is not a device execution.

The old portrait/image S10 mismatch remains a valid incident: see PLAN's `bd85137`/`43941917691` entry. Sampling/source-marker evidence was hardened without relaxing ±2% geometry or RGB MAE ≤0.08. Fresh greens do not establish a confirmed intermittent production root cause.

Use the final artifact's `BUILD.txt`, APK/EXE binary `.sha256` and source/license companions. Check actual binary hashes, APK signing certificate and versionCode; an Actions ZIP digest is not a binary hash. No merge, tag, release, owner-data deletion or repeated per-checkpoint installation is part of this work.
