# Spec: Flow Handoff (manual)

## Actions
- **Copy Prompt**: clipboard ← VeoFlowDialect string. Sets handoff=SENT_TO_FLOW.
- **Open Flow**: `https://labs.google/fx/tools/flow` via platform open-URL. No embedded webview.
- **Export Package** → `packages/shot-03/`: `prompt.txt`, `ref-a.png`, `ref-b.png`, `start-frame.png` (if chaining), `shot.json` (timecode, ratio, duration, pass, suggested Flow mode: "Ingredients to Video" or "Frames to Video").
- **Batch sheet** → `packages/batch-<date>.html`: all selected shots, prompt + images inline, copy buttons (plain JS, offline).
- **Import Video**: file picker or inbox. Validates container (mp4/mov), reads duration/ratio via ffprobe; warns if ratio ≠ project ratio or duration deviates > 1.5s.

## Inbox
`FolderWatcher(inbox/)` emits new files. UI shows them with "Attach to shot…" (default suggestion = most recent SENT_TO_FLOW shot). On attach: ArtifactStore.put(bytes, kind=vd, inputs={promptIr, ref}, note=pass) → move original to `.cache/inbox-done/` (not deleted) → handoff=IMPORTED.

## Credit log
Manual. On IMPORTED, prompt the user: "Credits used? [20] (Fast) / [100] (Quality) / custom / skip". Values default from `config/models.json.flow.creditsHint` and are editable.

## Forbidden
Any form of automated interaction with Flow (DOM scripting, cookie reuse, undocumented endpoints).
