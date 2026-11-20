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
- Music Video → Input → Sync lyrics → Suggest timings. Confirm that media prep and waiting are displayed, Jobs → Open returns to the same active run, and saved timings appear after completion. Verify Settings → Test key and Test Gemini TTS display distinct progress with Cancel next to the corresponding action.
- Movie Recap: start Transcribe from lower in Source, Write script from Story, and Voice remaining and a single Generate/Re-voice from the block detail in Voice and Story. Verify the active card and Cancel are visible without scrolling back, including Classic. A single voice request has a waiting timer (not a percentage); Voice remaining counts saved takes only. Cancel a single voice and reopen; the previous take stays selected.

### Inbox follow-up — delete a shared copy (PHONE_PENDING)

- In a disposable Music Video project, share an MP4 and a JPG from Gallery. Inbox must not show inline **Delete…**, and a short tap or **Attach to Shot…** must not open Delete. Long-press the MP4 card → **Delete…** → Cancel once, then repeat and confirm. Repeat on the photo. At 360/412 dp and English/Burmese large font, check the long filename and both confirmation buttons. Check the per-file TalkBack action; desktop equivalent is right-click.
- Share the same file twice and delete just one Inbox copy. If shares are waiting, long-press one there to delete it without using **Move here**. Leave Inbox, reopen it, then force-stop/relaunch and check that deleted copies stay gone, the other copy still attaches, and the Gallery originals and any already-attached shot media still play.

## Phase 7 — Subtitles · Voice & Audio · Dubbing (PHONE_PENDING, never blocks the next PR, D-85)

### S-1a/S-1b — Subtitles projects (PHONE_PENDING)

- With `tools.subtitles` off (default): Home → Quick tools shows Subtitles as **Planned**; Home, Projects, Music Video and Movie Recap look and work exactly as before.
- Self-test → Preview features → turn on `tools.subtitles`. Home → Subtitles opens the system picker at once. Cancel: no project appears anywhere. Pick a short video: a project named `<file name> · <date time>` (phone's local time) opens, shows "Copying…", then the Video card (length · size · fps · sound). Pick an audio-only file: nothing is created and Home says the file has no picture.
- Projects shows the new row with the Subtitles label and no shot counter; Home Continue shows it with the Subtitles icon and "Subtitles". Long-press → Remove → Restore → Remove → Delete permanently works as for other projects (D-76). Backup → Restore lists it again.
- 360/412 dp, English/Burmese, font 1.0/2.0: the Video card and the "Subtitle source" card fit without clipping.

### S-2 — Subtitle source (PHONE_PENDING)

- A video with a text subtitle track (e.g. an `.mkv` with SubRip): the Source card lists each track (language · codec), the Burmese text track — else the first text track — already chosen; "Use the subtitles inside the video" → "Saved N lines from track N" and the Subtitles card shows the line count and the first lines. Picture tracks (PGS/DVD) are not options; one line says how many cannot be read as text. A video without sound shows Transcribe with "no sound to transcribe".
- A video without tracks says so. "Import subtitles (.srt)": a UTF-8 file, a UTF-16 file (Windows Notepad "Unicode") and an old Windows-1252 English file all import and the message names the encoding; an empty file or one with a broken time line saves nothing and says which subtitle; a Zawgyi file imports with the amber Zawgyi warning.
- "Choose another source" → pick again: the new lines replace the shown ones (the old version stays in `artifacts/`). Close and reopen the project: the same lines show. "Turn speech into text" is Planned.

### S-3c — Subtitle editor (PHONE_PENDING)

- After a source: the lines list (#n · start → end · length), Original · Burmese · Both chips, and an amber "n to check" chip when lines overlap / are too short / too long; tapping it jumps to the next such line.
- ✎ on a line opens the edit panel under it: change the text, type a time (`1:02.5`) or use −0.1 / +0.1, Split (at the line break), Merge with next, Add after, Delete; Previous · Next · Done. The keyboard does not hide the field being typed in. "Not saved yet" with Discard · Save appears; Undo / Redo step through the changes; "Choose another source" is off until Save or Discard.
- Save → "Saved: N lines."; reopen the project: the edited lines show. Edit, then Back → "Save your changes before leaving?" with Save · Discard · Keep editing. Edit, wait 2 seconds, close the app, reopen → "Unsaved changes from <time>" with Restore · Discard.
- 360/412 dp, English/Burmese, font 1.0/2.0: the list, the edit panel and the Save bar fit without clipping; the header scrolls away with the lines.

### S-3d — Subtitle preview (PHONE_PENDING)

- The video shows above the lines with the time in its corner; Play plays it with sound and the line under the playhead appears over the picture (Original / Burmese / Both follow the view chips). Dragging the bar seeks.
- Tap a line: the video jumps to its start. While playing, the list follows the line being spoken; scroll the list yourself and it stops following for about 3 s.
- In the edit panel: "Use playhead" sets Start or End to the video's time; "Play line" plays just that line and pauses at its end; "Split here" while the video is inside the line splits at that time.
- 360 dp at font 2.0: the video takes about a third of the screen and the lines are still visible below it.

### S-3e — Shift times (PHONE_PENDING)

- Shift times → All lines → +1 s: every line's times move 1 s later and "Moved n lines by 1.0 s." shows; Undo puts them back.
- Tap line 5, Shift times → From line 5 to the end → −0.1 s: lines 1–4 stay, the rest move 0.1 s earlier.
- All lines → −1 s repeatedly until the first line is near 0:00: the next press is refused and says the most you can move back.
- 360 dp at font 2.0, English and Burmese: the Shift card's chips and buttons wrap without clipping.

### S-3f — Wide layout and keys (Windows · tablet, PHONE_PENDING)

- Window ≥ 840 dp wide: the video and the edit panel are on the left, the lines on the right; the lines stay visible while a line is edited. Narrower: as before.
- Click an empty spot, then: ↓ / ↑ select the next / previous line and the video jumps to it; Enter opens its edit panel; Space plays / pauses; Ctrl+Z undoes; Ctrl+S saves ("Saved: N lines.").
- Typing a space or Enter inside a text box types it; it does not play or open anything.

### S-4a — Export .srt (phone + Windows, PHONE_PENDING)

- Saved subtitles show **Export** at the bottom; while there are unsaved changes only Save · Discard show.
- Export → language chips: **Burmese** is chosen when some line has Burmese, else **Original**. "Video with subtitles" shows Planned.
- Phone: **Share…** opens the share sheet with an `.srt` file (Telegram/Drive receive it); **Save to Downloads** says "Saved to Downloads: Download/AutoFlow/…"; the file opens in a player (VLC/MX) next to the video and the lines match the editor.
- Windows: **Save .srt** shows the path in the project's `exports` folder; **Open folder…** opens that folder in Explorer.
- Export twice with the same language: the same file is used (no second copy). Change a line, Save, export again: a new file name.

### S-4c — Video with subtitles (phone + Windows, PHONE_PENDING)

- Export → **Video with subtitles**: look chips Clean / Boxed / Outline, size chips Small / Medium / Large, and "Same size and frame rate as the video; sound copied."
- Burmese chosen with no Burmese font (none in Settings → Fonts and none built in): Make video says "No Burmese font…" and nothing starts. Original works without one.
- Make video: a card shows "Making the video: N%" and Jobs shows the same row with Cancel. Cancel → "Cancelled. Nothing was saved." and no file in `exports`.
- Done: the video plays (▶ Play); on the phone Share… and Save to Gallery work and the video is in Gallery (Movies/AutoFlow); on Windows Open folder… shows `exports`.
- In a player: the picture is the same size and frame rate as the source (e.g. a 23.976 fps film stays 23.976), the sound is the original; each line appears and disappears at its time; Burmese shows joined letters (no broken stacks), long lines wrap into two.
- Phone with the app in the background during the render: the notification shows progress and the file is there when it finishes.

### S-4d — Check one frame (phone + Windows, PHONE_PENDING)

- Select a line in the editor, Export: the Video with subtitles card says "Line N · time" for that line; ‹ › move to the previous / next line.
- **Check one frame**: after a moment a still appears with the line burned in, in the chosen look and size, exactly as Make video will draw it (Burmese joined correctly).
- Change Burmese ↔ Original, the look or the size: the still disappears until checked again. Nothing appears in Jobs for a check.

### S-5a/S-5b — Transcribe the video (phone + Windows, PHONE_PENDING; needs a Gemini key)

- A Subtitles project with a spoken video: the source card has **Turn speech into text (Gemini)**. A video with no sound: the button is off and says why.
- Press it: "Finding the pauses…", then a card "N min of sound · K Gemini requests · only the sound is sent", "Today: … requests", language chips (Find out, EN, KO, ZH, TH, JA, HI, MY). Back sends nothing.
- Start: "Transcribing: chunk 1 of K"; Jobs shows "Transcribe video". Pause: the current chunk finishes, then "Paused: 1 of K chunks saved" and **Resume K−1 remaining**; Resume finishes without sending chunk 1 again.
- Done: "Done: n lines…", the editor shows the lines at the right times (check a few against the speech; a line cut at a 10-minute boundary appears once).
- With lines already there (Choose another source → Transcribe): the card says it makes a new version; after Done, Versions still has the old lines.
- Cancel job: "Transcribe cancelled. Your subtitles did not change."
- Phone: close the app during a long job, reopen: the job card is there (running or paused) and Resume sends only the rest.

### S-6a/S-6b — Translate to Burmese (phone + Windows, PHONE_PENDING; needs a Gemini key)

- A Subtitles project with English (or other non-Burmese) lines, saved: the sticky bar has **Translate to Burmese (Gemini)** next to Export. Burmese lines (a `my` track): no Translate button.
- Press it: a card "Translate N lines: K Gemini requests.", "Only new or changed lines…", Everyday spoken (selected) / Formal, names (+ Add a name), "Also replace my edits" (off). Tick it with a hand-edited Burmese line: N grows. Back sends nothing.
- Add a name (e.g. a character → its Burmese), Start: "Translating: batch 1 of K"; Jobs shows "Translate subtitles". The lines cannot be edited and the bar says "Editing pauses while translating" with Cancel job.
- Done: "Done: n lines have Burmese…", the editor shows the new version; Burmese view shows the lines, the name written as given, the hand-edited line unchanged. Versions still has the old lines.
- Cancel job: "Translate cancelled. Your subtitles did not change." and editing comes back.
- If Gemini is busy: the job card says Paused with **Resume n remaining**; Resume sends only the rest. If a batch fails: "n lines not translated. The rest is kept." + Retry asks again for only those lines.

### V-1a — Voice & Audio project data (nothing to press yet)

- Nothing new to see: Home still shows Voice & Audio as Planned. Existing Music Video, Movie Recap and Subtitles projects open and save exactly as before.

### V-1c — Voice & Audio tile, chooser and text (phone + Windows, PHONE_PENDING)

- Self-test → Preview features: turn on `tools.voiceAudio`. Home → Quick tools: **Voice & Audio** is a button (off: Planned).
- Press it: "What do you want to do?" with **Text → voice**, and Clean up sound / Video sound as Planned. Nothing appears in Projects yet.
- Text → voice: paste a long Burmese text (with names, numbers like 3.5 and English): the line under it shows characters and parts (about 500 characters per part). Back returns to the chooser and creates nothing.
- **Continue with this text**: a project "Voice · <date time>" opens with the text; "Speak n parts" is shown as Planned. Change the text, wait a second, go Home and reopen it from Continue: the change is there.

### V-1d — Voice, tone and parts list (phone + Windows, PHONE_PENDING)

- Open a Text → voice project: under the text are **Voice** (saved voices from Library first, then Burmese models and the six voices), **Tone** (Calm / Lively / News / Storytelling) and the parts list; every part says "Not voiced yet" (Speak comes with V-1e, still Planned).
- Pick another voice or tone, leave and reopen: the choice is kept. Tap the chosen tone again: no tone.
- Burmese labels fit at 360 dp with large text; owner: check the Burmese wording of the tone chips.

### V-1e — Speak the parts (phone + Windows, PHONE_PENDING; needs a Gemini key)

- **Speak n parts**: a card says how many Gemini requests, with which voice and model, and about how long (3 per minute). Back sends nothing.
- **Start**: "Speaking: part k of n"; the text, voice and tone cannot be changed meanwhile. Each part turns "✓ Voiced" with ▶ (listen) and ↻ Speak this part again.
- Lock the phone or switch apps during a long text: the job goes on (Jobs shows "Text to voice"); if Gemini is busy it pauses, and **Resume** speaks only the parts left.
- Change one part's text afterwards: only that part says "Not voiced yet" and Speak asks for 1 request.

### V-1f — Save the voice (phone + Windows, PHONE_PENDING)

- With every part voiced the bottom bar shows **Save m4a** and **Save wav** (while a part is not voiced it shows Speak instead).
- **Save wav**: "Saved: …/exports/<name>-voice-….wav" at once; listen to it — parts in order with a short pause, no clicks, nothing cut.
- **Save m4a**: "Making the m4a: n%" with Cancel (also in Jobs as "Save m4a"); then the file path. Cancel leaves no m4a.
- Phone: **Share…** opens the share sheet; **Save to Music** puts it in Music/AutoFlow (check a music app sees it). Windows: **Open folder…** shows the exports folder.

### V-2a — Clean up sound commands (nothing to press yet)

- Nothing new on screen; Clean up sound stays Planned until V-2b.

### V-2b — Clean up sound (phone + Windows, PHONE_PENDING)

- Home → Voice & Audio → **Clean up sound** opens the file picker at once; Cancel creates nothing. Pick a noisy voice recording (m4a / mp3 / wav) or a video.
- "Copying the file into the project…", then the file name and length. For a video: "A video gives its sound only…".
- Three chips (all on): Shorten long pauses · Reduce noise · Even loudness (−14 LUFS). The last one on cannot be turned off.
- **Before / After (first 30 s)**: "Listening to the sound…", then ▶ Before and ▶ After — After should have shorter pauses, less hiss, and a steady level.
- **Save m4a** / **Save wav**: "Cleaning the sound: n%" with Cancel (Jobs shows "Clean up sound"); then the file in exports, Share / Save to Music (phone) or Open folder (Windows). Play it in another app.

### V-3a — Video sound commands (nothing to press yet)

- Nothing new on screen; Video sound stays Planned until V-3b.

### V-3b — Video sound (phone + Windows, PHONE_PENDING)

- Home → Voice & Audio → **Video sound** opens the file picker at once; Cancel creates nothing. Picking an audio file says "This file has no picture…" and creates nothing.
- Pick a video with sound: "Copying the file into the project…", then the name · length · size and "The picture is not re-encoded."
- **Save the sound only** (default): bottom bar **Save m4a** / **Save wav** → the file in exports; play it in another app (same length as the video). Phone: Save to Music.
- **Replace the sound**: **Choose the sound** (e.g. a voice m4a from Text → voice); "Sound: song.…"; **Make video** → "Making the video: n%" (Jobs shows "Replace the sound"); the result plays with the new sound, same length; a shorter sound ends in silence. Phone: **Save to Gallery**.
- **Add voice or music on top**: Starts at 2, Volume 50 %, Lower the original on → **Make video**; the original is quieter while the added sound plays. A video without sound: the Lower chip is not shown and Save the sound only is off.
- Check the picture looks exactly like the original (no blur, same size).
