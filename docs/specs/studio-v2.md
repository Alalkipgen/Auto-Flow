# Spec: Studio v2 (Phase 5, D-35 … D-48)

Recap update + new UI. Builds on docs/specs/recap.md (Phase 4); where they differ, this file wins for v2 projects.
Screens: DESIGN.md §7. Tasks: PLAN Phase 5.

## 1. Feature flags (D-41)
`config/features.json` (data, bundled, read at start):
```json
{ "schemaVersion": 1, "studioV2": false, "quickRecap": false,
  "labs": { "voiceClone": false, "dubbing": false, "vocalSeparation": false, "smartReframe": false,
            "autoCaptionPlacement": false, "wordKaraoke": false, "wholeMovieTranscribe": false, "edgeVoices": false } }
```
`FeatureFlags` (`:core`) = the file overridden by the app settings `featureOverrides` (Self-test → "Preview features").
Only the UI reads flags; `:core` rules (RecapRules, WorkflowRules) never depend on them.

## 2. Recap data v2 (D-36)
`rs` v2 (each story change writes a new one; v1 stays readable):
```json
{ "schemaVersion": 2, "source": "gemini" | "manual" | "quick", "style": "PLAIN" | "COMMENTARY",
  "register": "SPOKEN" | "WRITTEN",
  "blocks": [ { "id": "b_1a2b3c4d", "clips": [ { "inMs": 12000, "outMs": 16200 } ],
                "textMy": "…", "speakMy": null } ] }
```
- `clips`: movie ranges played in order; at least one per block, each ≥ 0.5 s and inside the movie. Block length = the
  sum of its clips. Split / merge / reorder / trim write a new `rs` and clear `storyApproved`.
- `speakMy`: optional "speak as" text for TTS; captions always show `textMy`.
- Outdated (recap.md §4) for v2: a story is outdated only when a clip lies outside the current movie (new import).
  Picks build the first story; afterwards "Rebuild from picks" makes a new one (confirm: what becomes stale).
- Approval: `storyApproved` only for a current story with no blank `textMy` (RecapRules).

`project.recap` v2:
```json
"recap": {
  "rightsConfirmed": true, "movie": { "…": "as v1" }, "targetMs": 300000,
  "scenes": [ { "startMs": 0, "endMs": 4200, "picked": false } ], "sceneThreshold": 10,
  "sources": { "subtitles": "source/subtitles.srt", "subtitleTrack": 2, "summary": "source/summary.txt",
               "transcript": "tx_…" },
  "story": "rs_…", "storyApproved": false,
  "voiceLock": { "provider": "gemini", "model": "gemini-3.8-flash-tts", "voice": "Charon", "lang": "my" },
  "takes": [ { "block": "b_1a2b3c4d", "audio": "au_…", "source": "TTS" | "RECORDED" | "IMPORTED" | "POLISHED",
               "provider": "gemini", "model": "gemini-3.8-flash-tts", "voice": "Charon",
               "durationMs": 3900, "spokenHash": "…", "createdAt": "2026-11-13T10:00:00Z" } ],
  "chosenTakes": { "b_1a2b3c4d": "au_…" },
  "fit": { "b_1a2b3c4d": "EXTEND" },
  "layers": [],
  "audio": { "movieVolume": 0.2, "bgm": "au_…", "bgmVolume": 0.18, "duck": true, "polish": true,
             "trimSilence": false, "loudnessLufs": -14 },
  "captions": { "burn": true, "style": "default", "rect": [0.05, 0.78, 0.9, 0.17],
                "chunkEdits": { "b_1a2b3c4d": [1800, 3600] } },
  "looks": ["ZOOM"],
  "quality": "AUTO",
  "quick": null
}
```
- Takes: the chosen take, else the newest, per block. **Stale** when `spokenHash` ≠ SHA-256 (12 hex) of
  `lexicon(speakMy ?: textMy)` (recorded takes too: a text edit stales them, as in v1). **Different voice** when a
  TTS take's provider / model / voice ≠ `voiceLock`; **voice unknown** when `model` is null (made before the lock).
- `chunkEdits`: caption chunk boundaries (ms into the take) the owner dragged; empty = automatic (`CaptionChunks`).

Migration 1 → 2 (`:core` `Migration`, fixture `core/src/commonTest/resources/migration/recap-v1.json`):
1. copy `project.json` → `project.v1.bak.json` (atomic; only if it does not exist);
2. `script` → new `rs` v2: one block per segment, `id` = `b_` + 8 hex of SHA-256("<index>:<startMs>-<endMs>"),
   `clips` = [{inMs: startMs, outMs: endMs}], same `textMy`, `speakMy` null; `story` = it, `storyApproved` =
   `scriptApproved`;
3. takes: `segment` index → block id, `spokenHash` = `textHash`, `provider`/`model`/`voice` = null for old TTS takes;
4. `voiceSource` = TTS → `voiceLock` {gemini, first candidate that speaks `my`, `ttsVoice`, "my"}, else null;
5. `subtitles` → `sources.subtitles`, `movieVolume` → `audio.movieVolume`, `burnCaptions` → `captions.burn`;
6. `schemaVersion` = 2. Running it on a v2 project changes nothing.

## 3. TTS config and the voice lock (D-35, D-45)
```json
"tts": { "candidates": [ { "id": "gemini-3.8-flash-tts", "langs": ["my", "en"] },
                         { "id": "gemini-3.8-flash-lite-tts", "langs": ["en"] },
                         { "id": "gemini-2.5-flash-preview-tts", "langs": ["en"] } ],
         "rpm": 3, "replication": "unavailable",
         "azure": { "voices": [ { "id": "my-MM-ThihaNeural", "lang": "my" }, { "id": "my-MM-NilarNeural", "lang": "my" } ] } }
```
- `langs` are copied from the Gemini speech-generation language table when the file is edited (the values above are
  placeholders except Burmese on 3.8 Flash TTS); a model whose list lacks the script language is never offered for it.
- Lock picker: models that speak the language, in `candidates` order; default = the first. The first TTS take sets the
  lock if it is empty.
- `speak()` uses exactly the lock. 429 (with `retryDelay` ≤ 60 s) / 500 / 503 → D-15 backoff, max 3 tries → the job
  pauses: "<model> is busy · Resume". 400 / 404 "model not found" → the job fails: "Model unavailable · Change voice…".
  No automatic model or voice change, ever.
- "Change voice…": shows the takes that would become "Different voice" and offers "Re-speak N blocks (≈ M min)" or
  "Keep old takes".

## 4. Layers and live preview (D-37, D-42)
```json
{ "id": "l_…", "type": "BLUR" | "IMAGE" | "TEXT" | "BORDER" | "BACKGROUND",
  "rect": [0.0, 0.82, 1.0, 0.18], "fromMs": 0, "toMs": null, "params": { } }
```
`rect` = x, y, w, h in 0–1 of the output frame (BORDER / BACKGROUND ignore it); `fromMs` / `toMs` = output time
(null = to the end). List order = drawing order; captions are drawn last (so captions sit on a blur mask).
| type | params | export (filtergraph) |
|---|---|---|
| BLUR | `sigma` 20, `feather` 0–0.05 | `crop` rect → `gblur` → `overlay` (alpha ramp when feathered) |
| IMAGE | `image` (`im_…`), `opacity` | `scale` to rect width (aspect kept) → `overlay` |
| TEXT | `textMy`, `style` | libass event, same font rules as captions |
| BORDER | `px` (at 1080p), `colour` | `drawbox` × 4 |
| BACKGROUND | `mode` BLUR / IMAGE / COLOUR, `image`, `colour` | when the movie's aspect ≠ the output ratio and Fill frame is off: `split` → scaled + `gblur` copy (or image / colour) behind the fitted movie |
Preview: rect × preview size, drawn in Compose over ExoPlayer; text with the same font file and `CaptionChunks`
breaks. "Exact frame": the compiled graph with `-ss <t> … -frames:v 1` → `.cache/frames/<t>.png`.

## 5. Jobs (D-38)
Job file `filesDir/ai-jobs/<work id>.json` (D-26 rules): `{ "id", "kind": "TTS_ALL" | "TRANSCRIBE" | "QUICK_RECAP" |
"PUBLISH_KIT", "project", "items": ["b_…"], "state": "QUEUED" | "RUNNING" | "PAUSED" | "DONE" | "FAILED" |
"CANCELLED", "error" }`.
- Pacing: at most `rpm` requests per rolling minute per model; a 429 `retryDelay` wins.
- ETA = remaining items × 60 / rpm (or the measured time per item, whichever is longer).
- Resume = run again; finished items are found from the project (a current take exists), not only from the file.
- Desktop: the same runner in-process; the Jobs screen lists both chains.

## 6. Library (D-39)
`<app data>/library/library.json` + `files/`:
```json
{ "schemaVersion": 1,
  "brandKits": [ { "id": "k_…", "name": "…", "logo": "files/…png", "watermark": "files/…png", "colours": ["#…"],
                   "font": "files/…ttf", "captionStyle": "…", "layers": [], "intro": null, "outro": null } ],
  "voices": [ { "id": "v_…", "name": "…", "lock": { "provider": "gemini", "model": "…", "voice": "…", "lang": "my" } } ],
  "lexicon": [ { "written": "…", "spoken": "…" } ],
  "music": [ { "id": "m_…", "name": "…", "file": "files/…m4a", "durationMs": 0 } ],
  "fonts": [ { "id": "f_…", "name": "…", "file": "files/…ttf" } ],
  "presets": [ { "id": "p_…", "name": "…", "kind": "CAPTION" | "STYLE" | "EXPORT", "value": { } } ] }
```
- Apply = copy each file into the project (`im`, `au`, new kind `ft` font; project-format.md) + the values into
  `project.json`. Projects never reference library paths.
- Lexicon: whole-word replacements, longest `written` first; an edit changes `spokenHash` → takes ⚠ Stale
  ("Lexicon changed").

## 7. Source: subtitles and scene transcription (D-43, D-44)
- Tracks: `ffprobe -v error -select_streams s -show_entries stream=index,codec_name:stream_tags=language,title -of json`.
- Extract: `ffmpeg -i <movie> -map 0:s:<n> -c:s srt -f srt source/subtitles.srt.part` → rename. Text codecs: subrip,
  ass, ssa, mov_text, webvtt, text. Picture codecs (hdmv_pgs_subtitle, dvd_subtitle, dvb_subtitle, xsub) → message +
  Transcribe.
- Transcribe picked scenes (only scenes without subtitle lines): per range `-ss <in> -to <out> -vn -ac 1 -ar 16000`,
  joined, `-c:a aac -b:a 32k` chunks ≤ 10 min (`.cache/transcribe/<n>.m4a`); the prompt lists "scene i: start–end s"
  in chunk time; output `{scenes:[{scene, lines:[{start, end, text}]}]}`; a missing scene or a time outside its scene
  → retry once, then fail. Saved as `tx` `{schemaVersion: 2, source: "gemini-scenes", lang, lines:[{startMs, endMs,
  text}]}` (movie time). The script writer takes the lines inside each block's clips (≤ 600 characters per scene).

## 8. Smart Fit (D-46)
When take + 0.4 s > block length, the block shows the fixes in order:
1. **Trim silences**: `silenceremove`, gaps > `silenceGapMs` (350) → 200 ms; a derived take (`source: "POLISHED"`).
2. **Shorten text**: Gemini rewrite to ≈ block length × 4 syllables / s; shown as a diff; applied only on approve
   (new `rs`, take ⚠ Stale).
3. **Extend**: `EXTEND` (movie runs on, recap.md §6) or `NEXT_RANGE` (adds the following movie range as a clip).
4. **Speed**: voice `atempo` ≤ `maxVoiceSpeed`, picture `setpts` slow-down ≥ `maxVideoSlow`.
5. **Freeze**: hold the last frame.
`config/recap.json`: `{ "fit": { "maxVoiceSpeed": 1.0, "maxVideoSlow": 1.0, "silenceGapMs": 350 } }` — 1.0 = off
until spike S8. Default fix = `EXTEND`; the choice is saved in `recap.fit`.

## 9. Export pre-flight and Publish Kit
Pre-flight (computed from data, no render): blocks with no voice · stale takes · different / unknown voice · a take
longer than its block by > 2 s with no fix · captions over an IMAGE layer · free space < 2 × the estimated output ·
estimated render time (last measured speed, `.cache/render-speed.json`) · the rights notice. Each row: "Fix →".
Quality Auto = the movie's height, at most 1080; 1440 only when picked.
Publish Kit: text stage `publishKit` → `{title, description, hashtags:[], chapters:[{atMs, title}]}` (Burmese;
chapter times come from blocks); thumbnail = an Exact frame + a TEXT layer → `exports/<name>.thumb.jpg`; all text in
`exports/<name>.publish.txt`; Copy / Share per item.

## 10. Quick Recap (D-47)
A normal Recap project with `recap.quick = { "stage": "SCENES" | "PICK" | "SCRIPT" | "REVIEW" | "VOICE" | "RENDER" |
"DONE", "targetMs", "style" }`.
- Needs subtitles or a transcript; without them the wizard offers Extract / Transcribe first (confirm card).
- `recapPick` (text stage, `config/models.json` candidates): input = target, scenes (index, start, length, subtitle
  text ≤ 300 characters); output `{picks:[index]}`; valid when indices exist and the total is within ±20 % of the
  target; else retry once, then "pick scenes yourself" (opens Source).
- Then the normal script writer (rs v2, `source: "quick"`), **stop at REVIEW** (Story step, Approve / Edit), then a
  TTS_ALL job with the voice lock, then a render job (Export 1080p). Each stage is a Jobs row.
