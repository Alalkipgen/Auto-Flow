# UI v3 — presentation over the tested engine

Status: specification checkpoint; implementation/automation/device results are tracked separately in PLAN and PHONE-QA.
Baseline: owner-tested `c2cd1e08f3f2ea4717ec05a7b928faaacace9c47`, Draft #175 (unmerged).
References: `../mockups/ui-v3/`; historical `../mockups/phase5.html` remains intact.

## 1. Boundaries and navigation

UI-only: existing project/save/backup, TTS, WorkflowRules, selected takes, IDs/hashes, voice locks,
render graph, player and export engines remain authoritative. No migration, new persisted flag or
parallel editor/player. No account, paid calls, Google Flow automation, Azure or additional Labs work.
Use the existing AutoFlowColors/Shapes, Af* controls, WrapRow and shared Compose at every width.

Home · Projects · Library · Jobs: bottom navigation below 600 dp, rail at/above 600 dp.
At/above 840 dp project content is **list · preview · inspector**. Below 840 dp it is a single
scrollable inspector with its preview and a sticky action bar outside the scroll hierarchy.
Content reserves the bar's measured space; the bar observes IME insets, not a guessed fixed height.

One Back rule for the visible back arrow and Android Back: modal/editor cancellation first,
block detail → block list → previous Recap step → project list → Home → existing leave-app confirmation.
Music Video detail → shot list; classic screen → Classic steps → Shot sheet → project list.
Selecting another project resets project-bound transient selection and never saves the previous draft into it.
Existing destructive/paid-call confirmations and explicit Save/Discard remain in place.

## 2. Shared components and state vocabulary

| Component | Contract |
| --- | --- |
| ProjectTopBar | Back, real name, localized step + Step N of 5, exactly five progress segments, overflow. Save chip only with reliable model state; otherwise omitted. |
| StickyActionBar | One primary, optional secondary and caption; minimum 48 dp targets, wrapping text, keyboard-safe. |
| StatusChip | Sole presentation mapping for status words/colors, also used by Jobs/Export. Never rely on color alone. |
| OptionCard / OptionChips | Radio semantics, title/helper/current recommended default; single-select chips wrap. |
| BlockCard | Real time/optional cached thumbnail, Block N, status, first script line, selected-take play and state actions. |
| PresetTile | Writes documented existing settings; manual changes show Custom. |
| SectionCard | Collapsible existing AfCard; all secondary actions remain scroll-reachable. |
| JobCard | Real project, state, measured progress/failure, only supported actions. |
| PlannedTile | Disabled, explicit Planned; no inert enabled action. |
| PreviewOverlay | Project/layer-ID-bound draft over the actual output rect; reuse existing layer transaction and geometry. |

| Status | Color / source |
| --- | --- |
| Fits | green; existing fit result |
| +N s too long / −N s short | amber; actual duration difference, localized formatting |
| No voice | neutral; no valid selected take |
| Script changed | amber; existing stale calculation, not guessed from UI text |
| Running | cyan; current engine progress (indeterminate when unknown) |
| Paused | amber; engine state/reason |
| Failed | red (`Failed` color token); actual reason separately |
| Done / Accepted | green; completed artifact/approval only |
| Planned | muted; not implemented |

Purple means primary/selection, cyan means external/Flow/progress. No invented ETA, remaining quota,
credits, percent, request count, model/voice or Saved state. Do not display theoretical RPM as measured ETA.
Burmese copy comes only from UiText, uses existing U+200B syllable boundaries, and keeps DESIGN §6 exact.
Buttons/chips wrap, have no fixed text height, support 360 dp and font scales 1.0/1.3/2.0.
Caption raster size is an output-video setting, not scaled by app language or accessibility font scale.

## 3. Screen map and complete existing-control relocation

Each row covers the existing controls named, including their confirmations and error/retry states.
Retain existing testTags on moved controls; tests navigate the real menu/scroll hierarchy.
No omission from a concept authorizes removal. Diagnostics is an accessible destination, not deletion.

| Screen / concept | Existing control(s) | UI v3 location / next action |
| --- | --- | --- |
| Home / 01 | Settings; provider status | Auto Flow header + small truthful provider line |
| Home / 01 | Start Quick Recap, length 1/3/5/10 min, style, Start, Back, Cancel | Quick Recap card; same Length → Style → Confirm flow, Story approval stop |
| Home / 01 | New Recap, New Music Video | Create project rows; Step-by-step opens same Create Recap flow |
| Home / 01 | Recent project open | Continue editing (latest known entry); full recent list remains Projects |
| Home / 01 | Quick Recap / Speak All job progress | Running from the same projected rows as Jobs; View opens Jobs; hide when empty |
| Home / 01 | Dubbing, Voice & Audio, Subtitles tiles | Dubbing Planned; quick tools Planned unless a working standalone route exists |
| Projects / none | Create/open folder, select recent, remove + confirmation | Existing dialogs/actions in SectionCards, no changed deletion semantics |
| Library / none | Brand kits, voices, lexicon, music, fonts, presets; add/edit/remove/Apply | Wrapping category chips + sections; Apply still copies into open project (D-39) |
| All Recap | Step label, Previous/Next, Classic Recap | ProjectTopBar five segments; step navigation in StickyActionBar; Classic in ⋮ |
| Source / 02 | Movie picker, replace + confirmation, metadata | Movie SectionCard; truthful filename/duration/resolution/audio |
| Source / 02 | Find scenes, density fewer/normal/more | Three OptionCards mapping existing values 16/10/6; current default recommended |
| Source / 02 | Scene count, select/all/none, selected ranges, target length | Scan result/time chips and Scene picks section; actual values only |
| Source / 02 | Subtitle tracks, image-track warning, Extract | Subtitles section; preserve unsupported-track explanation |
| Source / 02 | Import/replace/remove .srt; summary | Subtitles and Summary sections |
| Source / 02 | Transcribe selection, confirm real requests/today, start/back, cancel/retry | Transcription section and existing confirm/progress; never whole-movie upload |
| Story / none | Plain/commentary, written/spoken, hook, source credit Save/Cancel | Story options SectionCard; copy/context unchanged |
| Story / none | Write script/manual, replace confirmation, approve | Block list + existing editor; Approve story primary through WorkflowRules |
| Story / none | Per-block narration edit/save, speak-as | Block detail Script section; stale state from existing model |
| Story / none | Clip trim, add range, split, merge, remove | Block detail Clips section; stable block IDs preserved |
| Story / none | Selected take play, Speak, Record, Fit | BlockCard action/detail tools; same history and gates |
| Voice list / 03 | Voice/model lock; Change voice, Keep/Re-speak confirm | Voice lock card; real config/lock, no concept voice name |
| Voice list / 03 | All block rows, voiced count, stale/no-voice | BlockCards + All/Needs attention/Voiced filters with actual counts |
| Voice list / 03 | Speak all/remaining + request confirmation | Sticky Voice N remaining; same runner; existing job appears in Jobs |
| Voice list / 03 | Row Play/Stop, Record/import, Voice & Audio detail | Selected-take play on card; secondary tools in block detail |
| Voice detail / 04 | Block navigation, source preview, output/clip time, Exact Frame | Navigator + preview with accurately labelled time modes |
| Voice detail / 04 | Speak tab, voice details, text, caption timing auto/custom/edit/save/cancel | Script/Voice sections; exact numeric caption windows under More options |
| Voice detail / 04 | Record/Stop, import, teleprompter current/next | Record section; same recorder lifecycle |
| Voice detail / 04 | Clean selected take, stale warning, trim/polish; Clone Labs | Clean section; existing Labs gate, no new clone capability |
| Voice detail / 04 | Take selector/history, selected audio playback | Takes section; duration bar until separate waveform task lands |
| Voice detail / 04 | Re-voice this block | Sticky primary + real scope/requests confirmation; no other block changed |
| Style / 05 | Native preview Play/Pause, seek, mute/proxy explanation | Large preview + scrubber/time; engine details via ⓘ/Diagnostics |
| Style / 05 | Exact Frame render/unavailable/result | Visible preview action; real native-v2 compiler unchanged |
| Style / 05 | Seek-to-ms, active block/clip/layer IDs, native status | Expandable Diagnostics, not normal playback controls |
| Style / 05 | Add/select BLUR/IMAGE/TEXT/BORDER/BACKGROUND | Layers list + add chips; existing supported types only |
| Style / 05 | Layer times, rect x/y/w/h, sigma/feather, opacity, text/font/color, border px, image picker | Selected-layer inspector with % convenience controls and exact values in More options |
| Style / 05 | Earlier/later, Save/Discard, Remove | Layer order actions + same draft transaction; removal changes reference only |
| Style / 05 | Existing layer drag/pinch surface | Separate task overlays handles on real preview; output-frame coordinates, not movie-mask coordinates |
| Style / 05 | Ratio, hide-things presets/custom mask, strength/size/gesture | Layers / More options: clearly labelled pre-Looks movie-mask placement |
| Style / 05 | Background color/blur/image/change/remove; watermark/position | Layers / Background section, base locked by existing compiler rule |
| Style / 05 | Brand Kit save/name/cancel and Apply | Style sections / Library; same project copy semantics |
| Style / 05 | Looks Mirror/Zoom/Fill/Warm, movie volume, BGM change/remove/duck | Wrapping Look / Audio chips and existing controls |
| Captions / 06 | Burn on/off, preset save/library name/cancel, always .srt | Captions section; existing export fields only |
| Captions / 06 | Font, size factor/custom size, position, foreground/custom apply/clear, outline width/color, dark box | Caption inspector; separate preset task documents Clean/Bold/Boxed/Outline mappings |
| Style / 05–06 | Smart Fit per-block choices extend/next-range/speed/slow/freeze | Voice detail Fix timing; still reachable in Style More options |
| Style / 05–06 | Voice loudness normalize and trim-new-takes | Audio section; no changed processing |
| Export / 07 | Accepted/pending/voiced/silent counts, song/font/lyrics warnings | Real preflight/check summary; existing informational detail under More options |
| Export / 07 | Quality Auto/720/1080/1440, Preview 540p, Export | Output chips + sticky Export, optional Preview |
| Export / 07 | Core PreflightCheck report, Fix | Separate task: exact step/block navigation, never a duplicate UI approval gate |
| Export / 07 | Queue/progress/cancel/battery/background/rights note | JobCard + helper; existing capabilities/confirmations retained |
| Export / 07 | Last/current result Open/Share/Save Gallery, loudness/failure note | Result SectionCard; actions only when artifact exists |
| Export / 07 | Exact frames make/remake/select/stale/use-thumbnail | Exact-frame SectionCard, source staleness still shown |
| Export / 07 | Publish title/description/hashtags/chapters Save/Copy/share; thumbnail title/make/remake/share/stale | Collapsible Publish Kit, all copy buttons reachable |
| Jobs / 08 | Render and AI rows, project Open, cancel/resume/retry | Running / Needs you / Finished; existing supported operations only |
| Jobs / 08 | Failed reason; paused busy/recovery; completion | StatusChip + reason + supported Retry/Resume/Open/artifact actions |
| Jobs / 08 | Navigation badge | Needs-you count amber first, else running cyan, else none; no fake waiting rows |
| Shot sheet / 09 | Shot previous/next/chips/all/filter badges | List and navigator; same shot identity |
| Shot sheet / 09 | Storyboard edit/approve/regenerate | Section 1 Storyboard; existing confirmations |
| Shot sheet / 09 | Compile/read IR/copy image/video prompt | Section 2 Prompt, monospace; Copy prompt primary |
| Shot sheet / 09 | Ref A/B select/import/approve/regenerate, Open AI Studio | Section 3 References; real artifact states |
| Shot sheet / 09 | Copy/Open Flow/import video/package/batch/Inbox attach | Section 4 manual Flow handoff and overflow routes; no browser automation |
| Shot sheet / 09 | Version play/select/use/accept/remove | Section 5 Videos; Use this version remains distinct from Accept video |
| Music Video / 09 | Input, Timeline/assemble/export, References, Inbox, Classic steps | Existing project navigation/overflow; no feature loss |
| Settings / 10 | App language | App language section; UI-only helper unchanged |
| Settings / 10 | Fake providers, key save/test/remove, TTS test, actual today's calls | AI & keys section; masked secure storage unchanged |
| Settings / 10 | Config model list/override/bundled settings | Models read-only + existing override route; no hardcoded names |
| Settings / 10 | Pronunciation | Link to Library Lexicon; no parallel editor |
| Settings / 10 | Default ratio/clip length; font add/list | Export defaults and Fonts sections |
| Settings / 10 | ffmpeg path/detect/browse/check; Desktop VLC folder/auto/discovery | Tools section; existing platform boundaries/fallback |
| Settings / 10 | Backup one/many/all/share/restore, progress/cancel | Backup & restore section; existing format/confirmations |
| Settings / 10 | Feature overrides Apply; version long-press Self-test | Labs/Preview features; visible Diagnostics also opens Self-test/build/tool readouts |
| Settings / 10 | Version/about | About section; no account, invented storage or privacy settings |
| Desktop / 11 | Existing copy/approve/shot shortcuts | Preserve; add Space playback and arrows block navigation only outside text fields |

## 4. Screen states

Every screen has a real empty state and a next action, not concept sample data.

| Surface | Empty / loading | Running / paused | Error / done |
| --- | --- | --- | --- |
| Home, Projects | Create/open; current known entries only | Same Jobs projection, hide empty running area | Actual job reason/open project; no invented progress |
| Source | Pick movie; probing/scan progress | Existing scan/transcribe controls and retry/cancel | Metadata/scene times or real failure; Next Story |
| Story | Import subtitles/summary/write | Existing script generation; approval stop | Actual stale/error; Approve then Next Voice |
| Voice list/detail | No voice/generate/record | Real job/item progress, busy → Paused | Failed reason/retry; take play/history; Next Style |
| Style | Movie/voice prerequisites explained | Live player/Exact Frame state independently | Unavailable reason + still path; Save/Discard; Next Export |
| Export | Real prerequisite findings + Fix | Queued/render progress/cancel; no invented ETA | Failure reason/retry, or existing output actions |
| Jobs | No jobs helper | Running and Needs you; supported actions only | Failed/Paused/Done from engine; finished artifact actions |
| Music Video | Go Input/create storyboard | Existing compile/ref/import progress | Preserve approval/stale/accepted versions; Copy prompt/Accept |
| Settings, Library | Real list/empty; add/import | Existing test/backup progress | Actual secure-store/tool/library result; never save project on browse |

## 5. Image conflicts and conservative decisions

- Sample names, timecodes, counts, model/voice names and Saved chips are not data. Use actual values or omit.
- Concept grid-like Create tiles become compact accessible rows; no tool-tile grid or account/avatar.
- Dubbing and unavailable standalone quick tools are disabled Planned, never fake routes.
- Voice approval stops at Story review. No approval/voice-lock/fit engine is moved into UI.
- A theory-based ETA or quota is omitted; measured engine progress is allowed. Models remain config data.
- Layer visibility is **preview-only transient state**, explicitly labelled; no persisted enabled field or export exclusion.
  If that label cannot remain unambiguous, omit the toggle rather than imply export behavior (PLAN Q-15).
- BACKGROUND/BORDER retain existing rect/order semantics; captions remain last; TEXT keeps list order.
- The legacy mask view is pre-Looks; native proxy is approximate; Exact Frame is the export reference.
- Output geometry tolerance stays existing S10 ±2%; no bit-identical text/blur rasterization claim.
- Caption presets only use renderer-supported fields; an unavailable preset is omitted. No fake waveform.
- Desktop multi-track timeline is out of scope. No new quick-tool screens, storage/privacy walls or cache guarantees.
- Android Back and arrow follow §1; concept top Previous/Next controls move to the sticky bar.

## 6. Separate implementation tasks and evidence

Each gets its own scoped PR/tests: (1) lazy cached off-main scene/block thumbnails (fallback: time chips),
(2) on-video output-rect layer editing (fallback: existing numeric/transaction editor),
(3) caption presets (fallback: existing settings), (4) real Preflight Fix navigation (fallback: existing report),
(5) selected-take waveform (fallback: duration bar). Do not call fallback implementation complete.

PR sequence: 0 spec → 1 shared components → 2 shell/Home/Settings/Diagnostics → 3 Voice →
4 Story/Source → 5 Export/Jobs → 6 Style → 7 Music Video → 8 wide layout; insert separate tasks when needed.
Each unit runs targeted tests, commits/pushes to its scoped branch and opens a Draft PR with CI before next work.

For each redesigned screen: 412/840 × font 1.0/1.3/2.0 × EN/MY × bundled/system fallback plus 360 smoke;
real scroll/pointer clicks, visible uncovered primary, Back, byte-identical browse, Discard/Save/reopen,
status/badge, output geometry including Looks. No hidden callback/forced-click workaround.
Real screenshots use QA Landscape/Portrait/Square fixtures: 412 EN/MY × 1.0/2.0 and wide 1440×900,
paired with concept number; inspect individual images, not just a contact sheet. CI uses fake providers only.
Final gates run fresh on the actual final remote SHA (full command in owner request/CI), Android/lint/API,
packaged startup, installed-VLC and Windows installer smoke. Final stable-signed APK must update code 595;
if signing is unavailable, report the block instead of substituting debug signing. No merge/release/tag.

## 7. EN/MY copy contract

Existing UiText/My keys are reused first; DESIGN §6 words remain byte-exact. Every added UiText key has both
EN and Burmese, identical substitution placeholders, syllable-safe wrapping and catalog tests. New-key EN/MY
review tables are generated from the actual diff in visual PRs and included in the final owner handoff; this
spec does not pretend strings not implemented yet are shipped. Technical names and project content are not translated.

Proposed additions (owner-review wording, to be registered when used):

| EN | MY |
| --- | --- |
| Step {n} of 5 | အဆင့် ၅ ဆင့်မှ {n} |
| Needs you | သင်ဆက်လုပ်ရန် |
| Planned | နောက်မှ ထည့်မည် |
| Continue editing | ဆက်ပြင်မယ် |
| More options | ရွေးချယ်စရာများ |
| Preview only | အစမ်းကြည့်ရန်သာ |
| Script changed | စာသား ပြောင်းထားသည် |
| Unsaved changes | မသိမ်းရသေးသော ပြောင်းလဲမှုများ |
| Save failed | သိမ်း၍ မရပါ |
