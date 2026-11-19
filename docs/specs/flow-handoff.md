# Spec: Flow Handoff (manual)

## Actions
- **Copy Prompt**: clipboard ← VeoFlowDialect string. Sets handoff=SENT_TO_FLOW.
- **Open Flow**: `https://labs.google/fx/tools/flow` via platform open-URL. No embedded webview.
- **Export Package** → `packages/shot-03/`: `prompt.txt`, `ref-a.png`, `ref-b.png`, `start-frame.png` (if chaining), `shot.json` (timecode, ratio, duration, pass, suggested Flow mode: "Ingredients to Video" or "Frames to Video").
- **Batch sheet** → `packages/batch-<date>.html`: all selected shots, prompt + images inline, copy buttons (plain JS, offline).
- **Copy Image Prompt** (D-14): clipboard ← ImageDialect string for Ref A/B (via `ManualImageProvider`); the user generates the image in AI Studio or Flow.
- **Import Image** (D-14): file picker or inbox; png/jpg → Ref A/B of the shot (see Inbox). Warns if the image ratio ≠ project ratio.
- **Import Video**: file picker or inbox. Validates container (mp4/mov), reads duration/ratio via ffprobe; warns if ratio ≠ project ratio or duration deviates > 1.5s.

## Inbox
Inbox scans `<project>/inbox/` on entry, refresh and share arrival. Android copies shared media into app-owned `.shared-inbox/` first; `SharedInbox` moves it into the open project's Inbox without overwriting an existing name. The kind is decided by extension:
- **Video** (mp4/mov): "Attach to shot…" (default suggestion = most recent SENT_TO_FLOW shot). On attach: ArtifactStore.put(bytes, kind=vd, inputs={promptIr, ref}, note=pass) → handoff=IMPORTED.
- **Image** (png/jpg/jpeg): "Attach as Ref A / Ref B of shot…" (default suggestion = shot whose image prompt was copied most recently). On attach: ArtifactStore.put(bytes, kind=im, inputs={promptIr}) → set as the shot's Ref A/B. Does not change the shot's handoff state.
- Other files are listed but cannot be attached.

After a successful attach, the Inbox copy is moved to `.cache/inbox-done/`; the shot uses its separate immutable artifact.

**Owner follow-up — Inbox deletion.** The reported missing action is not a storage-permission failure: `InboxScreen` offers Attach and Refresh, and `InboxModel` / `SharedInbox` only scan, attach or move files. Add a per-file **Delete…** action and a separate confirmation for both the project Inbox and any waiting shares displayed there. Delete only the selected, unchanged regular file in the app's intake directory. Reject linked paths, partial imports and stale selections; refresh the list after the attempt and show its real result. Do not delete the sender's file, another same-name share, an attached artifact, an archived copy, or any project metadata. There is no Inbox trash or bulk action.

Scope: `InboxModel`, `InboxScreen`, `SharedInbox`, their localized strings, a narrowly scoped deletion capability in `:platform`, desktop regression tests, `PLAN.md` and `PHONE-QA.md`. Follow UI v3 wrapping, touch-target and English/Burmese rules.

**Owner follow-up — context-only action.** PR #215 placed Delete beside Attach (and beside each waiting filename), unlike the Projects, Library and Jobs removal flows. Remove those inline buttons. Holding the file card, right-clicking it on desktop or using its accessibility action opens the shared `CardRemoval` menu; **Delete…** then opens the filename-specific confirmation. A normal tap does not open Delete, and **Attach to Shot…** and **Move here** keep their own actions. Capture the guarded copy at menu entry; don't retarget a changed file while the menu is open. This follow-up only touches `InboxScreen`, the shared `CardRemoval` and its Library/Jobs call sites, Inbox UI regression tests and the plan/spec/phone QA. Storage and deletion rules above stay unchanged.

## Credit log
Manual. On IMPORTED, prompt the user: "Credits used? [20] (Fast) / [100] (Quality) / custom / skip". Values default from `config/models.json.flow.creditsHint` and are editable.

## Forbidden
Any form of automated interaction with Flow (DOM scripting, cookie reuse, undocumented endpoints).
