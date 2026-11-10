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
`FolderWatcher(inbox/)` emits new files; the kind is decided by extension:
- **Video** (mp4/mov): "Attach to shot…" (default suggestion = most recent SENT_TO_FLOW shot). On attach: ArtifactStore.put(bytes, kind=vd, inputs={promptIr, ref}, note=pass) → handoff=IMPORTED.
- **Image** (png/jpg/jpeg): "Attach as Ref A / Ref B of shot…" (default suggestion = shot whose image prompt was copied most recently). On attach: ArtifactStore.put(bytes, kind=im, inputs={promptIr}) → set as the shot's Ref A/B. Does not change the shot's handoff state.
- Other files are listed but cannot be attached.

In all cases the original is moved to `.cache/inbox-done/` (not deleted).

## Credit log
Manual. On IMPORTED, prompt the user: "Credits used? [20] (Fast) / [100] (Quality) / custom / skip". Values default from `config/models.json.flow.creditsHint` and are editable.

## Forbidden
Any form of automated interaction with Flow (DOM scripting, cookie reuse, undocumented endpoints).
