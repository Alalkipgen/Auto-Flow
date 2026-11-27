# One consolidated Studio v2 phone-QA session

Status: **PHONE_PENDING**. No physical-phone results from the November 16 follow-up have been recorded.
Run this once after the combined QA APK is ready, not after every milestone. JVM tests, Compose desktop screenshots,
fake providers, simulated executor reconstruction and emulator runs do **not** close these rows.

## Build record — fill from the final combined artifact, never a historical APK

| Field | Value |
| --- | --- |
| Source branch / exact remote SHA | Pending final combined QA build |
| APK filename / SHA-256 / download | Pending final combined QA build |
| Flags | Record `studioV2`, `quickRecap`, and every non-default override; Labs must remain off |
| Device / Android / font scale | Owner records here |
| Signing / update outcome | Record stable signing vs runner-debug; never uninstall real projects to work around a mismatch |

The APK above must include **all completed milestones**. A checkpoint APK is not the final queue-complete APK.
Do not claim missing features are phone-testable. See PLAN for implementation and automation status separately.

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
| 2b | **Burmese large-font pass (5B-13, D-67)** at Android font size 1.0 / 1.3 / 2.0 (and largest display size once): bottom nav ပင်မ · ပရောဂျက်များ · စာကြည့်တိုက် · အလုပ်များ, Recap step header "အသံ · 3/5" with ← ယခင်အဆင့် / နောက်အဆင့်, Home cards, project bar chips (ထည့်သွင်း · Shot စာရွက် · ဝင်စာ · ထုတ်ယူ · အဆင့်ဟောင်းများ), Shot sheet, Export. Expected: Burmese reads naturally, no line breaks inside a syllable, no clipped/overlapping text, 48 dp targets, §6 words exact (အတည်ပြုမယ် · ပြင်မယ် · ပြန်ထုတ်မယ် · Prompt ကူးမယ် · ဗွီဒီယို လက်ခံမယ်). Note any wording the owner wants changed. Older Classic / Settings / Input screens are still English (known limit). | PHONE_PENDING |
| 3 | First TTS resolves configured default, later calls use exact lock. Change voice shows affected takes, request count/ETA; Keep retains history and shows Different voice, Re-speak adds takes. Busy pauses, never silently switches provider/model. Edit live Library pronunciation: affected take becomes stale and Speak All regenerates it; written captions remain written. | PHONE_PENDING |
| 4 | **Preview + playback (5A-8, D-68).** Recap → ပုံစံ → Hide things: the preview shows the blur boxes **and the first caption** where the export puts it (on the lowest box in the lower half, centred on it — also a Custom box off the middle — else the usual bottom place). Tap "▶ ရုပ်ရှင် ဖွင့်ကြည့်မယ်": the first picked scene plays muted and looping under the boxes/caption; drag/pinch still work while playing; "⏹ ပုံရိပ်ပဲ ပြမယ်" returns to the still; leaving the screen stops it. Compare with Exact Frame and the export at 16:9, 9:16, 1:1 (boxes/caption in the same place). Known limits: the preview shows the picture before Looks (Mirror/Zoom/Fill) and without the background; the caption is a sample, not timed while playing; `recap.layers` editing (TEXT/IMAGE layers) is not implemented; desktop has no playback (still + Exact Frame only). | PHONE_PENDING |
| 4b | **Custom blur box (5B-2).** Turn on "စိတ်ကြိုက် ဘောင်", drag it to the lower-left and resize (pinch, ပိုကျယ်/ပိုကျဉ်း/ပိုမြင့်/ပိုနိမ့်): captions move onto it only once its centre is in the lower half; save, reopen, export — the box blurs the same area and the captions sit on it. Other presets (bottom/top strip, logos) unchanged. | PHONE_PENDING |
| 5 | Transcribe picked scenes: confirmation shows selected requests and today's usage; cancel/retry/resume/partial failure do not mix projects or skip required output. Never whole-movie transcription. | PHONE_PENDING |
| 6 | S8 Smart Fit: listen at 1.00/1.05/1.10/1.15/1.20/1.25×, view video at 0.9/0.8×; record tolerable Burmese limits, artifacts unchanged. Existing longer-take/CaptionChunks fit checks included. | PHONE_PENDING |
| 7 | Existing S5/S9 Burmese speech (five agreed sentences) and S7 real-song lyric timing (target ≈0.5 s); record returned model/intelligibility/timing. Azure comparison NOT authorized/NOT RUN; replication paid-tier is not required. | PHONE_PENDING |
| 8 | MV Shot sheet: storyboard edit/approval, English prompt stays English, Ref A/B, manual Flow open/copy/import only. Save/reopen, select older/newer version, accept three videos without losing history or selected version. | PHONE_PENDING |
| 8b | **Classic screens kept one release (5B-13).** Music Video project bar → "အဆင့်ဟောင်းများ" opens the old Storyboard / Shots / References / Flow Handoff screens with the same data; Back goes classic screen → အဆင့်ဟောင်းများ → Shot စာရွက် → list. Recap → "Recap ပုံစံဟောင်း" opens Classic Recap on the same project. Changes made in either place show in the other. | PHONE_PENDING |
| 9 | Export audio/BGM/ducking, two-pass loudness vs clearly visible fallback note after completion/reopen, Exact Frame thumbnail, readable Publish Kit frame+title JPEG, existing text copy/share and Save to Gallery. | PHONE_PENDING |
| 10 | Back up disposable project, Restore through Android system picker to a new destination; referenced artifacts/approvals/takes survive. Collision/corruption tests must not overwrite existing projects. Check share-sheet import → Inbox too. | PHONE_PENDING |
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
4. Settings → Apps → uninstall: the app and shortcuts are gone; `%USERPROFILE%\.autoflow` and project folders remain.

| Check | Pass / fail / not run + evidence |
| --- | --- |
| Download + SHA-256 match | OWNER_PC_PENDING |
| SmartScreen / install / launch / open project | OWNER_PC_PENDING |
| Uninstall keeps user data | OWNER_PC_PENDING |

## Separate automated gate

S10 is **Compose-preview vs real-ffmpeg placement parity**, five representative caption/layer cases within ±2%
of frame dimensions. It is not any Android lifecycle row above. Automation results, screenshots and ffmpeg pixel
measurements belong in the CI evidence bundle; they cannot mark these physical-phone acceptance rows passed.
Implemented as `PreviewExportParityTest` (D-68): five cases, every blur-box and caption edge within ±2 % of the
output frame; it runs in the CI `check` job with ffmpeg (`AUTOFLOW_EXPECT_FFMPEG=1`). On the Windows `windows` job
(no ffmpeg) it returns early without measuring. Passing S10 does not pass row 4.
