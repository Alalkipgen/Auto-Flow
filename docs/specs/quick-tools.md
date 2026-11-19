# Spec: Quick tools — Subtitles and Voice & Audio (Phase 7)

Status: **planned, not implemented** (P7-0, 2026-11-20). Source: the owner's Phase 7 plan bundle
(`AutoFlow-Phase7-Subtitles-Voice-Dubbing-Plan.zip`), ported by editing, with code facts re-checked at
`8d092a0` (top of the UI v3 Draft stack, PR #216; §14). Decisions D-77…D-86 (owner answers: phone checks never block, Edge TTS instead of Azure); Q-36…Q-44 in PLAN.
Dubbing has its own spec: [dubbing.md](dubbing.md). Order: Subtitles → Voice & Audio → Dubbing.

## 1. Rules for every Phase 7 PR
1. **Reuse engines, not screens.** Lift pure logic out of `RecapModel` into `:core` / `:media` services. Recap and
   Music Video renders, `project.json` and job files stay byte-identical; tests prove it before and after each lift.
2. **Each tool works alone** as a real project folder (index, Jobs, resume, Backup, Remove/Restore, Delete
   permanently per D-76). Each is proven by a test per mode; a folder alone proves nothing.
3. **Free first.** A text track inside the video or an `.srt` comes before any Gemini call. Every paid call has a
   confirm card with the real request count and today's usage, runs as a resumable job and retries only what failed.
4. **Originals are never lost.** Original text and Burmese are separate fields; saves create new immutable versions;
   a translation never silently replaces a line the owner edited; never auto-translate (AGENTS.md §1).
5. **Honest UI.** Real progress only; no invented ETA, quota or "Saved". A tile stays Planned until its flag is on
   and the route really works.
6. **Shared components, theme tokens and `UiText` only**, one composable tree for all widths (D-22): < 600 dp one
   column; 600–839 dp one column with wider cards; ≥ 840 dp two panes. Burmese uses `My.breakSyllables`.

## 2. Reuse map (verified at `8d092a0`)
| Capability | Existing code | Used by |
| --- | --- | --- |
| Subtitle probe / extract | `MediaProbe` → `MediaInfo.subtitleTracks`, `SubtitleTrack` (text vs picture codecs), `MediaProbe.extractSubtitleArgs`; orchestration now in `RecapModel.extractSubtitleTrack()` | S-2, D-1 |
| `.srt` | `core/recap/Srt.kt`: `Srt.parse`, `Srt.write`, `SrtCue(startMs, endMs, text)` | S-2, S-4 |
| Transcript | `Transcript` v2 (`tx`), no speaker field | S-5 |
| Picked-scene transcription | `SceneAudioChunks` (`MAX_CHUNK_MS = 600_000`), `SceneAudio` (mono 16 kHz AAC), `TranscribeProvider.transcribeScenes`, `AiJobKind.TRANSCRIBE`, `TranscribeConfirm` | S-5 |
| Whole-video transcription | flag `labs.wholeMovieTranscribe` only, no code | S-5 builds it |
| Translation | none (prompts say "do not translate") | S-6 builds it |
| Burmese captions | `CaptionChunks`, `AssWriter`, libass, `CaptionStyle` presets Clean/Boxed/Outline | S-4, D-3 |
| Preview | `VideoPreview`, `MoviePlayback` (ExoPlayer; VLCJ + installed VLC 3.0.x on desktop), Recap caption overlay | S-3, D |
| TTS | `TtsProvider.speak(text, voice, lang, style, lockedModel)`, `VoiceLock`, `Lexicon`, Library `SavedVoice` | V-1, D-2 |
| Audio filters | `AudioFilters`: polish, silence trim, BGM, sidechain duck, two-pass `loudnorm` −14 LUFS | V-2, V-3, D-3 |
| Timed mix | `VoiceTrack` + `VoiceCue(file, startMs, speedFactor)` → `adelay`/`atempo`/`amix`; original at constant `clipsVolume` | V-3, D-3 |
| Jobs / output | `AiJob` (F3 `timings`), `AiJobExecutor`, `RpmPacer`, Android `AiWorker`; `RenderJob`; `FileShare.open/share/saveToGallery` | all |

## 3. Tool projects (D-77, D-78)
- `Mode` gains `SUBTITLES` (S-1), `VOICE_AUDIO` (V-1), `DUBBING` (D-1). Every `Mode` use in the 8 main files
  (`V1ProjectCopy`, `ExportModel`, `HomeContinue`, `HomeScreen`, `DashboardModel`, `DashboardScreen`, `AppShell`,
  `ShellModel`) gets an explicit branch — no silent `else`. Home Continue today labels every non-Recap mode
  "Music Video" (`HomeScreen.kt`); S-1 makes it an exhaustive `when` with a step line per mode.
- Folder: `project.json` · `source/` (`movie.<ext>` via `ProjectSession.importMovie`; `input.<ext>` for audio) ·
  `artifacts/` (`st_…` new; `tx_…`, `au_…`) · `exports/` (`.part` while writing) · `.cache/` (chunks, job scratch,
  `st-draft.json`; never backed up).
- Optional `Project` fields, one per mode, each `@EncodeDefault(EncodeDefault.Mode.NEVER)`:
  `subtitles: SubtitlesTool?` (`video`, `current` st id, `exportText`, `style: CaptionStyle`);
  `voice: VoiceTool?` (`task`, `text`, `lock`, `tone`, `parts[{id, hash, take}]`, `joined`);
  `dubbing: DubbingTool?` (see dubbing.md §3). `SourceVideo` = `RecapMovie` + `fps` (`MediaInfo.fps`).
- Create on pick: Subtitles opens the system picker at once; Cancel creates nothing. Probe first when a readable path
  exists; create only for a readable video, then copy with the existing notice. If the probe can only run after the
  copy and fails, keep the project with the error (owner may Remove); the app never deletes files by itself.
  Auto name `<file name without extension> · <yyyy-MM-dd HH:mm>`; Rename from ⋮. No naming dialog or settings form.
- Projects shows a mode chip (`V3_SUBTITLES`, `V3_VOICE_AUDIO`, `V3_DUBBING`), no shot counter; Open → the tool at its
  last step. Jobs bind via `AiJob.project` + `projectIdentity`; Backup takes the index list; Remove/Restore and
  Delete permanently follow D-76 unchanged.
- **Compatibility (D-78).** `CoreJson` is strict (`ignoreUnknownKeys = false`, `encodeDefaults`, `explicitNulls`),
  so a plain new field would write `null`s into every MV/Recap `project.json`. The index
  (`~/.autoflow/projects.json`) ignores unknown keys, but an unknown `Mode` value fails the whole file; an older build
  then starts empty and rewrites it on the next change (dropping `removedPaths` / `deletedProjects`). S-1: decode
  entries one by one, keep unknown ones verbatim, show `NEEDS_NEWER_APP`; same check for persisted jobs with new
  `AiJobKind`s and for Backup restore. Owner rule: after a tool project exists, don't install a build without Phase 7
  (CI sets `versionCode` from a Gradle property, so an older branch's later run can install over a newer build).

## 4. Subtitle document — `st` artifact (D-79)
```json
{ "schemaVersion": 1, "source": "track:2", "derivedFrom": "st_9c1e0b7a44d2", "lang": "en", "nextId": 1285,
  "cues": [ { "id": "c1", "startMs": 723100, "endMs": 725800, "text": "We have to leave tonight.",
              "textMy": "ဒီည ထွက်သွားရမယ်။", "myFrom": "5be0c2d1", "myEdited": false,
              "spokenMy": null, "speaker": null } ] }
```
- `source`: `track:<stream index>` · `srt` · `tx:<tx id>` · `edit`; `derivedFrom`: previous `st` or null.
- `myFrom` = first 8 hex of SHA-256 of `text` when `textMy` was made; `myEdited` = the owner typed `textMy`.
  `speaker` is reserved (empty in v1); `spokenMy` is Dubbing only.
- Ids never change: split gives the second half `c<nextId>`, merge keeps the first id. Save = new immutable version,
  only the `project.json` pointer moves. Raw transcription stays `tx`; the first `st` records it as input.
- S-2 adds `st` to `ArtifactKind`, [project-format.md](project-format.md) §1, Backup and artifact tests.
- Pure `:core` ops with unit tests: `split`, `merge`, `insertAfter`, `delete`, `setTimes`, `shift(deltaMs, fromId?)`,
  `sort`, `checks(config)`, `toSrt(textChoice)` (numbering 1…n), `fromSrt`, `fromTranscript(tx)`,
  `applyTranslation(batch)`, draft `undo/redo` (last 50). Times are integer ms; display 0.1 s; nudge 100 ms.

## 5. Subtitles screens (S-1…S-6)
Flow: `Home → Subtitles → pick video → source → edit with preview → (translate) → export .srt and/or burned video`.

| State | Screen | Sticky bar |
| --- | --- | --- |
| No source | Source chooser | Continue |
| Extracting / importing | chooser + progress line | Cancel |
| Transcribing (S-5) | job card; editor once lines exist | Pause · Cancel |
| Editing, saved | Editor | Translate to Burmese (only if `lang ≠ my`) · Export |
| Editing, unsaved | Editor + "Not saved yet" | Discard · Save |
| Translating (S-6) | read-only editor + job card + "Editing pauses while translating" | Cancel |
| Exporting | export sheet progress + Jobs row | Cancel |

- **Source chooser:** first-frame preview and facts; one `OptionCard` per text track (language · codec · title),
  Import `.srt` (`IMPORT_SUBTITLES`), Transcribe (Planned chip until S-5; disabled with `MOVIE_NO_SOUND_TO_TRANSCRIBE`
  without sound), picture tracks named with `SUB_PICTURE_TRACK`. Default: a Burmese text track, else the first text
  track, else Import. `.srt` encodings: UTF-8 (± BOM), UTF-16 with BOM, else UTF-8 then Windows-1252, saying which
  was used. Zawgyi: see Q-44. A broken `.srt` shows a message (cue number when known) and saves nothing.
- **Editor:** `LazyColumn` keyed by cue id (2,000+ lines smooth); `CueRow` shows number, times, duration, two-line
  clamp and check chips. Tap = seek + select; ✎/double tap = edit panel; the list follows the playhead unless the owner
  scrolled in the last 3 s. View chips Original / Burmese / Both and an issues chip (tap = next issue). Wide: left
  preview + scrubber + inline edit panel, right list; keys Space, ↑/↓, Enter, Ctrl+S, Ctrl+Z.
- **Edit panel** (sheet on phone): Original and Burmese text, Start/End `TimeField` with ±0.1 s and Use playhead,
  Split here (time = playhead inside the line, text = cursor, else a line break, else ask), Merge with next (first
  start → second end, texts joined by a line break), Add after, Delete (with Undo), Previous / Play line / Next.
  Times accept `h:mm:ss.mmm` or `mm:ss.mmm`; invalid input keeps the old value and says why.
- **Shift times:** All lines or From line n; amount with −1/−0.1/+0.1/+1; a shift before 0:00 is refused with the
  largest allowed amount.
- **Checks** never block Save: Overlaps next · Shorter than `minCueMs` · Line too long (Burmese width as
  `CaptionChunks`) · More than 2 lines · Empty · Not translated · Original changed. Export blocks only on no lines,
  end ≤ start, or no Burmese font for a Burmese burn; overlaps/empty lines offer "Fix automatically" as a draft edit.
- **Draft:** edits go to `.cache/st-draft.json` (debounced 1 s, not an artifact). A newer draft on reopen shows
  "Unsaved changes from {time}" → Restore · Discard. Save → new `st`, draft removed. Leaving with edits → Save ·
  Discard · Keep editing.
- **Transcribe (S-5, D-80):** confirm card with sound length, requests = chunks, "only the sound is sent", today's
  usage ("…" when unknown) and spoken language (`transcribe.languages`). Progress `chunk k of n`; Pause/Cancel keep
  finished chunks; Resume sends only the rest. Done → `tx` → new `st`. "Transcribe again" on an edited project asks
  and makes a new version.
- **Translate (S-6, D-81):** confirm card with lines, requests, "Only new or changed lines", style (everyday spoken /
  formal, Q-39), names list. Hand-edited `textMy` is skipped unless "Also replace my edits" is ticked (off). Result:
  one new `st`; a failed batch → "n lines not translated — Retry", the rest kept.
- **Export (S-4):** text Burmese / Original (Q-38). `.srt` → Share / Save to Downloads, instant. Video with subtitles:
  look Clean/Boxed/Outline + size, "Same size and frame rate as the video; sound copied", Check one frame at a line;
  real-progress job in Jobs; Done card Open · Share · Save to Gallery. Desktop has no `FileShare` yet: show the file
  in `<project>/exports/` + Open folder.

## 6. Voice & Audio screens (V-1…V-3)
- **Task chooser:** `TaskTile`s Text → voice · Clean up sound · Video sound. The project is created at the first real
  step (Continue with text, or a picked file).
- **Text → voice (V-1):** text field with counter and part count; `VoiceLockPicker` (saved voices first); tone chips
  Calm/Lively/News/Storytelling → TTS `style` (`tts.tones`); parts list with ✓/not voiced, ▶ and ↻. Parts split at
  `။ ? ! .` and line breaks up to `tts.maxCharsPerPart`; editing re-splits and only changed parts lose their take.
  Voice lock and tone are in each take key; nothing is re-spoken until Speak. Lexicon applied before TTS. Sticky:
  Speak n parts (confirm; requests = parts without a current take), then Save m4a / Save wav / Share.
- **Clean up sound (V-2):** pick audio or video → Shorten long pauses · Reduce noise · Even loudness (−14 LUFS) →
  Before/After (first 30 s, then the full job) → Save m4a / wav · Share. Video input gives audio; put it back via
  Video sound → Replace.
- **Video sound (V-3):** Save the sound only (copied when already AAC) · Replace the sound (shorter audio padded,
  longer cut — both stated) · Add voice or music on top (start, volume, "Lower the original while it plays").
  Every result says "The picture is not re-encoded".
- Audio formats (D-82): m4a (AAC) and wav; no mp3 (Q-37). Audio → Music/AutoFlow and `.srt` → Downloads need two
  small `FileShare` additions.

## 7. Jobs — one system
| Kind | Item key | Saved per item | Resume |
| --- | --- | --- | --- |
| `AiJobKind.MEDIA_TRANSCRIBE` | `k<startMs>` | `.cache/jobs/<job>/k<startMs>.json` | skip saved chunks; end → `tx` → `st` |
| `AiJobKind.SUB_TRANSLATE` | `b<first cue id>` | `.cache/jobs/<job>/b<id>.json` | skip saved batches; end → one `st` |
| `AiJobKind.TEXT_TTS` | `p<n>` | `au` take; key = hash(text after Lexicon + VoiceLock + tone) | skip current takes |
| `MediaTaskJob` (render queue, no AI) | single | `exports/<name>.part` → rename | restart the ffmpeg step |

F3 `timings` recorded for every AI item. Each AI kind gets a processor beside `RecapJobProcessor`. `MediaTaskJob`
(progress from `-progress`, cancel, Jobs row) lands in S-4 and is reused by V and D; `RenderJob` stays for graphs.

## 8. AI contracts (`:ai` interfaces, fakes in CI, live only with `-PliveAi`)
- **Whole-media transcription:** chunks in the `SceneAudio` format; first try `transcribeScenes` with one scene =
  the chunk; add `transcribeMedia(audio, mime, lang)` only if needed (fake + contract test). Offset chunk times,
  de-duplicate overlap lines by time, keep final times monotonic.
- **Translation:** `TextProvider.json(prompt, schema, system)`; input `{lang, style, names[{from, my}],
  context[{text, textMy}], lines[{id, text}]}`, schema `{lines:[{id, textMy}]}`. Valid = same ids, once, in order,
  non-empty. Invalid → one retry with the reason; invalid again → that batch fails alone. System prompt: natural
  Burmese on-screen subtitles in the chosen style, names from the list, keep numbers, no notes.
- **TTS:** `speak(text, voice, lang = "my", style, lockedModel)`; models only in `config/models.json`. V-1b adds
  `EdgeTtsProvider` (D-86: Thiha/Nilar, no key, flag `tts.edge`, unofficial — recoverable errors, Gemini stays available).
- **Fakes:** per-chunk transcriber; translator `textMy = "MY:" + text`; TTS duration = chars × `msPerChar`.

## 9. Media commands (golden args; real ffmpeg where timing/loudness is the claim)
| Use | Shape |
| --- | --- |
| Chunk cut points | `-vn -af silencedetect=noise=-35dB:d=0.4 -f null -`; cut nearest each 10-min mark within ±30 s, else hard cut with 2 s overlap |
| Burn | `-i src -vf "ass=<st>.ass:fontsdir=<fonts>" -map 0:v:0 -map 0:a:0? -c:v libx264 -preset <burn.x264Preset> -crf <burn.crf> -c:a copy` (else `-c:a aac`); `.ass` from `AssWriter` with `PlayResX/Y` = source size |
| Exact frame | `-ss <t> -copyts -i src -vf "ass=…" -frames:v 1 frame.png` |
| Sound only | `-vn -c:a copy` (AAC → m4a), else `-c:a aac -b:a <tts.m4aBitrate>`; wav `pcm_s16le` |
| Replace sound | `-map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -af apad -t <video duration>` |
| Add on top | existing mix/duck on `[0:a]` + new input; `-c:v copy` |

Single-source burns don't use `RenderCompiler` (`Output.fps` is an Int, ratios are presets): the direct command keeps
the exact size and frame rate (e.g. 1920×800, 23.976 fps) and copies the sound.

## 10. Flags and `config/tools.json` (D-84)
`config/features.json`: `tools.subtitles`, `tools.voiceAudio`, `dubbing`, `tts.edge` (D-86), default `false`, toggled in Self-test →
Preview features (D-41). `labs.wholeMovieTranscribe` / `labs.dubbing` retire when their replacements ship.
New `config/tools.json` (starting values; Burmese line length uses `CaptionChunks` width, not raw characters):
```json
{ "schemaVersion": 1,
  "transcribe": { "chunkMaxMs": 600000, "silenceWindowMs": 30000, "overlapMs": 2000,
                  "languages": ["auto", "en", "ko", "zh", "th", "ja", "hi", "my"] },
  "translate":  { "batchLines": 40, "contextLines": 5, "style": "spoken" },
  "subtitles":  { "minCueMs": 700, "maxCharsPerLine": 42, "maxLines": 2, "nudgeMs": 100 },
  "tts":        { "maxCharsPerPart": 500, "partGapMs": 300, "m4aBitrate": "160k", "tones": { } },
  "burn":       { "x264Preset": "veryfast", "crf": 20 },
  "dubbing":    { "minGapMs": 100, "maxVoiceSpeed": 1.0, "originalVolume": 0.15, "maxSourceMs": 600000, "stemGroup": 50 } }
```

## 11. Tests and acceptance
| PR | Must include |
| --- | --- |
| S-1 | create/reopen/backup/restore/remove/restore/delete per new mode; Home/Projects labels; index with an unknown mode; MV/Recap bytes unchanged |
| S-2 | `st` round-trip; ops; encodings (BOM, UTF-16, Windows-1252, Zawgyi detection); picture-track message; empty/broken `.srt`; 2,000-line import |
| S-3 | draft persist/restore; undo/redo; Burmese split/merge; UI smoke 360/412/840/1440 dp × font 1.0/1.3/2.0 × EN/MY |
| S-4 | real ffmpeg: burned cue times = document ±1 frame; size/fps kept; sound copied; `.srt` round-trip; cancel/resume |
| S-5 | fake chunks: kill/resume skips done chunks; silence cuts; monotonic join; overlap de-dup |
| S-6 | schema validation; one retry; batch isolation; `myEdited`/`myFrom` rules |
| V-1 | split/skip/join; take keys; joined = parts + gaps ± 50 ms; kill/resume |
| V-2 | golden args; real ffmpeg −14 LUFS ± 1 |
| V-3 | video stream unchanged (`-c:v copy`); duration kept; pad/cut |

Every new `UiText` key has EN and MY with identical placeholders (catalog tests). Owner milestones (phone + Windows):
- **S-A:** 10-min mp4 + English `.srt`: edit three lines, split one, merge two, shift all +0.5 s; preview timing right;
  restart keeps the saved version; `.srt` plays elsewhere; burned mp4 shows stacked consonants and matches preview;
  cancel and Backup work.
- **S-B:** 10–20 min video without subtitles: real counts on the confirm card; kill resumes without resending finished
  chunks; every line gets Burmese; editing an original marks only its translation; burned Burmese export.
- **V:** 2,000-character Burmese text with names, numbers, English → parts → redo one → m4a/wav play elsewhere; noisy
  file cleaned; sound saved, replaced, overlaid; video not re-encoded. Spike S9 (Gemini vs Edge, D-86) whenever the owner listens.

## 12. Proposed UiText (owner checks the Burmese)
Reuse: `IMPORT_SUBTITLES`, `SAVE_GALLERY`, `SHARE`, `CANCEL`, `TRANSCRIBE_USED_TODAY`, `MOVIE_NO_SOUND_TO_TRANSCRIBE`,
`V3_SUBTITLES`, `V3_VOICE_AUDIO`, `V3_DUBBING`, `V3_PLANNED`, `LOUDNESS_*`, `CLEAN_*`, `VOICE_POLISH`.

| Key | English | မြန်မာ |
| --- | --- | --- |
| SUB_START_HOW | How do you want to start? | ဘယ်လို စမလဲ? |
| SUB_USE_TRACK | Use the subtitles inside the video | ဗီဒီယိုထဲက စာတန်းကို သုံးမယ် |
| SUB_TRANSCRIBE | Turn speech into text (Gemini) | စကားသံကို စာသားပြောင်းမယ် (Gemini) |
| SUB_PICTURE_TRACK | {n} picture subtitle track(s) ({codec}) can't be read as text. | ပုံစံ စာတန်း {n} ခု ({codec}) ကို စာသားအဖြစ် မဖတ်နိုင်ပါ။ |
| SUB_ZAWGYI | This file looks like Zawgyi. Convert to Unicode? | ဒီဖိုင်က ဇော်ဂျီ ဖြစ်ပုံရတယ်။ ယူနီကုဒ် ပြောင်းမလား? |
| SUB_VIEW_ORIGINAL / BURMESE / BOTH | Original / Burmese / Both | မူရင်း / မြန်မာ / နှစ်ခုလုံး |
| SUB_TO_CHECK | {n} to check | စစ်ရန် {n} ခု |
| SUB_NOT_SAVED | Not saved yet | မသိမ်းရသေးပါ |
| SUB_SAVE / SUB_DISCARD | Save / Discard | သိမ်းမယ် / မသိမ်းဘူး |
| SUB_RESTORE_DRAFT | Unsaved changes from {time} | {time} က မသိမ်းခဲ့တဲ့ ပြင်ဆင်ချက် |
| SUB_START / SUB_END | Start / End | စချိန် / ဆုံးချိန် |
| SUB_USE_PLAYHEAD | Use playhead | ဖွင့်နေတဲ့ နေရာကို ယူမယ် |
| SUB_SPLIT / SUB_MERGE_NEXT | Split here / Merge with next | ဒီနေရာမှာ ခွဲမယ် / နောက်စာကြောင်းနဲ့ ပေါင်းမယ် |
| SUB_ADD_AFTER / SUB_DELETE | Add a line after / Delete line | နောက်မှာ စာကြောင်း ထည့်မယ် / စာကြောင်း ဖျက်မယ် |
| SUB_SHIFT / ALL / FROM | Shift times / All lines / From line {n} to the end | အချိန် ရွှေ့မယ် / စာကြောင်းအားလုံး / စာကြောင်း {n} ကနေ အဆုံးထိ |
| SUB_OVERLAP / SUB_TOO_SHORT | Overlaps the next line / Shorter than {s} s | နောက်စာကြောင်းနဲ့ ထပ်နေတယ် / {s} စက္ကန့်ထက် တိုနေတယ် |
| SUB_LINE_TOO_LONG | Line too long | စာကြောင်း ရှည်လွန်းတယ် |
| SUB_EMPTY / NOT_TRANSLATED / ORIGINAL_CHANGED | Empty / Not translated / Original changed | စာမရှိ / မပြန်ရသေး / မူရင်း ပြောင်းထားတယ် |
| SUB_TRANSLATE | Translate to Burmese (Gemini) | မြန်မာလို ဘာသာပြန်မယ် (Gemini) |
| SUB_TRANSLATE_CONFIRM | Translate {lines} lines: {requests} Gemini requests. | စာကြောင်း {lines} ကြောင်းကို ဘာသာပြန်မယ်: Gemini request {requests} ခု။ |
| SUB_STYLE_SPOKEN / FORMAL | Everyday spoken / Formal | ပြောဆိုသလို / စာအရေး |
| SUB_TRANSCRIBE_CONFIRM | {minutes} min of sound · {requests} Gemini requests · only the sound is sent | အသံ {minutes} မိနစ် · Gemini request {requests} ခု · အသံကိုပဲ ပို့မယ် |
| SUB_EXPORT_SRT / SUB_SAVE_DOWNLOADS | .srt file / Save to Downloads | .srt ဖိုင် / Downloads ထဲ သိမ်းမယ် |
| SUB_EXPORT_VIDEO / SUB_MAKE_VIDEO | Video with subtitles / Make video | စာတန်းပါ ဗီဒီယို / ဗီဒီယို ထုတ်မယ် |
| SUB_CHECK_FRAME | Check one frame | ပုံတစ်ကွက် စစ်ကြည့်မယ် |
| VA_TEXT_TO_VOICE / CLEAN / VIDEO_SOUND | Text → voice / Clean up sound / Video sound | စာ → အသံ / အသံ သန့်စင်မယ် / ဗီဒီယို အသံ |
| VA_PASTE | Paste or type the text | စာကို ကူးထည့်ပါ (သို့) ရိုက်ထည့်ပါ |
| VA_PARTS / VA_SPEAK_PARTS | {n} parts / Speak {n} parts | အပိုင်း {n} ပိုင်း / အပိုင်း {n} ပိုင်း အသံထုတ်မယ် |
| VA_TONE | Tone | လေသံ |
| VA_TONE_CALM / LIVELY / NEWS / STORY | Calm / Lively / News / Storytelling | အေးအေးဆေးဆေး / တက်တက်ကြွကြွ / သတင်းကြေညာသလို / ပုံပြင်ပြောသလို |
| VA_REDO_PART | Speak this part again | ဒီအပိုင်းကို ပြန်ပြောမယ် |
| VA_SAVE_M4A / VA_SAVE_WAV | Save m4a / Save wav | m4a သိမ်းမယ် / wav သိမ်းမယ် |
| VA_CUT_PAUSES | Shorten long pauses | ကြာတဲ့ အသံတိတ်တွေကို တိုမယ် |
| VA_BEFORE / VA_AFTER | Before / After | မပြင်ခင် / ပြင်ပြီး |
| VA_SOUND_ONLY / REPLACE_SOUND / ADD_ON_TOP | Save the sound only / Replace the sound / Add voice or music on top | အသံကိုပဲ သိမ်းမယ် / အသံကို အစားထိုးမယ် / အသံ (သို့) သီချင်း ထပ်ထည့်မယ် |
| VA_PICTURE_KEPT | The picture is not re-encoded. | ရုပ်ပုံကို မထိပါ — အရည်အသွေး မကျပါ။ |
| JOB_* | Transcribe video / Translate subtitles / Text to voice / Video or audio task | ဗီဒီယို စာသားပြောင်း / စာတန်း ဘာသာပြန် / စာ → အသံ / ဗီဒီယို (သို့) အသံ ပြင်ဆင်ခြင်း |
| NEEDS_NEWER_APP | Made with a newer Auto Flow. Update to open it. | Auto Flow ဗားရှင်းအသစ်နဲ့ လုပ်ထားတာပါ။ Update လုပ်ပြီးမှ ဖွင့်ပါ။ |

## 13. Later / not doing
- Later: soft subtitles in mp4 (`mov_text`; check with `FfmpegCheck` first), two-language subtitles, `.ass` export,
  speaker labels, word karaoke, auto placement (Labs); recording in Voice & Audio (`VoiceRecorder` + teleprompter),
  mp3 (needs libmp3lame in `tools/ffmpeg-android`, a DECISIONS entry, licence text and the owner's yes), waveform trim.
- Not doing: OCR of picture subtitles, translating on import, multi-track audio editor, voice clone, any voice provider as a silent default.

## 14. Code facts re-checked at `8d092a0`
All bundle facts used above hold at `8d092a0`. Differences: (1) the stack top is #216 @ `8d092a0` — `836d83f` plus a
CI-only dependency-resolution retry, no app source change. (2) The "33 `Mode` references" count depends on the
search; the 8 files are confirmed and S-1 enumerates every use. (3) Recap's runtime fit is now known:
`RecapFit.rows` defaults `maxVoiceSpeed = 1.5`, `maxVideoSlow = 0.7` and the UI callers pass no override, while
`SmartFitConfig.maxVoiceSpeed = 1.0`. Dubbing never reuses Recap fit; it reads `dubbing.maxVoiceSpeed`.
(4) No Zawgyi detector exists in `:core`/`:ui` (Q-44). (5) `tools/ffmpeg-android/build.sh` enables
`gpl, libx264, libass, zlib, pthreads` — no libmp3lame. `config/tools.json` and `config/recap.json` don't exist.
