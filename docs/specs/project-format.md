# Spec: Project Format (v1)

## Folder
```
<Name>.autoflow/
├─ project.json          manifest (see §2)
├─ source/               user inputs: song.mp3, lyrics.txt, imported movie (recap)
├─ artifacts/            immutable, write-once, content-hash named
├─ inbox/                drop zone for Flow downloads; files moved out after attach
├─ exports/              rendered outputs
├─ packages/             exported shot packages / batch sheets
└─ .cache/               proxies, thumbnails (safe to delete)
```

## 1. Artifact naming
`<kind>_<first 12 hex of sha256(bytes)>.<ext>` with a sidecar `<kind>_<hash12>.meta.json` (Artifact record; no data extension in the sidecar name, so the id alone locates it). The artifact list is always rebuildable by scanning `artifacts/` for sidecars; no index file is authoritative.
Kinds: `an` analysis · `sb` storyboard · `sp` shotspec · `pr` promptIR · `im` image · `vd` video · `au` audio · `tx` transcript · `rg` rendergraph.
Putting identical bytes twice returns the same id (dedupe); `ArtifactStore.recordInputs` may then refresh that sidecar's `inputs` (D-29). Deleting is never done by the app; a "Clean unreferenced" tool may list them for the user (phase 2+).

## 2. project.json
```json
{
  "schemaVersion": 1,
  "id": "uuid",
  "name": "Midnight Rain",
  "mode": "MUSIC_VIDEO",
  "createdAt": "...", "updatedAt": "...",
  "settings": { "ratio": "9:16", "clipMs": 8000, "durationMs": 200000, "storyboardMode": "NARRATIVE",
                "songStyle": "ballad", "videoStyle": "cinematic", "mood": "melancholic, hopeful",
                "textModel": "ref:models.text.default", "imageModel": "ref:models.image.default" },
  "globalStyle": { "visual": "...", "palette": "...", "lighting": "...", "camera": "...", "negative": "...", "hash": "gs_..." },
  "characters": [ { "token": "HERO", "nameMy": "...", "descriptionEn": "...", "refImages": ["im_..."] } ],
  "analysis": "an_...",
  "lyricTiming": "tx_...",
  "storyboards": ["sb_...", "sb_..."],
  "currentStoryboard": "sb_...",
  "shots": [ { "number": 1, "startMs": 0, "durationMs": 8000, "section": "INTRO", "lyricsMy": "...",
               "spec": "sp_...", "promptIr": "pr_...", "refImages": ["im_a","im_b"], "chosenRef": "im_a",
               "videos": ["vd_..."], "acceptedVideo": null,
               "approvals": { "storyboard": true, "prompt": true, "ref": true, "video": false },
               "handoff": { "state": "SENT_TO_FLOW", "sentAt": "...", "pass": "DRAFT", "note": null } } ],
  "timeline": null,
  "flowCredits": { "manualLog": [ { "date": "2026-11-10", "credits": 20, "shot": 3 } ] }
}
```
`lyricTiming` (optional, D-30): the `tx` artifact with the song's lyric line timings, `{"schemaVersion":1,"source":"tap","lines":[{"text":"...","startMs":1200,"endMs":4000}]}`; used only while its line texts equal the lyrics' non-blank, non-`[Section]` lines.
Writes: serialize → `project.json.tmp` → fsync → rename over `project.json`. Keep `project.json.bak` (previous version).

## 3. Migration
`schemaVersion` bump requires a `Migration` object in `:core` with a unit test loading a fixture of the old version.
