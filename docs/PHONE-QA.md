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
| 3 | First TTS resolves configured default, later calls use exact lock. Change voice shows affected takes, request count/ETA; Keep retains history and shows Different voice, Re-speak adds takes. Busy pauses, never silently switches provider/model. Edit live Library pronunciation: affected take becomes stale and Speak All regenerates it; written captions remain written. | PHONE_PENDING |
| 4 | Real video plays under editable native layers. Geometry/timing/visibility/order persist after reopen. Check TEXT below/above an image and captions last at 16:9, 9:16, 1:1. Exact Frame and export at the same timestamp agree. | PHONE_PENDING |
| 5 | Transcribe picked scenes: confirmation shows selected requests and today's usage; cancel/retry/resume/partial failure do not mix projects or skip required output. Never whole-movie transcription. | PHONE_PENDING |
| 6 | S8 Smart Fit: listen at 1.00/1.05/1.10/1.15/1.20/1.25×, view video at 0.9/0.8×; record tolerable Burmese limits, artifacts unchanged. Existing longer-take/CaptionChunks fit checks included. | PHONE_PENDING |
| 7 | Existing S5/S9 Burmese speech (five agreed sentences) and S7 real-song lyric timing (target ≈0.5 s); record returned model/intelligibility/timing. Azure comparison NOT authorized/NOT RUN; replication paid-tier is not required. | PHONE_PENDING |
| 8 | MV Shot sheet: storyboard edit/approval, English prompt stays English, Ref A/B, manual Flow open/copy/import only. Save/reopen, select older/newer version, accept three videos without losing history or selected version. | PHONE_PENDING |
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

## Separate automated gate

S10 is **Compose-preview vs real-ffmpeg placement parity**, five representative caption/layer cases within ±2%
of frame dimensions. It is not any Android lifecycle row above. Automation results, screenshots and ffmpeg pixel
measurements belong in the CI evidence bundle; they cannot mark these physical-phone acceptance rows passed.
