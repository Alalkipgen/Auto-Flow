# Phone test results — v0.1.14

## Scope

- App: Auto Flow v0.1.14, based on release commit `90dac1c75d86900065d98d8cc71ecdaebd1de1ad`.
- Device: Xiaomi 2312DRA50C, Android 16.
- Manual phone testing across the music-video workflow and Movie Recap. These results reflect the observed cases, not exhaustive coverage.
- This report records follow-up work only; no fixes are implemented here.

## Observed passing checks

- App self-test reported PASS, including bundled FFmpeg checks, Burmese overlay, and 540p/1080p rendering; WorkManager 1080p render check also reported OK.
- Music-video project: imported music, generated and approved a 14-shot storyboard, compiled a prompt, imported and approved a reference image, imported and accepted video, and rendered 540p and 1080p outputs. A 1080p output was saved to Gallery.
- Beat snap reported 12 of 13 cuts moved at about 118 BPM. This confirms the operation ran, not that the timing is musically accurate.
- Batch sheet HTML opened with the selected shot and prompt; copying the prompt into another app worked.
- Sharing an image into the project Inbox and attaching it to Shot 3 succeeded.
- Character token substitution worked. Changing the character description from a blue shirt to a red shirt marked the dependent shot Stale; Recompile cleared the warning and produced a prompt containing the updated description.
- Shot 2 video import and acceptance worked; the Accepted status remained visible after reopening the app. Persistence of the status was observed; the underlying media was not independently revalidated after reopening.
- Movie Recap: a 4:01 video produced 13 detected scenes; three scenes were selected. A per-scene script was generated from a supplied summary, Gemini TTS generated voice for all three scenes, and the fit check showed the scene durations and overruns. Preview 540p and export 1080p completed; the exported video was saved to Gallery.
- Backup created a roughly 52.7 MB ZIP for three projects in Download/AutoFlow. Restore reported three projects restored, and the tester confirmed projects were present afterward.

## Fixes and improvements requested — not implemented

### 1. Movie Recap: long burned-in captions obscure the video

**Observed:** During 1080p recap playback, long per-scene narration text appeared as many lines at once, covering much of the frame and overlapping existing video text. A subsequent shorter-caption example was more readable, but does not establish that the original problem is fixed.

**Suggested direction:** Split long narration into readable, timed caption chunks; keep captions within a safe area, limit the number of simultaneous lines, and verify Burmese shaping and readability in both portrait and landscape exports. Confirm caption timing against narration. Review the exported SRT as well as the burned-in result.

**Acceptance checks:** Long Burmese narration does not occupy most of the frame or run off-screen; caption chunks remain legible, are timed appropriately, and work at 540p and 1080p. Verify on phone with representative long text and source footage containing its own text.

### 2. Show reference-image and video thumbnails on shot cards

**Observed:** In Flow Handoff, shot cards show prompt text and statuses such as Imported or Accepted but no reference-image or video thumbnail. It is hard to tell at a glance which media was attached. The tester requested thumbnails for both kinds of media.

**Suggested direction:** Show separate, clearly labeled reference-image and video thumbnails on each relevant shot card, with explicit empty states when no media is present. Make the video thumbnail recognizable as video, and consider tap-to-preview. Keep text status in addition to thumbnails and distinguish the selected/accepted video when versions exist. Treat these as proposals to validate during implementation, not existing behavior.

**Acceptance checks:** A shot with no media, one with an image, and one with a video are distinguishable at a glance; the displayed thumbnail matches the attached media, remains correct after reopening, and handles unavailable media gracefully.

## Pending / not verified

- Listening-dependent checks: narration intelligibility and pronunciation, audio mixing, and whether lyric timings and beat cuts match the actual song. Arrange a separate listening test.
- The lyrics UI reported four synced lines, but their correctness against the song has not been verified. Gemini timing suggestions were not verified.
- Multiple video versions and switching between versions were not verified; choosing the Final setting alone does not establish that version switching works.
- The exported SRT file and its timing were not independently inspected.
- No claim is made that all devices, long videos, failure cases, or every workflow feature have been tested.

## Notes

- The character description appeared twice in one compiled test prompt because the token was entered in both Subject and Action during testing; this alone is not evidence of a compiler defect.
- Movie Recap source footage should be owned or used with permission.
