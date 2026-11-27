# Spec: Dubbing (Phase 7, milestone D)

Status: **in progress** — D-0 CI part, D-1a (data + rules) and D-1b (Video / Translate / Dub script screens) D-2 (fit rule, takes, `DUB_TTS` job, Voice step, Shorten) and D-3 (pre-flight, `DubMixer`, Export step) done — owner milestone D on the phone pending; data layout refined by D-88 (video and `st` pointer in `project.subtitles`). Ported from the owner's Phase 7 plan bundle; code facts
re-checked at `8d092a0` (PR #216). Builds on [quick-tools.md](quick-tools.md) (tool projects, `st` document, jobs,
`config/tools.json`). Decisions D-83, D-85, D-86; open questions Q-40…Q-42. **No Dubbing UI before the CI part of D-0 is reported** (phone parts don't block, D-85).

## 1. v1 rules (D-83)
- Short video, **one voice** for the whole video. Speaker voices, lip sync and vocal separation are not in v1.
- **The picture is fixed.** Recap's picture-side fixes (EXTEND, NEXT_RANGE, SLOW_VIDEO, freeze) are never used, and
  Dubbing does not build on `RecapModel` or reuse `RecapFit.rows` (its defaults are 1.5× / 0.7×).
- Subtitle text (`textMy`) and spoken line (`spokenMy`) are different fields. The spoken line defaults to the subtitle
  text, may be shortened, and any change needs the owner's approval before TTS (WorkflowRules gate in `:core`).
- Original sound: **Lower** (constant `clipsVolume`, default `dubbing.originalVolume` = 15 %, Q-40) keeps music and
  effects and the original voices stay quietly audible; **Mute** (`clipsVolume = 0`) removes everything. The UI says
  exactly this. "Remove only the voices" is never offered. Only audio is sent to Gemini, never video.

## 2. Steps and screens
Existing `ProjectTopBar` five segments: **Video → Translate → Dub script → Voice → Export**. A step opens only when
the previous one is done and says why it is locked. Videos longer than `dubbing.maxSourceMs` (set after D-0, Q-41)
are refused at Video with `DUB_LIMIT`.
- **Video / Translate:** the Subtitles source chooser and editor (same components, Dubbing top bar). Every line needs
  `textMy` before Dub script opens ("Not translated" count with a jump button).
- **Dub script:** per line: number, times, slot; Subtitle text; Spoken field (defaults to the subtitle text);
  "Use the subtitle text". Sticky: **Approve the dub script** (`DUB_APPROVE`). Any spoken-line change after approval
  clears the approval.
- **Voice:** voice lock, tone, Original sound Lower/Mute. Rows: slot, take length, fit chip, ▶, ↻, ⋯ (Edit spoken
  line · Shorten (Gemini)). Chips: Fits · Uses the pause · Faster ×N · +N s too long · No voice yet · Script changed.
  Sticky: Speak n lines (confirm card) → Go to Export. Shorten shows old/new side by side; Use → spoken line changes →
  needs approval again → ↻.
- **Export:** pre-flight blocks on any "too long", missing or old takes, or no Burmese font when burning. Options:
  burn Burmese subtitles (`textMy`; Size, Lines on screen 1 / 2 and Line length Short / Long, by default from the frame —
  D-91), save the voice track as wav. Output mp4 + `.srt`; Done card Open · Share · Save
  to Gallery. Video stream copied when nothing is burned.

## 3. Data
`Project.dubbing: DubbingTool?` (`@EncodeDefault(NEVER)`, D-78): `video: SourceVideo`, `subtitles` (`st` id),
`scriptApproved{hash, at}`, `lock: VoiceLock?`, `tone`, `takes{cueId → au}`, `original{mode: LOWER|MUTE, volume}`,
`burnSubtitles`. `spokenMy` lives on the `st` cues.

## 4. Fit per line (picture fixed)
```
d      = take duration after trimming leading/trailing silence
lineMs = end − start
slotMs = (next.start − minGapMs) − start          // last line: videoEnd − start
if d ≤ lineMs                      → FITS        speed 1.0
else if d ≤ slotMs                 → USES_PAUSE  speed 1.0
else if d / slotMs ≤ maxVoiceSpeed → FASTER      speed = ceil2(d / slotMs)
else                               → TOO_LONG    by (d / maxVoiceSpeed − slotMs)
```
`maxVoiceSpeed` = `dubbing.maxVoiceSpeed` in `config/tools.json`: 1.0 until the owner decides S8 (Q-42), so FASTER
cannot appear before that. A cue never starts before its line, never ends after `next.start − minGapMs`, never cuts
words, and lines that did not change are never re-spoken.

## 5. Jobs and AI
- `AiJobKind.DUB_TTS`, item = cue id, take `au` keyed by hash(`spokenMy` after Lexicon + VoiceLock + tone); resume
  skips current takes; F3 `timings` recorded; processor beside `RecapJobProcessor` (no second job system).
- **Shorten:** `TextProvider.json` input `{lines:[{id, spokenMy, maxChars}]}` with
  `maxChars = floor(chars × slotMs / takeMs × 0.95)`, schema `{lines:[{id, spokenMy}]}`; shown as a diff; the owner
  approves per line. Fake TTS duration = chars × `msPerChar`.

## 6. Mix and export
`DubMixer` (cue builder lifted from `RenderCompiler`: `adelay`, `atempo`, `amix`) builds `VoiceTrack` cues at line
starts with fit speed factors; original at `clipsVolume`. Stem → mux with `-c:v copy`, or into the burn command
(quick-tools.md §9). If D-0 shows input limits, pre-mix groups of `dubbing.stemGroup` cues into one cue.

## 7. D-0 gate (owner's phone, before D-1)
(a) S8 listening at 1.00–1.25× → chosen limit into `config/tools.json`. (b) Three 30–60 s clips through a debug-only
path: per-line TTS time, request count, 429/retries, regenerating one line, naturalness — from `AiJob.timings`.
(c) Renders with 50/150/300 cues on phone and CI. Report in `docs/perf/`; the owner sets the v1 maximum length and
defaults. (c) and the fake-provider counts run on CI before D-1; (a) and (b) on the phone are PHONE_PENDING and
never block (D-85) — Dubbing uses the defaults (1.0×, 10 min) until the owner reports. Edge TTS (D-86) may be used
for dub lines, which avoids Gemini free-tier request limits.

## 8. PRs, tests and acceptance
| PR | Scope | Must include |
| --- | --- | --- |
| D-0 | spikes above | `docs/perf/` report |
| D-1 | `Mode.DUBBING`, Video/Translate reuse, Dub script + approval, flag `dubbing` | `:core` gate tests; editing a spoken line clears approval |
| D-2 | per-line TTS job, fit chips, filters, Shorten with diff + approval | every fit branch and boundary; one-line redo leaves others untouched; kill/resume |
| D-3 | `DubMixer`, Lower/Mute, optional burned `textMy`, pre-flight, mp4 + `.srt` (+ wav stem) | real ffmpeg: every cue within ±40 ms of its line; no overlap; Lower/Mute levels; video copied when not burning |

Owner milestone **D**: three clips plus one at the v1 limit — every line on time, none overlapping, speed never above
the S8 limit, Lower and Mute both correct, one-line redo, resume after kill.

## 9. Proposed UiText (owner checks the Burmese)
Reuse `V3_FITS`, `V3_TOO_LONG`, `V3_NO_VOICE`, `V3_SCRIPT_CHANGED`; Approve stays "အတည်ပြုမယ်" (DESIGN §6).

| Key | English | မြန်မာ |
| --- | --- | --- |
| DUB_STEP_* | Video / Translate / Dub script / Voice / Export | ဗီဒီယို / ဘာသာပြန် / ပြောမည့်စာ / အသံ / ထုတ်မယ် |
| DUB_SPOKEN / DUB_USE_SUBTITLE | Spoken line / Use the subtitle text | ပြောမည့်စာ / စာတန်းစာကို ပြန်သုံးမယ် |
| DUB_APPROVE | Approve the dub script | ပြောမည့်စာကို အတည်ပြုမယ် |
| DUB_USES_PAUSE / DUB_FASTER | Uses the pause / Faster ×{x} | အနားယူချိန်ကို သုံးထားတယ် / ×{x} မြန်ထားတယ် |
| DUB_SHORTEN | Shorten (Gemini) | တိုအောင်ပြင်မယ် (Gemini) |
| DUB_ORIGINAL | Original sound | မူရင်းအသံ |
| DUB_LOWER | Lower — music and effects stay, original voices still heard quietly | လျှော့မယ် — တေးဂီတ၊ အသံထူးတွေ ကျန်မယ်၊ မူရင်းစကားသံကို တိုးတိုး ကြားရမယ် |
| DUB_MUTE | Mute — music and effects go too | ပိတ်မယ် — တေးဂီတ၊ အသံထူးတွေပါ ပျောက်မယ် |
| DUB_BURN | Add Burmese subtitles | မြန်မာ စာတန်း ထည့်မယ် |
| DUB_LIMIT | Dubbing works with videos up to {minutes} min for now. | Dubbing က လောလောဆယ် {minutes} မိနစ်အထိ ဗီဒီယိုတွေနဲ့ပဲ ရပါတယ်။ |
| JOB_DUB_TTS | Dub voices | Dubbing အသံ |

## 10. Later / not doing
Later (Labs or v1.1): duck the original only while the dub speaks (sidechain on the original), per-speaker voices
(needs speaker labels), vocal separation (Labs spike), voice clone (paid tier). Not doing: lip sync.
