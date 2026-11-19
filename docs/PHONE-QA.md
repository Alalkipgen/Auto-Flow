# One consolidated Studio v2 phone-QA session

Status: **owner-reported narrow PASS on build 595 / c2cd1e0; UI v3 regression PHONE_PENDING**. All other acceptance rows remain pending/deferred.
Run this once after the combined QA APK is ready, not after every milestone. JVM tests, Compose desktop screenshots,
fake providers, simulated executor reconstruction and emulator runs do **not** close these rows.

## Build record — fill from the final combined artifact, never a historical APK

| Field | Value |
| --- | --- |
| Source branch / exact remote SHA | `work/phase-6-ui-v3-final-audit`; use the exact final `Source:` and run in this artifact’s `BUILD.txt` (the report records the full remote SHA; do not substitute an earlier checkpoint) |
| APK filename / SHA-256 / download | Use the neighboring `autoflow-debug-<sha7>.apk` and its binary `.sha256`; final download/run links are in the acceptance PR handoff |
| Flags | Record `studioV2`, `quickRecap`, and every non-default override; Labs must remain off |
| Device / Android / font scale | Owner records here |
| Signing / update outcome | Stable baseline595 certificate SHA-256 must equal `d7c3e543c25fa829b31b81cd459febdd10ff1cc67d9b16b32601a1962036f2f0`; app ID `io.github.alalkipgen.autoflow`, versionCode>595. Runner-debug/new-key is BLOCKED-SIGNING, never a substitute; never uninstall/clear data |

The APK above must include **all completed milestones**. A checkpoint APK is not the final queue-complete APK.
Do not claim missing features are phone-testable. See PLAN for implementation and automation status separately.

### One combined 5A-8 owner-QA package

CI now attaches `BUILD.txt` (exact pushed source SHA / run), `FEATURES.json`, this checklist and an offline
`qa-fixture/AutoFlow-5A8-synthetic-backup.zip` beside the fresh APK. APK/EXE `.sha256` files hash the actual binaries,
not their Actions artifact ZIPs. APK and unsigned EXE artifacts are retained for 30 days from upload; the Actions
artifact page gives the exact expiry and requires GitHub sign-in. The EXE artifact also includes matching application
and pinned VLCJ/JNA source archives and license notices; no native VLC is bundled. Defaults are `studioV2=true`,
`quickRecap=true`, every `labs.*=false`, no QA override required. Check existing settings overrides after updating.

The fixture uses the same valid-WAV/multiple-clip project as the real Desktop playback test, plus 9:16 Mirror/Zoom/Fill
and 1:1 image-background variants. Restore via the existing backup flow; do not replace real projects. Follow its
`EXPECTED.txt`: output 150/500/2500/4500 ms gives no-cue/FIRST/no-cue/SECOND and green/green/yellow/blue source patches.
The five native layers are now drawn by the muted native output-frame player, including Looks/background, list
order and output-time activation. Legacy movie-mask placement stays a clearly separate pre-Looks still. Compare
Exact Frame/export at the same output ms; Gaussian/text/YUV rasterization is approximate, not bit-identical. This is
the combined installation for the owner’s **one final QA session**, not an install per PR. Verify that this exact source
run’s checks are green before installing. Physical-phone/emulator and owner-PC acceptance remain pending.

### Reported restore failure — owner evidence, not an agent-executed test

- Installed `io.github.alalkipgen.autoflow`, `0.4.2`, versionCode `559`, source
  `4e95a7bc5c6b9f22a2a1cab08164692eeaa0f68c`, stable signing.
- Xiaomi `2312DRA50C` (`garnet`), Android 16 / SDK 36, `arm64-v8a`.
- **FAIL:** restoring `qa-fixture/AutoFlow-5A8-synthetic-backup.zip` crashed with
  `NoSuchMethodError: Files.readString(Path)` at `ProjectZip.checkProject(ProjectZip.kt:210)`,
  via `ProjectZip.restore` and `AndroidProjectBackup.restore`.
- Bundled ffmpeg concat / Burmese ASS / 1080p audio-mixed self-tests were reported **PASS**.
  This is restore failure evidence, not evidence of failed backup creation or rendering.
- Historical status at that build: hotfix update/retry was PHONE_PENDING. Build 595 restore retest is recorded below. Keep the consolidated session;
  never uninstall, clear data or replace the signing key to get past an update failure.

## Owner-reported baseline — not agent-executed acceptance

Installed `io.github.alalkipgen.autoflow`, `0.4.2`, stable-signed versionCode `595`, source
`c2cd1e08f3f2ea4717ec05a7b928faaacace9c47`; Xiaomi `2312DRA50C`, Android 16 / SDK 36, arm64.
Owner reports PASS: restore QA Landscape / Portrait / Square; UI language switch; native preview frame/caption;
play/pause/seek; final export (file opens, audio plays, Save to Gallery); Save / Reopen / Discard; Project A/B isolation.
Build 559 bundled ffmpeg self-test PASS remains historical evidence. These narrow results do not close broader rows
or any new UI v3 checks. This baseline is not a release candidate. Do not reinstall after each PR.

## UI v3 regression — one final combined installation

Every row starts **PHONE_PENDING**; owner-PC acceptance stays **OWNER_PC_PENDING**. Emulator absence is not a pass.
Use the final stable-signed update with versionCode >595, never uninstall/clear data or swap signing to make it install.
Record actual final SHA, enabled flags, APK/EXE filenames, binary hashes and artifact expiry from the final handoff.

| Check | Status |
| --- | --- |
| Back arrow and Android Back: detail → list → previous step → leave; cancel an open draft safely | PHONE_PENDING |
| Home real project data, Continue, Quick Recap approval stop, running rows; no concept sample data | PHONE_PENDING |
| Recap Source → Story → Voice → Style → Export end to end; old capabilities/confirmations reachable | PHONE_PENDING |
| UI v3 thumbnails: scroll Source scenes and Story/Voice blocks at 360/412 dp, font 1.0/1.3/2.0, EN/MY. Visible rows show real source frames (scene midpoint / first block-range midpoint); picks, filters, paging, Back and sticky actions still work. Revisit/reopen, switch A/B during loading, background/foreground, and test missing tools/media: no wrong-project picture, hang or project mutation; unavailable images keep times. Cache/performance/touch checks need actual device evidence. | PHONE_PENDING |
| Voice filters, block actions, selected/history takes, record/import, Smart Fit and stale/lock warnings | PHONE_PENDING |
| Style layer create/select/move/resize/order/timing, Discard unchanged, Save/reopen; preview vs Exact Frame/export | PHONE_PENDING |
| Caption presets/custom values/size independent of UI font scale; captions last | PHONE_PENDING |
| Export real preflight findings and Fix → exact step/block; output Open/Gallery/Share | PHONE_PENDING |
| Jobs grouping/badge, supported pause/resume/retry/cancel; project-bound progress/result | PHONE_PENDING |
| Music Video shot sheet five sections; Copy/manual Flow/import, Use version separate from Accept | PHONE_PENDING |
| Settings visible Diagnostics, Self-test, masked keys, language, tools, Library pronunciation link, backup | PHONE_PENDING |
| Burmese at font 2.0 and 360 dp; no syllable cuts, covered action or cut badge; EN switch unchanged content | PHONE_PENDING |
| Keyboard vs sticky action bar; real scroll reachability of Exact Frame and last row | PHONE_PENDING |
| Projects: actual Create/Recent sections,folder/rights/Create/Cancel draft after collapse,Open,confirmed Remove only from index; A/B unchanged | PHONE_PENDING |
| Library: all6 section jumps,empty/editable kit/preset draft retained,visible Delete and long-press confirmation,Lexicon link;real copied assets remain after Library original removal;Music copy-only vs existing Recap BGM picker,font copy-only,partial/save failure and unsupported Planned presets | PHONE_PENDING |
| Native known RED regression: cold trial7 of CI44413049428 showedBLUE at output1500/source3500 instead ofGREEN;CI44407013390 native-without-captions export at output2500/source6500 decodedGREEN instead ofYELLOW. Compare with Exact Frame/export; do not count later finite passes as a fix. Historical Block1 thumbnail blank remains unconfirmed-cause/open owner review. | PHONE_PENDING |
| Playback seek/caption/layer timing, project switching, background/foreground and cleanup after leaving | PHONE_PENDING |

## Safe setup

1. Back up existing projects to Download/AutoFlow; copy the ZIP off the phone and verify it opens before any
   uninstall/destructive experiment. A different signing key can prevent an in-place update: stop rather than delete data.
2. Use two disposable Recap projects A/B with clearly different names and written text; each has a short owned
   movie with audio, two picked scenes, an approved story and narration takes. Keep a separate disposable MV project
   with at least three shots, two reference images and two imported video versions of a shot.
3. Default to fake providers. No paid/live Gemini calls were authorized for automated testing. The owner may opt into
   their real key for the existing listening checks; record model, request count and account quota, never the key.
4. Use Settings → long-press version → Self-test → Preview features for the intended QA overrides. Record flags,
   reopen the app and verify them again. Do not enable Labs/Azure for this session.
5. Keep normal screen recording or written notes where possible. Store only synthetic/disposable fixtures in shared evidence.

## Results (each row starts PHONE_PENDING)

| # | Check / expected result | Pass / fail / not run + evidence |
| --- | --- | --- |
| 1 | Install/update without data loss; existing projects open. Save a key, restart, still masked/saved; only owner-authorized Test calls use it. | PHONE_PENDING |
| 2 | Home/Projects/Library/Jobs and Android Back preserve routes and project identity. Check narrow portrait/landscape and font scales 1.0, 1.3, 2.0: labels wrap, critical actions remain reachable, no clipped controls. | PHONE_PENDING |
| 2b | **Burmese large-font pass (5B-13, D-67)** at Android font size 1.0 / 1.3 / 2.0 (and largest display size once): bottom nav ပင်မ · ပရောဂျက်များ · စာကြည့်တိုက် · အလုပ်များ, Recap step header "အသံ · 3/5" with ← ယခင်အဆင့် / နောက်အဆင့်, Home cards, project bar chips (ထည့်သွင်း · Shot စာရွက် · ဝင်စာ · ထုတ်ယူ · အဆင့်ဟောင်းများ), Shot sheet, Export. Expected: Burmese reads naturally, no line breaks inside a syllable, no clipped/overlapping text, 48 dp targets, §6 words exact (အတည်ပြုမယ် · ပြင်မယ် · ပြန်ထုတ်မယ် · Prompt ကူးမယ် · ဗွီဒီယို လက်ခံမယ်). Note any wording the owner wants changed. Classic / Settings / Input also follow the current app preference; test English and Burmese separately. Technical names and owner content remain unchanged (see UI-LANGUAGE.md). | PHONE_PENDING |
| 2c | **App-only English/Burmese.** Settings without a language field defaults to English; an existing explicit choice persists. Use real Language choices en → my → en, without restart: Studio and Classic navigation, Settings, app-owned Self-test text, normal results/in-flight job labels, dialogs, Projects/Input/Recap/Jobs/Library/Export use only the selected UI language. Reopen main/Settings/Self-test and restart: choice persists, OS language and current route/project do not change. Verify original Burmese lyrics/story/captions/speak-as, English prompts, TTS provider/model/voice/language, spoken hashes/stale flags, approvals/takes/layers and filenames remain unchanged. UI font scale 1.0/1.3/2.0 and language switch must not resize rendered captions. Native system-picker chrome may follow the OS. | PHONE_PENDING |
| 3 | First TTS resolves configured default, later calls use exact lock. Change voice shows affected takes, request count/ETA; Keep retains history and shows Different voice, Re-speak adds takes. Busy pauses, never silently switches provider/model. Edit live Library pronunciation: affected take becomes stale and Speak All regenerates it; written captions remain written. | PHONE_PENDING |
| 4 | **Three preview modes (5A-8, D-70).** Legacy movie-picture mask placement is a pre-Looks still (sample caption only there). The separate muted timeline card plays every compiled native-v2 story clip as an output-frame proxy: legacy masks before Looks, fit/Fill/background, ordered/timed native layers, watermark and actual caption chunks last. Neither UI font scale nor UI language changes rendered text size; unavailable named TEXT fonts can fall back to the primary font. Pause/resume, seek between disjoint clips, compare active caption text with Exact Frame at the same output ms. Exact Frame remains the real export compiler. Never count a first-clip loop or cue readout as full-output parity. | PHONE_PENDING |
| 4b | **Custom blur box (5B-2).** Turn on "စိတ်ကြိုက် ဘောင်", drag it to the lower-left and resize (pinch, ပိုကျယ်/ပိုကျဉ်း/ပိုမြင့်/ပိုနိမ့်): captions move onto it only once its centre is in the lower half; save, reopen, export — the box blurs the same area and the captions sit on it. Other presets (bottom/top strip, logos) unchanged. | PHONE_PENDING |
| 4c | **Native recap.layers editor.** Add BLUR / IMAGE / TEXT / BORDER / BACKGROUND; select stable IDs, change supported params, output-frame rect (not legacy movie-picture coordinates), order and fromMs/toMs. Drag/pinch rect, Save, close/reopen and verify; Cancel leaves project unchanged. BORDER/BACKGROUND ignore rect. Removing an IMAGE reference must not delete its hash artifact; existing takes/approvals/script/other layers stay. | PHONE_PENDING |
| 4d | **Timed cues and seeks.** Before/inside/after a layer interval, check the actual native output-frame pixels as well as the active-ID readout. Caption chunks change with output time (including multiple clips / chosen takes / Fit SPEED and slow-video), blank gaps stay blank, paused seek stays paused. Compare same-time Exact Frame/export; a correct cue readout alone does not prove rendered layers or caption ink. | PHONE_PENDING |
| 4e | **Player lifecycle / project binding.** While playing A, switch to B, leave Style/Story, return, background/foreground the app, then pause/seek/resume. No old movie/caption/cursor/result appears in B; no hidden player continues on leaving the screen; desired pause survives background. Pending layer edit/image picker cannot write into B. | PHONE_PENDING |
| 4f | **Exact Frame reachability.** Narrow portrait and wide/landscape at font scale 1.0 / 1.3 / 2.0 (bundled Burmese and system fallback): scroll through the real screen, see the button, tap it, verify the resulting frame/output time or truthful unavailable message. Offscreen is acceptable when genuinely scroll-reachable. | PHONE_PENDING |
| 4g | **Preview versus exported output.** Check 16:9 / 9:16 / 1:1, native overlapping layer order, timing edges, Mirror/Zoom/Fill/background and multiple-clip boundaries with the deterministic fixture. Native output-frame Compose and expanded S10 are implemented; this physical-phone row is still pending. Compare the live native proxy, same-time Exact Frame and exported output separately; legacy placement or a cue readout alone is not output parity. | PHONE_PENDING |
| 4h | **On-video Style layers (brief §7.2).** On the real phone, scroll to the actual output preview; select BLUR/IMAGE/TEXT, move, corner-resize and pinch in 16:9/9:16/1:1, letterbox and Mirror/Zoom/Fill. IMAGE keeps aspect and uses width; BG/BORDER cannot move/resize. Check ≥48dp uncovered targets and × removes only the reference. Numeric/% fields and handles share the same draft; invalid text blocks handles/Save, Save retains stable IDs/order/timing and same paused output time; Discard/Back/project switch write nothing. Compare same-time saved preview, real Exact Frame and completed output at ±2% geometry; captions last. Missing native frame/assets must not get fake editable handles. EN/MY, bundled/system fallback and font1/1.3/2 require owner review. Automated component/static tests and installed-VLC CI do not satisfy this row. | PHONE_PENDING |

| 5 | Transcribe picked scenes: confirmation shows selected requests and today's usage; cancel/retry/resume/partial failure do not mix projects or skip required output. Never whole-movie transcription. | PHONE_PENDING |
| 6 | S8 Smart Fit: listen at 1.00/1.05/1.10/1.15/1.20/1.25×, view video at 0.9/0.8×; record tolerable Burmese limits, artifacts unchanged. Existing longer-take/CaptionChunks fit checks included. | PHONE_PENDING |
| 7 | Existing S5/S9 Burmese speech (five agreed sentences) and S7 real-song lyric timing (target ≈0.5 s); record returned model/intelligibility/timing. Azure comparison NOT authorized/NOT RUN; replication paid-tier is not required. | PHONE_PENDING |
| 8 | MV Shot sheet: storyboard edit/approval, English prompt stays English, Ref A/B, manual Flow open/copy/import only. Save/reopen, select older/newer version, accept three videos without losing history or selected version. | PHONE_PENDING |
| 8b | **Classic screens kept one release (5B-13).** Music Video project bar → "အဆင့်ဟောင်းများ" opens the old Storyboard / Shots / References / Flow Handoff screens with the same data; Back goes classic screen → အဆင့်ဟောင်းများ → Shot စာရွက် → list. Recap → "Recap ပုံစံဟောင်း" opens Classic Recap on the same project. Changes made in either place show in the other. | PHONE_PENDING |
| 9 | Export audio/BGM/ducking, two-pass loudness vs clearly visible fallback note after completion/reopen, Exact Frame thumbnail, readable Publish Kit frame+title JPEG, existing text copy/share and Save to Gallery. | PHONE_PENDING |
| 10 | Back up disposable project, Restore through Android system picker to a new destination; referenced artifacts/approvals/takes survive. Collision/corruption tests must not overwrite existing projects. Check share-sheet import → Inbox too. | Historical fixture restore FAIL on `4e95a7b` / code `559`; fixture restore PASS reported on `c2cd1e0` / code `595`. Broader backup/collision/corruption checks PHONE_PENDING |
| 11a | Real WorkManager Speak All partially complete → background/screen-off/Doze → completion or truthful PAUSED. Shared queue/pacing/notifications observed. | PHONE_PENDING |
| 11b | OS kills app process during an item/checkpoint; reopen/resume real WorkManager chain. Completed local outputs reconciled, no duplicate saved takes, false DONE or skipped incomplete items. Record actual kill mechanism and request count. | PHONE_PENDING |
| 11c | Switch A → B with A item in flight; result saves only to A. Cancel during execution/checkpoint: no partial final output replaces a valid one; following queued job still runs. | PHONE_PENDING |
| 12 | User force-stop is a **separate** experiment; record Android's suspension until explicit reopen, then recovery. Do not describe this as ordinary OS process death. | PHONE_PENDING |

### Lifecycle evidence fields

For rows 11–12 record: physical device vs emulator; actual app-process restart vs executor reconstruction;
real WorkManager vs mocked scheduler; process-death vs force-stop; item state before/after; job IDs; local take IDs;
observed remote request count if available. Local reconciliation is **not exactly-once remote TTS**: the provider
has no promised idempotency across a crash after request acceptance and before local publication.

## Owner PC check — Windows EXE installer (not a phone row)

Status: **OWNER_PC_PENDING**. CI (`windows-installer`) builds the real EXE with jpackage + WiX, then silently installs it,
launches the installed app, closes it and uninstalls it on a disposable runner; that is automation, not the owner's PC.

1. Download the Actions artifact `autoflow-windows-exe-unsigned-<sha7>` from the final combined build; check the EXE's
   SHA-256 against the `.sha256` file / CI notice annotation.
2. The EXE is **unsigned**: Windows SmartScreen may say "Windows protected your PC" → More info → Run anyway. Record it.
3. Install (per user, no admin prompt) → Start menu "Auto Flow" + desktop shortcut → app opens; open an existing project
   folder; Settings shows the same version as the APK build record.
4. Playback: first verify startup/Exact Frame without VLC; play gives a clear unavailable message, never a startup failure. If desired, install **64-bit VLC 3.0.x separately** from https://www.videolan.org/vlc/ (not bundled/downloaded by Auto Flow), use Settings → VLC path or auto-discovery; restart after changing an already-loaded native path. Test actual muted movie pixels, pause/seek/project replacement and leaving the screen. A Windows installer smoke does not prove Windows VLC runtime playback.
5. Settings → Apps → uninstall: the app and shortcuts are gone; `%USERPROFILE%\.autoflow` and project folders remain. The companion desktop source ZIP and GPLv3 notice ship beside/inside the unsigned EXE; no paid VLCJ licence is required.

| Check | Pass / fail / not run + evidence |
| --- | --- |
| Download + SHA-256 match | OWNER_PC_PENDING |
| SmartScreen / install / launch / open project | OWNER_PC_PENDING |
| Uninstall keeps user data | OWNER_PC_PENDING |

## Separate automated gate

The original `PreviewExportParityTest` is preserved: five legacy pre-Looks still-placement cases, real Compose and
ffmpeg/libass, every measured mask/caption edge within ±2% of the output frame. `NativeOutputParityTest` adds eight
native-output configurations and seven output positions each (56 composed/exported frames): all layer types,
overlap/order, before/inside/after activation, FIRST/SECOND/blank chunks, 16:9/9:16/1:1, Mirror/Zoom/Fill/Warm and
native/legacy backgrounds across three disjoint clips/two stable blocks. It measures actual changed-pixel bounds,
not just shared geometry: edge tolerance ±2% of the relevant frame dimension, whole-frame RGB MAE <=0.08. Text,
Gaussian and YUV rasterization are NOT bit-identical. Samples are away from activation edges; 24fps export gives
41.7ms sampling, not a claim of exact live-player boundary synchronization.

Linux `check` requires ffmpeg (`AUTOFLOW_EXPECT_FFMPEG=1`). Platforms without it report framework skips and are not
parity successes. `desktop-playback` separately installs VLC 3 on a disposable runner and runs the real decoder plus
production Compose playback screen: changing source pixels / output mapping, pause/seek/resume, project/screen
cleanup, native IMAGE/BORDER pixels, and actual FIRST/SECOND/blank caption ink in portrait/square live output. It
records screenshots and XML separately from S10. These tests found/fixed copied RV32 alpha and paused-seek Compose
state-capture bugs; cue readouts alone were not substituted for rendered pixels. Polling is nominally 40ms; no
frame-perfect audiovisual synchronization or audio-workstation preview is promised (the preview is muted).

None of this passes Android lifecycle, physical-phone or owner-PC acceptance above. The implementation sandbox
had no Android emulator/adb or `/dev/kvm`; emulator/instrumentation and physical-phone checks remain **PHONE_PENDING**.
Windows installer/install/launch/uninstall and no-VLC packaged startup are separate gates, not Windows installed-VLC
movie playback proof. Installed-VLC playback and real-project acceptance on the owner PC remain **OWNER_PC_PENDING**.


### UI-v3 final-source delivery discipline

All phone rows above remain PHONE_PENDING and owner-PC acceptance remains OWNER_PC_PENDING; this document does not upgrade desktop/fake/compile/disposable-runner evidence into device acceptance. The final combined APK must update the existing stable595 installation without uninstall/clear-data. If certificate verification is unavailable or mismatched,report BLOCKED-SIGNING and do not offer a runner-debug substitute. Use only the matching final-source APK/UNSIGNED EXE,BUILD/MANIFEST,binary hashes,FEATURES,QA backup and source/license companions in the final report. Q31 project shortcut semantics and newMY wording remain owner decisions. Native/core/media failure repair is outside this UI-only scope; retain every failure record.

## UI v3 feedback round 1 — PHONE_PENDING

Use a disposable synthetic project/restore, not an owner's irreplaceable media. Confirm the APK has the matching application ID, stable signing and a higher versionCode before installing as an update. On Xiaomi 2312DRA50C (Android 16), repeat at normal and large font in English and Burmese.

1. **F1 Export:** Preview 540p for portrait, landscape and square. Check poster, fit, finish → Replay, fullscreen → Back, Open externally → return, then force-stop/relaunch without regenerating. Try a disposable project with no preview.
2. **F2 Watermark:** Change Small/Medium/Large/Custom and compare Style, Exact Frame and export for all three ratios. Save/apply a Brand Kit; reopen and confirm the saved size. Open a previous project without editing it.
3. **F3 Gemini:** With synthetic input only, start a flow and look at the same run in the project, Home and Jobs. Verify Cancel, previous result labelling, saved-clip counts, a single request's elapsed time, and Diagnostics. Check resume only if a real limit occurs.
4. **F4 Projects:** Long-press and Remove a new disposable project; restart and find it under Removed projects. Restore to Projects without opening it. Remove it again, open its row in Removed projects, cancel the permanent confirmation once, then confirm. Repeat with an older worked-on disposable project containing generated artifacts. Check that active jobs block deletion, the deleted folder and both list entries stay gone after restart, Home Continue no longer links it, and a same-name sibling, external media, Gallery/Downloads and existing backups remain.
5. **F5 Library:** Tap Apply and verify no visible Delete. Long-press a saved kit and then one of two same-name presets. Confirm only the selected ID disappears after restart and the applied project keeps its style. Repeat with TalkBack action.
6. **F6 Jobs:** Long-press a finished run, confirm history removal and restart. Check the badge and playback/output remain correct. Long-press active and paused jobs; neither should offer Delete.

### F3 follow-up — Gemini progress in the project (PHONE_PENDING)

- Music Video → Input → Generate storyboard. At 412 dp (also 360 dp) and English/Burmese font scales 1.0/2.0: see `1/2` (lyrics analysis), then `2/2` (single batch with the actual shot count), elapsed time, an indeterminate bar and Cancel in the project. No fake percentage. Open Home → Running and Jobs; verify the same stage. Use Jobs → Open and cancel in the project; the existing storyboard must still be available. Regenerate all and one shot from Shot Sheet; verify a single-shot request is indeterminate and the card stays visible in the detail view.
