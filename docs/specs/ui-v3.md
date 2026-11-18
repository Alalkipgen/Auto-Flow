# UI v3 — presentation over the tested engine

Status: shared-control foundation implemented; screen migrations and separate tasks remain in progress. Implementation/automation/device results are tracked separately in PLAN and PHONE-QA.
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
| Story / none | Per-block narration edit/save; speak-as exists in schema, not the old UI (Q-20) | Shared native block Script inspector; stale state from existing model |
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
| Voice detail / 04 | Selected audio playback; history exists in data, no old chooser/Compare UI (Q-22) | Takes section; duration bar until separate waveform task lands |
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
| Desktop / 11 | Existing input/scrubber keys; historical copy/approve intent | Preserve input/scrubber behavior. Space playback and arrows navigate only on project-surface focus; Ctrl+C/A project actions await Q-31 (no handlers found at PR-7). |

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

### Scene/block thumbnails — separate task 1 (PR-9 / Q-32, brief §7.1)

- Source's existing scene rows and Story/Voice's existing native BlockCards use one shared thumbnail composable. Time labels, pick/filter/page controls, block actions, badges, shortcuts, Back and testTags remain authoritative and reachable. No Home media decode or fake concept frame.
- A scene samples the midpoint of its actual source range; a multi-range block samples the midpoint of its first range. It never samples a gap, changes selection, renders output/Looks/captions or starts a native player. The unchanged `FrameGrab.thumb` creates a 240 px JPEG; the decorative up-to-128×72 dp crop has no separate focus/click target.
- Composition alone does not start work: the anchor must intersect the clipped viewport. A stable 72 dp slot is reserved from first measurement, including loading/offscreen/unavailable states, so late images cannot move scrolled rows. Leaving it cancels queued/in-flight work and releases the decoded bitmap without collapsing the slot. Revision-keyed composition and a post-work identity check reject a late old-project result.
- Extraction, disk IO and image decode run on `Dispatchers.Default`; one process-local mutex serializes requests. The cache lives under `AppDirs.root/cache/scene-thumbnails-v1`, never inside the project, and is optional for hosts without app-cache wiring.
- D-33 deliberately has no movie hash. Cache identity includes project folder/ID/saved revision, full movie metadata and both source endpoints; even same-size replacements require a new revision. Any save conservatively invalidates cached thumbnails. Each of 64 replaceable app-cache slots checks the full key; key+JPEG is one atomic record, not two sidecars. Unique temporary paths isolate overlapping processes; completed requests recycle their own work files, while crash orphans are disposable cache. Bad/oversized/unreadable images or missing ffmpeg keep time-only rows, not a misleading earlier image.
- No project schema, immutable artifacts, save/import/TTS/WorkflowRules/render/export engine, dependency, model/voice data or approval behavior changes. Legacy Classic scene-thumbnail controls/API remain unchanged. No new UI strings. Thumbnails are not take waveforms or on-video editing; the other four §7 tasks remain unstarted by this unit.
- Verification must cover real QA-fixture ffmpeg frames, the full EN/MY/font/width matrix, actual scroll/pointer/navigation and ≥48 dp existing targets, all-project-file byte identity, cache reuse/collisions/corruption, first-range sampling, off-main work, viewport laziness, cancellation and late project-switch rejection. WIP evidence: latest test-compile-5 passed, focused targeted-4 36/36 and selected existing regression-1 115/115, both with 0 failures/errors/skips. Earlier compile/test failures and their fixes remain in evidence. Continuation review reran the unchanged thumbnail code: compile and focused 36/36 passed with 0 failures/errors/skips; the initial missing-javac host prerequisite failure (0 tests) is retained. These selected results are not a full check; exact-remote full check, new Android validation and thumbnail-commit CI are reported separately in this unit's Draft PR/handoff. Historical PR-8 results are baseline only. PHONE_PENDING / OWNER_PC_PENDING / owner MY wording and Q-31 stay open.

For each redesigned screen: 412/840 × font 1.0/1.3/2.0 × EN/MY × bundled/system fallback plus 360 smoke;
real scroll/pointer clicks, visible uncovered primary, Back, byte-identical browse, Discard/Save/reopen,
status/badge, output geometry including Looks. No hidden callback/forced-click workaround.
Real screenshots use QA Landscape/Portrait/Square fixtures: 412 EN/MY × 1.0/2.0 and wide 1440×900,
paired with concept number; inspect individual images, not just a contact sheet. CI uses fake providers only.
Final gates run fresh on the actual final remote SHA (full command in owner request/CI), Android/lint/API,
packaged startup, installed-VLC and Windows installer smoke. Final stable-signed APK must update code 595;
if signing is unavailable, report the block instead of substituting debug signing. No merge/release/tag.

### On-video layers — separate task 2 (brief §6.05 / §7.2; commit-time validation checkpoint)

- Opt in only on native Style. Draw handles only over a successfully painted real native output frame, never over a black placement surface, preparing/unavailable message or saved Exact Frame. Selecting/editing pauses and seeks the existing player to the layer start; no decoder or render engine is introduced. Missing VLC/assets retains the honest reason and existing manual/Exact Frame fallback. While playing, handles are inactive; pause before dragging.
- Reuse the captured folder/project/revision/layer-ID transaction and one transient text form for numeric controls, percent sliders and output handles. Invalid typed values disable visual edits/Save rather than silently using old numbers. Save/Discard, staged order, image import and reference-only Remove retain their contracts; late/stale token callbacks cannot edit another draft/project.
- Output-frame coordinates include bars/background and do NOT inverse-map through Mirror/Zoom/Fill. The painter already applies those Looks. BLUR/TEXT resize their normalized rect. IMAGE handles follow its rendered aspect-preserving footprint: width controls image size, unused stored height is not falsely stretched. BACKGROUND remains behind the picture and BORDER full-frame; neither gets move/resize handles. Captions remain last. No preview-only visibility/export toggle.
- Draft rendering is transient and explicitly unsaved; Exact Frame is disabled until Save/Discard and remains the saved export compiler truth. Layer-only Save retains the paused output time only for the same project/movie/timeline; other identities reset. Manual controls, stable testTags and all existing tests/assertions remain intact. No core/media/schema/models/WorkflowRules/import/handoff/dependency changes.
- The first exact-tip installed-VLC CI run reported 7 total / 6 passed / 1 failure / 0 skips: the new cold-paused-start case timed out because the unchanged decoder does not decode until first Play. UI-only repair primes that decoder, keeps the sole output clock frozen, hides priming pixels, then pauses/re-seeks before enabling real-frame handles. The live gate also checks the independent green source-time patch after edit and Save; its new real pointer/scroll targets use their node centres. Initial exact `b52b5d3` fresh-clone unfiltered full passed 1469 total / 1463 passed / 0 failures / 0 errors / 6 explicit VLC prerequisite skips; lint 0 errors / 6 existing warnings and API-26 23-reference scan passed (not runtime proof). All b52 CI outcomes are retained: five jobs succeeded, installed-VLC failed, so that commit is not all-green. The repaired focused-5 run passed 40 /39 /0 failures /0 errors /1 VLC skip. Verified 2a7 fresh full was 1469 /1463 /0 failures /0 errors /6 explicit prerequisite skips; its CI run `44273229930` nevertheless FAILED installed-VLC 7 /6 /1 failure /0 skips, with the other five jobs successful. The GitHub-digest-verified original artifact identifies the independent green-source3500 assertion (line83): its real screenshot shows a red initial-source frame at output1500. A new actual-VLC cold-start regression reproduces this on trial7 without production changes. The continuation UI handshake hides warming frames, waits for real source progress, confirms native PAUSED, then issues a fresh paused seek. A matching paused-source acknowledgement AND a newly delivered frame are both required, in either order: a paused decoder may deliver its only bitmap before the next polled snapshot. PLAYING plus an old bitmap is not sufficient. Earlier continuation candidates failed actual-VLC cold runs (red stale frame, blue overshoot, then a frame-before-snapshot readiness stall); those failures are retained. The current candidate passed 47 focused cases /47 passed /0 failures /0 errors /0 skips, including twelve cold starts and all original live scenarios; filtered local success is not exact-head full/CI proof. New draft identities reset it, Save/Discard cannot expose priming pixels, and manual Play/Close remain authoritative. Pure UI state tests and twelve real cold starts supplement—not replace—the original live scenarios. Genuine cursor move/click/exit fixes a separately reproduced mouse-to-touch injection error; every old test/window/timeout/pixel/persistence assertion remains. Measured dd2 repair head `dd2d0495ba6faa2226c04378d31f17d273eaf14a` then passed the exact-head fresh-clone unfiltered full1476/1476/0failures/0errors/0skips (238 suites,12/12 actual-VLC cold trials). The original canonical attempt failed by host OOM after592 completed cases; successful retry used same-head compiled/lint outputs,per-test-task --rerun,640m Gradle heap,one worker,plus explicit desktop-app3-case retest,without filters/exclusions or assertion/window changes. That failed attempt is retained,not relabelled as canonical success. Local lint0errors/6 existing warnings. Exact-dd2 CI44291821511 was five successes plus a desktop-playback apt prerequisite failure: three failed/timed-out install attempts,then NO decoder tests/XML/artifact; ffmpeg setup was not reached. New owner raw logs119894947904 confirm this,without a deeper apt diagnostic/exit code; exact apt/mirror/package cause remains unknown. Successful Linux1476/1469/0/7VLCskips and Windows966/960/0/6VLCskips are not live acceptance. The status-doc child changes no executable source/tests/workflow and requires its own exact-head fresh full/all-six CI verification. Final measured outcomes remain in Draft PR194/handoff; no all-green or owner acceptance is invented. Subsequent exact `7a5476836b4002ca0b20b0018c38ae8c3b97b9fb` CI run `44303068079` genuinely PASSED installed-VLC playback:8 total /8 passed /0 failures /0 errors /0 skips,including12/12 cold trials; full XML and real frames were verified against official artifact SHA256 `c5e95e38608622d992d29afd9a84302f984c587f29232015d37456bfe1a81938`. Windows,Android,smoke,installer also succeeded,but general check FAILED BEFORE TESTS:static ffmpeg download/checksum failed,apt fallback failed/timed out,and the setup action hit its10-minute limit. Its artifact has empty test groups/no JUnit XML; no general-check executed-test count or pass is claimed. Deeper download/checksum/apt cause is not present in available evidence; helpers remain unchanged. Do not combine dd2 general-check success with7a5 playback success to call any head all-green. The next normal documentation child still needs its own exact-head fresh full and all-six successful CI jobs; current outcomes are measured in Draft PR194/handoff.
- New validation covers real pointer/scroll, cancellation/identity and post-drag actual Compose-vs-real-ffmpeg at 16:9,9:16,1:1, letterbox and actual Mirror/Zoom/Fill configurations; the ±2% edge tolerance is unchanged. Component/static parity is not live-decoder synchronization evidence: the separate installed-VLC runtime case uses the actual Style container. At initial-head commit preparation, focused = 40 total / 39 passed / 0 failures/errors / 1 explicit installed-VLC prerequisite skip; selected regression plus focused = 101 / 100 / 0 / 1 (filtered, not full). Six newly dragged/saved actual Compose-vs-Exact Frame/completed 540p MP4 cases have max edge delta 0.003704, below the unchanged 0.02 limit. The original mobile/wide gate is retained unchanged in coverage; final exact-SHA continuation evidence is tracked in the PR/handoff, never inferred from thumbnail, skipped-VLC or historical S10 passes. Q-31/PHONE_PENDING/OWNER_PC_PENDING/owner MY wording remain pending.

New on-video copy (owner wording review remains pending):

| Key | EN | MY (owner review pending) |
| --- | --- | --- |
| `V3_LAYER_MOVE` | Move layer | Layer ကို ရွှေ့မယ် |
| `V3_LAYER_RESIZE` | Resize layer | Layer အရွယ်အစား ပြောင်းမယ် |
| `V3_LAYER_DRAFT_PREVIEW` | Unsaved preview. Save or discard the layer before Exact frame / export. | မသိမ်းရသေးသော preview ပါ။ အတိအကျ ပုံ / ထုတ်ယူ မလုပ်မီ Layer ကို သိမ်းပါ သို့မဟုတ် ပယ်ပါ။ |
| `V3_IMAGE_ASPECT` | Image aspect is kept; width sets its rendered size. | ပုံအချိုးကို ထိန်းထားသည်။ အကျယ်က ပြမည့် အရွယ်အစားကို သတ်မှတ်သည်။ |

## 7. EN/MY copy contract

Existing UiText/My keys are reused first; DESIGN §6 words remain byte-exact. Every added UiText key has both
EN and Burmese, identical substitution placeholders, syllable-safe wrapping and catalog tests. New-key EN/MY
review tables are generated from the actual diff in visual PRs and included in the final owner handoff; this
spec does not pretend strings not implemented yet are shipped. Technical names and project content are not translated.

Registered shared-component additions (owner review; registration does not claim every screen is redesigned):

| Key | EN | MY |
| --- | --- | --- |
| `V3_PLAIN_HINT` | Straight narration | ဇာတ်ကြောင်းကို ရိုးရိုး ပြောပြမယ် |
| `V3_EXPANDED` | Expanded | ဖွင့်ထားသည် |
| `V3_COLLAPSED` | Collapsed | ခေါက်ထားသည် |
| `V3_STEP` | {step} · Step {n} of 5 | {step} · အဆင့် ၅ ဆင့်မှ {n} |
| `V3_MORE_OPTIONS` | More options | ရွေးချယ်စရာများ |
| `V3_RECOMMENDED` | Recommended | အကြံပြုထားသည် |
| `V3_PLANNED` | Planned | နောက်မှ ထည့်မည် |
| `V3_SCRIPT_CHANGED` | Script changed | စာသား ပြောင်းထားသည် |
| `V3_FITS` | Fits | ကိုက်တယ် |
| `V3_TOO_LONG` | +{seconds} s too long | +{seconds} စက္ကန့် ပိုရှည်သည် |
| `V3_TOO_SHORT` | −{seconds} s short | −{seconds} စက္ကန့် လိုသည် |
| `V3_NO_VOICE` | No voice | အသံ မရှိသေး |
| `V3_BLOCK` | Block {n} | အပိုင်း {n} |
| `V3_SAVED` | Saved | သိမ်းပြီး |
| `V3_UNSAVED` | Unsaved changes | မသိမ်းရသေးသော ပြောင်းလဲမှုများ |
| `V3_SAVE_FAILED` | Save failed | သိမ်း၍ မရပါ |

### Home implementation checkpoint (PR-2a)

- Header/Settings, existing Length → Style → Confirm wizard, Step-by-step mode-specific create form, most-recent Continue, compact Create rows and disabled Planned tools. Home performs no media probe/decode.
- Continue shows the retained step only for the same currently open project; otherwise mode only and Source/Input on reopen (Q-16). Recent projects remain in Projects. No invented progress or Saved state.
- Running uses the identical `jobRows` projection as Jobs, including real queued/running state and pending item count; no theoretical RPM-based ETA. Empty Running is omitted.
- Home create routes use the existing Dashboard form/rights confirmation; transient create state is cancelled before route Back. Tabs retain the actual open Recap step. Wizard Back steps through its transient state before app Back.
- Visual evidence: real QA Landscape fixture in the actual `StudioV2Shell`; concept 01 pairs, 412 EN/MY × font1/2, 360 MY/font2, 840 matrix and 1440×900 wide. This is Home evidence, not completion of the project-wide layout.

Home additions for owner copy review:

| Key | EN | MY |
| --- | --- | --- |
| `V3_APP_NAME` | Auto Flow | Auto Flow |
| `V3_CONTINUE` | Continue editing | ဆက်တည်းဖြတ်မယ် |
| `V3_STEP_BY_STEP` | Step-by-step → | တစ်ဆင့်ချင်း လုပ်မယ် → |
| `V3_APPROVAL_BEFORE_VOICE` | Approval before voice · stops at Story review | အသံမထုတ်မီ ဇာတ်လမ်းကို စစ်ပြီး အတည်ပြုရမယ် |
| `V3_DUBBING` | Dubbing | အသံ အစားထိုးခြင်း |
| `V3_QUICK_TOOLS` | Quick tools | အမြန် ကိရိယာများ |
| `V3_VOICE_AUDIO` | Voice & Audio | အသံနှင့် Audio |
| `V3_SUBTITLES` | Subtitles | စာတန်းထိုး |
| `V3_TOOLS_IN_PROJECT` | Standalone tool planned; existing controls are inside projects. | သီးသန့် ကိရိယာကို နောက်မှ ထည့်မယ်။ လက်ရှိ ကိရိယာများကို ပရောဂျက်ထဲမှာ သုံးနိုင်တယ်။ |
| `V3_VIEW_JOBS` | View jobs → | အလုပ်များ ကြည့်မယ် → |

### Settings implementation checkpoint (PR-2b)

- Concept 10 maps to shared, collapsible AI & keys / Models / Export defaults / Fonts / Tools / Backup & restore (where available) / Preview features / Diagnostics / About. Language remains expanded. All existing Apply, Browse, Detect, masked Test, backup and version long-press actions are retained.
- Diagnostics is visible without a long press: actual build identity, actual ffmpeg result or explicit not-yet-checked/unavailable state, configured VLC or missing-runtime explanation, and the existing Android Self-test entry. Desktop has real generated package/source identity but no device Self-test; its disabled action explains this.
- Pronunciation opens the existing Library Lexicon and scrolls it into view. The current Library section is a list, not an editor; project pronunciation editing stays in its existing place (Q-18). No duplicate editor or implicit Apply.
- The actual shell back control is arrow-only with an accessible localized name; provider state wraps in the remaining space at large fonts. Browsing Settings/Library does not write project files.

Settings additions for owner copy review:

| Key | EN | MY |
| --- | --- | --- |
| `V3_AI_KEYS` | AI & keys | AI နှင့် Key များ |
| `V3_EXPORT_DEFAULTS` | Export defaults | ပုံမှန် ထုတ်ယူမှု ဆက်တင် |
| `V3_PRONUNCIATION` | Pronunciation | အသံထွက် |
| `V3_OPEN_LEXICON` | Open Library → Lexicon | စာကြည့်တိုက် → အသံထွက် အဘိဓာန် ဖွင့်မယ် |
| `V3_DIAGNOSTICS` | Diagnostics | စနစ် စစ်ဆေးမှု |
| `V3_BUILD_UNAVAILABLE` | Build identity is not available on this platform. | ဒီ platform မှာ build အချက်အလက် မရရှိပါ။ |
| `V3_TOOL_DETECTED` | ffmpeg detected; version check has not run. | ffmpeg တွေ့ရှိသည်။ Version မစစ်ရသေးပါ။ |
| `V3_SELFTEST_ANDROID` | The on-device Self-test is available in Android builds. | ဖုန်းပေါ် ကိုယ်တိုင် စမ်းသပ်မှုကို Android build မှာ သုံးနိုင်တယ်။ |


### Voice list implementation checkpoint (PR-3a)

- Concept 03 now uses native block IDs, every clip range, chosen current takes and the live pronunciation snapshot. All / Needs attention / Voiced counts come from those rows, not take-history order or scene indices. A valid short take is still voiced; speak-remaining does not replace it.
- The timing chip compares `(selected take duration + existing 400 ms tail) / effective Fit voice speed` with the sum of native clip durations. Signed differences beyond ±400 ms are attention hints; no workflow gate or render graph changes (Q-19). Stale and different/unknown-voice messages remain visible. Fix timing opens the existing core-backed Smart Fit choices.
- This route uses the five-segment ProjectTopBar and one counted speak-remaining primary plus Next: Style in the sticky bar. Classic Recap is in the actual overflow. Back from a block returns to the list before the previous step; platform Back uses the same rule.
- Change voice retains the existing lock/stale/keep/regenerate flow. In compact UI the policy is inside that dialog; the new confirmation shows actual request counts, not the old theoretical RPM ETA. Batch generation uses the existing confirmation and job pipeline, preserves valid selected takes and can be cancelled without writing.
- Block controls, recorded voice, selected-take play, Smart Fit, muted native timeline preview and Exact Frame remain reachable in the existing block editor. This is a deliberate interim fallback: concept 04 navigator, script/takes inspector and per-block re-voice confirmation remain PR-3b, not completed by this list PR. Thumbnails and waveforms remain separate tasks; time ranges and durations are the honest fallback.
- Busy Smart Fit chips are semantically disabled, not enabled no-op controls. Block selection and its Fit-open intent are one transient, project-bound value; the pointer matrix caught the previous separate-state reset when opening Fix timing.
- Browsing filters, opening/closing a block, navigating and cancelling confirmation must leave the QA fixture bytes unchanged. The separate fake-TTS persistence test confirms only the missing native block is generated and survives reopen, with other selected take/story/layers/approval intact. No live provider call.

Voice list additions for owner copy review:

| Key | EN | MY |
| --- | --- | --- |
| `V3_ALL_COUNT` | All {n} | အားလုံး {n} |
| `V3_ATTENTION_COUNT` | Needs attention {n} | စစ်ဆေးရန် {n} |
| `V3_VOICED_COUNT` | Voiced {n} | အသံပါ {n} |
| `V3_FIX_TIMING` | Fix timing | အချိန်ကိုက် ပြင်မယ် |
| `V3_REVOICE` | Re-voice | အသံ ပြန်ထုတ်မယ် |
| `V3_GENERATE_VOICE` | Generate voice | အသံ ထုတ်မယ် |
| `V3_VOICE_REMAINING` | Voice {n} remaining | ကျန် {n} ပိုင်း အသံထုတ်မယ် |
| `V3_VOICE_COUNT` | {n} voice requests | အသံထုတ် request {n} ခု |
| `V3_VOICE_REQUESTS` | {n} voice requests · existing valid takes stay unchanged | အသံထုတ် request {n} ခု · မှန်ကန်သော take အဟောင်းများ မပြောင်းပါ |
| `V3_REVOICE_COUNT` | Re-voice {n} blocks | အပိုင်း {n} ပိုင်း အသံပြန်ထုတ်မယ် |


### Native block Script checkpoint (PR-3b prerequisite, Q-20)

- Voice detail now shows the native block text and the actual `SpokenText.of(block, liveLexicon)` line. Edit opens a scrollable shared draft for existing `textMy`/`speakMy`, with visible Save/Cancel outside the scroll content. An empty speak-as field removes the override; it never changes app language or silently translates content.
- Drafts capture project folder, ID, revision and stable block ID. Save uses the unchanged immutable ArtifactStore and atomic/conflict-aware ProjectSession; it clears only the existing Story approval as the old script save does. Takes/chosen takes, other blocks, clip ranges and layers are preserved. Cancel and unchanged Save write nothing; a project/revision change is rejected. Clone remains disabled and explicitly Planned rather than an enabled no-op.
- The original control inventory overstated the baseline: no native speak-as or clip-range editor was wired. This checkpoint exposes text/speak-as; clip trim/add/split/merge remain an explicit Story follow-up. Concept 04 preview-first navigator, selected-take/history inspector, scrubber and per-block confirmation are still open, not completed by this Script prerequisite. No waveform/Compare is invented.

| Key | EN | MY |
| --- | --- | --- |
| `V3_SCRIPT` | Script | စာသား |
| `V3_CAPTION_TEXT` | Caption text | စာတန်းထိုး စာသား |
| `V3_SPEAK_AS` | Speak as: {text} | အသံထွက်: {text} |
| `V3_SPOKEN_OVERRIDE` | Speak-as override (optional) | သီးသန့် အသံထွက် (မထည့်လည်း ရသည်) |
| `V3_SPOKEN_HELP` | Blank uses caption text + pronunciation. | အလွတ်ဆို စာတန်းထိုးနှင့် အသံထွက် အဘိဓာန်ကို သုံးမယ်။ |
| `V3_SPOKEN_HINT` | Leave blank to speak the caption text with the pronunciation dictionary. Saving clears Story approval; existing takes stay in history. | အလွတ်ထားလျှင် စာတန်းထိုး စာသားကို အသံထွက် အဘိဓာန်ဖြင့် ဖတ်မယ်။ သိမ်းလျှင် ဇာတ်လမ်း အတည်ပြုချက် ပြန်လိုမယ်။ Take အဟောင်းများ မပျက်ပါ။ |
| `V3_BLOCK_REVISION_CHANGED` | Project revision changed; cancel and reopen the block | Project ဗားရှင်း ပြောင်းသွားသည် — ပယ်ဖျက်ပြီး အပိုင်းကို ပြန်ဖွင့်ပါ |

### Preview controls checkpoint (PR-3b, Q-21)

- Shared foundation `AfScrubber` uses a real 48 dp graphic hit area, pointer tap/horizontal drag, focused arrow/Home/End keys and accessible range semantics. It controls the existing `RecapPlaybackClock`; no dependency or player/timeline engine is added. Font scale changes UI labels, not output-caption rasterization.
- Voice detail starts at the selected stable block ID's first output interval, not its source timestamp. Playback/scrubbing continues across all native clips, including the block's later clips and Fit mapping, and is labelled **All clips · output time · muted**. A missing block interval is unavailable, not a silent first-clip fallback. Opening or seeking does not write project files.
- Normal controls: output time label, scrubber, Play/Pause, ±5 s and Close player. Exact Frame remains below and uses the unchanged native export compiler at the same output cursor. Before playback, an honest Play/Exact-frame hint replaces any fabricated still; the concept-04 preview-first inspector is still a follow-up.
- Exact-ms input, active IDs, raw time/caption readouts and the detailed rasterization limitation move under collapsed **Diagnostics**. Their existing test hooks remain. Actual video has the active caption as accessible state even when Diagnostics is collapsed. Classic retains its existing readouts.
- No-VLC error/fallback, native-frame composition, source/revision binding and player disposal remain in the existing platform path. A still capture is not live-runtime proof. Navigator, selected-take/history presentation, per-block confirmation and the separate thumbnail/waveform tasks are not completed by this controls checkpoint.

| Key | EN | MY |
| --- | --- | --- |
| `V3_TIMELINE_SCOPE` | All clips · output time · muted | Clip အားလုံး · output အချိန် · အသံပိတ် |
| `V3_PREVIEW_IDLE` | Play to preview, or use Exact frame. | ဖွင့်၍ ကြည့်ပါ၊ သို့မဟုတ် အတိအကျ ပုံကို သုံးပါ။ |
| `V3_OUTPUT_POSITION` | Output {position} / {duration} | Output {position} / {duration} |
| `V3_SCRUB_OUTPUT` | Seek output timeline | Output အချိန်လိုင်း ရွှေ့မယ် |
| `V3_LIVE_PREVIEW` | Muted output preview | အသံပိတ် output အကြိုကြည့်ပုံ |
| `V3_BLOCK_PREVIEW_UNAVAILABLE` | This block has no playable output interval. | ဒီအပိုင်းတွင် ဖွင့်ကြည့်နိုင်သော output အချိန် မရှိပါ။ |

### Voice inspector checkpoint (PR-3b, Q-22)

- Shared native stable-ID navigator, source ranges (all clips), compiler-derived output interval, preview-first layout and Exact Frame. Preview remains the existing all-clips muted player, not an invented clip loop. Voice lock, Script/speak-as, Smart Fit and recorded/import/clean/caption-timing tools remain reachable.
- Read existing immutable take history off the UI thread; selected take first, measured durations and a duration bar, other takes collapsed. Missing files are explicit. No fake waveform/Compare, chooser write, new schema or engine. Selected-take play moves to Takes; Record keeps its teleprompter.
- One sticky block-scoped generation/re-voice action opens a real confirmation. Capture the project revision and stable block ID; reject old confirmations after project/edit/navigation changes. Resolve the ID at confirmation before calling the unchanged synthesis action; core approval rules remain authoritative. Existing exports are not silently updated.
- Each block resets transient controls/scroll. Back still returns detail → list → previous step. Wide three-pane composition remains PR-8.

New EN/MY strings for owner review:

| Key | English | Burmese |
| --- | --- | --- |
| V3_PREVIOUS_BLOCK | Previous block | ယခင် အပိုင်း |
| V3_NEXT_BLOCK | Next block | နောက် အပိုင်း |
| V3_BLOCK_OF | Block {n} of {total} | စုစုပေါင်း {total} ပိုင်းမှ အပိုင်း {n} |
| V3_SOURCE_RANGES | Source {ranges} | ရင်းမြစ် {ranges} |
| V3_OUTPUT_RANGE | Output {range} | Output {range} |
| V3_TAKES_COUNT | Takes · {n} | အသံမှတ်တမ်း · {n} |
| V3_LOADING_TAKES | Loading takes… | အသံမှတ်တမ်းများ ဖတ်နေသည်… |
| V3_TAKE_DURATION | Take {n} · {duration} | အသံမှတ်တမ်း {n} · {duration} |
| V3_SELECTED | Selected | ရွေးထားသည် |
| V3_TAKE_HISTORY | Other takes · {n} | အခြား အသံမှတ်တမ်း · {n} |
| V3_BLOCK_TOOLS | Block voice tools | အပိုင်းအတွက် အသံ ကိရိယာများ |
| V3_REVOICE_BLOCK | Re-voice this block | ဒီအပိုင်း အသံပြန်ထုတ်မယ် |
| V3_ONLY_BLOCK | Only block {n} will change | အပိုင်း {n} ကိုသာ ပြောင်းမယ် |
| V3_ONE_VOICE_REQUEST | 1 voice request; retries may send more. | အသံထုတ် request ၁ ခု။ ပြန်စမ်းလျှင် ပိုပို့နိုင်သည်။ |
| V3_NEW_TAKE_EFFECT | The new take becomes selected; old takes stay in history. Existing exported files do not update. | Take အသစ်ကို ရွေးမယ်။ Take အဟောင်းများ မပျက်ပါ။ ထုတ်ပြီး ဖိုင်အဟောင်းများ မပြောင်းပါ။ |

## Native source-cut draft checkpoint (Q-20/Q-23; PR-4 prerequisite)

- `Movie source ranges` is shared native UI, first reachable below Script in Voice detail and intended for reuse by Story. Start/End accept exact decimal seconds (up to 3 decimals), never floating-point timestamps. Trim/add/reorder/remove/split/join remain unsaved until Save; Discard, Back/dismiss and project/detail disposal cancel the draft.
- Split means two source ranges **inside the same stable block**, at an explicit source time. Join is allowed only for consecutive ranges whose source endpoints touch. A gap is never silently included and an overlap is never discarded. Automatic block/text/take splitting/merging is not implemented or implied. At least one range remains; each is ≥500 ms and within the captured movie.
- Save writes a new native `rs` through the unchanged ArtifactStore/ProjectSession, clears Story approval, and preserves block IDs/text/speak-as, selected takes/history, Fit, caption windows, layers and every unrelated project field. Voice is not made stale merely by a cut-only edit: the existing spoken-text hash is unchanged. No schema, approval rule, renderer or save-engine change. Revision/project conflicts reject the draft.
- Accurate native timeline/preview/export use the saved range order through their existing compiler; the editor never converts through the v1 first-clip projection. The Story/Source screen redesign remains separate.

New EN/MY copy for owner review:

| Key | English | Burmese |
| --- | --- | --- |
| `V3_CLIPS` | Movie source ranges | ရုပ်ရှင် ရင်းမြစ် အချိန်ပိုင်းများ |
| `V3_EDIT_CLIPS` | Edit movie cuts | ရုပ်ရှင် ဖြတ်ချိန်များ ပြင်မယ် |
| `V3_CLIP_NUMBER` | Range {n} | အချိန်ပိုင်း {n} |
| `V3_CLIP_FROM` | Start (seconds) | အစ (စက္ကန့်) |
| `V3_CLIP_TO` | End (seconds) | အဆုံး (စက္ကန့်) |
| `V3_CLIP_SPLIT_AT` | Split at source time (seconds) | ရင်းမြစ် အချိန်မှ ခွဲမယ် (စက္ကန့်) |
| `V3_CLIP_SPLIT` | Split range | အချိန်ပိုင်း ခွဲမယ် |
| `V3_CLIP_MERGE` | Join next range | နောက် အချိန်ပိုင်းနှင့် ဆက်မယ် |
| `V3_EARLIER` | Earlier | အရှေ့ ရွှေ့မယ် |
| `V3_LATER` | Later | အနောက် ရွှေ့မယ် |
| `V3_CLIP_ADD` | Add movie range | ရုပ်ရှင် အချိန်ပိုင်း ထည့်မယ် |
| `V3_CLIPS_HINT` | Movie cuts; Save needs Story approval again. | ရုပ်ရှင် ဖြတ်ချိန်သာ။ သိမ်းလျှင် ဇာတ်လမ်း ပြန်အတည်ပြုပါ။ |
| `V3_CLIPS_NOTE` | Movie cuts only, not voice/caption timing. Existing takes stay unchanged. | ရုပ်ရှင် ဖြတ်ချိန်သာ ဖြစ်ပြီး အသံ၊ စာတန်းချိန် မဟုတ်ပါ။ Take အဟောင်းများ မပြောင်းပါ။ |
| `V3_CLIP_INVALID` | Keep at least one range, each ≥0.5 seconds and inside the movie. Use seconds with up to 3 decimal places. | ရုပ်ရှင်အတွင်း အနည်းဆုံး ၀.၅ စက္ကန့်စီ ရှည်သော အချိန်ပိုင်း တစ်ခု ရှိရမယ်။ စက္ကန့်ကို ဒသမ ၃ နေရာအထိ ထည့်နိုင်သည်။ |
| `V3_CLIP_MERGE_HINT` | Only touching source ranges can join. Each split part must be ≥0.5 seconds. | ရင်းမြစ်တွင် ဆက်နေသော အချိန်ပိုင်းများသာ ဆက်နိုင်သည်။ ခွဲပြီး တစ်ပိုင်းစီ အနည်းဆုံး ၀.၅ စက္ကန့် ရှိရမယ်။ |

## Story presentation checkpoint (PR-4a / Q-25)

- Native BlockCards use every source range, stable block ID, current spoken hash, real selected take and existing Smart Fit timing. Opening a card reuses the Voice inspector: Script/speak-as, native movie ranges, selected/history takes, Record/Import/Clean and scoped voice confirmation. No second editor, player or approval policy.
- Story has ProjectTopBar step 2/5, Classic in overflow, and measured sticky approval/navigation. Approve calls existing `RecapModel.approveScript`/core gates; only a current approved story offers Next: Voice. Arrow/platform Back clears detail before Source. Explicit editor Save/Cancel/Discard are unchanged.
- Story writing options retain summary, subtitle/extract/transcribe, style/register, hook, credit and existing write/rebuild confirmations. With existing blocks, narrow layouts put these secondary options after the list so the first block remains visible even with OS fallback/font 2.0; an empty story opens writing options first. Style/register are choices for the next write, not an implicit current-story edit; new Story Gemini writes always show the existing request confirmation. Source relocation is a later scoped unit.
- Wide Story keeps the pre-existing three-pane reachability without duplicating preview controls inside its inspector. Reordering all project panes and keyboard shortcuts remains PR-8. There is no Story concept image; new captures are paired with concept 04 as a shared block-pattern reference, not claimed as a missing Story mockup.
- No project schema, artifact format, IDs, save/TTS/preview/export engine, dependency or device-acceptance change. Source/Export/Jobs/Style/Music Video/global wide migration and the five separate tasks remain open.

New EN/MY copy for owner review:

| Key | English | Burmese |
| --- | --- | --- |
| `V3_STORY_OPTIONS` | Writing options | ရေးရန် ဆက်တင် |
| `V3_STORY_WRITING_HINT` | Style and register apply on the next story write. | ပုံစံနှင့် စကားပုံစံကို နောက်တစ်ကြိမ် ဇာတ်လမ်း ရေးရာတွင် သုံးမယ်။ |
| `V3_APPROVE_STORY` | Approve story | ဇာတ်လမ်း အတည်ပြုမယ် |
| `V3_SELECT_BLOCK` | Select a block to edit its script, movie cuts and voice. | စာသား၊ ရုပ်ရှင် ဖြတ်ချိန်နှင့် အသံ ပြင်ရန် အပိုင်းတစ်ခု ရွေးပါ။ |


## Source presentation checkpoint (PR-4b / Q-26)

- Native step 1/5 uses ProjectTopBar/Classic overflow and a measured sticky Import or Next: Story. Movie metadata uses the stored duration and known positive resolution; unknown dimensions and rounded-zero size are omitted. No Saved chip, sample thumbnail/name/count or estimated scan speed. A project/revision-bound replacement confirmation precedes the unchanged import, whose existing pointer-clearing effects are stated explicitly; Cancel does not open the picker.
- Fewer/Normal/More OptionCards select transient thresholds 16/10/6. Only the explicit Find scenes action invokes the existing scanner/save path; Normal (10) remains Recommended. The helper states that a successful scan does not rebuild Story clips/takes and does clear the existing Story approval. Failed scans retain the saved project and expose the actual reason for retry.
- Subtitles, Summary and Transcribe move from Studio Story writing options to Source, without duplication. Classic keeps the same shared controls. Existing .srt import/replace/remove, text-track extraction by real stream index, picture-track explanation, immediate summary save, request/today confirmation and transcription Cancel/Retry remain on their unchanged model paths.
- Inspection found that `RecapModel.refresh()` dropped transient embedded-track metadata immediately after import/selection. Keep it only for the same project/movie. A reopened movie is **unprobed**, not “no tracks”: the explicit Read subtitle tracks action reuses the existing off-main MediaProbe without saving, rejects results after a project/revision change, and permits retry with the actual probe failure reason, not an import/copy message. Track chips reflect the existing selected stream pointer. Empty states make no unmeasured scan-time claim. This is UI metadata only, not a new persisted field, probe engine or extraction format.
- Scene picks use actual start/end times, target/selected duration, picked-only, clear and 30-row pagination. Picking does not reset the current page; project switches reset transient selection/confirmation. No eager decoder calls: lazy cached thumbnails remain a separate tested PR. Existing wide-pane reachability is retained, not claimed as the PR-8 list · preview · inspector redesign.
- Tests cover the 27-cell actual-shell EN/MY/font/bundled/fallback matrix, real scroll/pointer actions, visible sticky bar, density browse with byte-identical files, scroll-visible transcription Start/Back and cancellation, plus replacement cancellation (including registered platform Back and outside dismissal), import/extraction, scan failure/retry/busy controls, persisted summary/subtitles/picks/reopen and immutable Story/Voice history. Full/actual-remote/CI results belong to this unit's PR and handoff; phone/PC acceptance remains pending.

New EN/MY copy for owner review (generated from the implemented UiText additions):

| Key | English | Burmese |
| --- | --- | --- |
| `V3_MOVIE` | Movie | ရုပ်ရှင် |
| `V3_READ_TRACKS` | Read subtitle tracks | စာတန်း track များ ဖတ်မယ် |
| `V3_TRACKS_UNREADABLE` | Could not read subtitle tracks: {detail} | စာတန်း track များ ဖတ်မရပါ: {detail} |
| `V3_AUDIO_FOUND` | Audio found | အသံ ပါသည် |
| `V3_SPLIT_MOVIE` | How should the movie be split? | ရုပ်ရှင်ကို ဘယ်လို ပြကွက်ခွဲမလဲ။ |
| `V3_DENSITY_FEWER` | Higher cut threshold · fewer detections | ဖြတ်မှတ်အဆင့် ပိုမြင့် · ပြကွက်တွေ့မှု ပိုနည်း |
| `V3_DENSITY_NORMAL` | Default cut threshold | ပုံမှန် ဖြတ်မှတ်အဆင့် |
| `V3_DENSITY_MORE` | Lower cut threshold · more detections | ဖြတ်မှတ်အဆင့် ပိုနိမ့် · ပြကွက်တွေ့မှု ပိုများ |
| `V3_SCAN_RESULT` | Scan result · {n} scenes | ရှာဖွေမှု ရလဒ် · ပြကွက် {n} ခု |
| `V3_SCAN_HINT` | Scans offline. A successful scan clears Story approval; existing Story clips and takes stay. | Offline စစ်မယ်။ ရှာဖွေမှု အောင်မြင်လျှင် ဇာတ်လမ်း ပြန်အတည်ပြုရန် လိုမယ်။ လက်ရှိ ဇာတ်လမ်း clip နှင့် take များ မပြောင်းပါ။ |
| `V3_NO_SCENES` | No scenes yet. Choose a split option and find scenes. | ပြကွက် မရှိသေးပါ။ ခွဲပုံ ရွေးပြီး ပြကွက် ရှာပါ။ |
| `V3_REPLACE_MOVIE_CONFIRM` | Choose a replacement movie? Import clears the current scenes, Story pointer, approval and take list. Existing artifact files are kept. | အစားထိုး ရုပ်ရှင် ရွေးမလား။ ထည့်လျှင် လက်ရှိ ပြကွက်၊ ဇာတ်လမ်း ချိတ်ဆက်မှု၊ အတည်ပြုချက်နှင့် take စာရင်းကို ရှင်းမယ်။ Artifact ဖိုင်အဟောင်းများ မပျက်ပါ။ |

## Export presentation (PR-5a / Q-27; validation pending)

- Native step 5 uses ProjectTopBar, Classic overflow and one measured sticky Export/Preview bar. The same shared Export content retains quality, real prerequisite findings, Exact-frame make/remake/select/stale/use-thumbnail, render cancellation/progress, completed output and loudness/failure notes. Help stays in the scrolling Output card so it cannot crowd out content at font 2. Run/result kind does not encode resolved dimensions, so status/history say Export rather than assuming 1080p. No render or approval engine changes.
- Publish Kit is collapsible in Studio. Title, description, hashtags and chapters have Copy; Save, draft Discard, share-file and saved-title thumbnail controls remain reachable. Project identity resets transient text drafts. Browsing/Copy/Discard must not write project files.
- Preflight rows are still the existing core report, not a completed Fix-navigation feature. No inert “Fix →” text is presented as an action. Existing wide panes remain until PR-8; no sample preview, fake Saved/ETA or owner-device acceptance.

| Key | English | Burmese |
| --- | --- | --- |
| `V3_HASHTAGS` | Hashtags | Hashtag များ |
| `V3_CHAPTERS` | Chapters | အခန်းများ |
| `V3_COPY_HASHTAGS` | Copy hashtags | Hashtag များ ကူးမယ် |
| `V3_COPY_CHAPTERS` | Copy chapters | အခန်းများ ကူးမယ် |

## Jobs presentation (PR-5b / Q-28; validation pending)
Running / Needs you / Finished share JobCard and StatusChip; red failures preserve their reason, and cancelled is not Done. No theoretical ETA, fake approval-wait, thumbnail, Pause or Clear-finished action is added. TTS/Transcribe Resume/Retry uses the existing runner and exact persisted remaining items. Current live IDs are rechecked before cancellation, resume or output actions. Open switches to the row's project through the existing Dashboard open path; unbound storyboard opens Projects, and a failed open stays in Jobs with its actual reason. Render Play/Share/Save-to-Gallery require the live matching completed run, existing file and platform port; actual results/errors stay with that stable job row, not an off-screen global header. Existing Open/Cancel controls remain; no history or pipeline changes. Project/index titles are preferred; unavailable titles fall back to the folder name, never a concept name.

| Key | EN | MY (owner review pending) |
| --- | --- | --- |
| V3_JOBS_NEEDS_YOU | Needs you | သင် လုပ်ဆောင်ရန် လိုသည် |
| V3_JOBS_FINISHED | Finished | ပြီးဆုံးသော အလုပ်များ |
| V3_RESUME_REMAINING | Resume {n} remaining | ကျန် {n} ခု ဆက်လုပ်မယ် |
| V3_JOB_OUTPUT_UNAVAILABLE | This output is no longer available. Open its project to check. | ဒီဖိုင်ကို ယခု ဖွင့်၍ မရပါ။ ပရောဂျက်ကို ဖွင့်ပြီး စစ်ပါ။ |

27-cell real-container pointer/scroll/Back matrix, persistent local runner Resume/Retry/Cancel and immutable-project checks are required. Fixture reuse changes only ExportHarness test visibility, not its behavior. Wide/global nav and owner phone/PC remain pending; a controlled queue screenshot is not evidence of a real background export.


## Style presentation (PR-6 / Q-29; scoped implementation)

Native step 4/5 owns the project header/Classic overflow and measured keyboard-safe Next Export/Previous bar. Wrapping Layers/Captions/Look/Audio chips are presentation state only, reset by project identity. The existing muted native output player, scrubber, diagnostics and Exact Frame stay reachable; unavailable player reasons retain Exact Frame as an explicit fallback. No new decoder, caption painter or rendering path.

Layers keeps all five supported types and stable testTags. Normal labels omit engine IDs; exact timing/rect values, IDs and the legacy separate gesture surface remain expandable. Percent convenience sliders edit the same output-frame normalized rect draft, not movie-picture masks. Existing validation, staged order, image import, explicit Save/Discard and reference-only Remove remain authoritative. Back cancels a draft before leaving; section/Next/Classic actions cannot silently discard it. No visibility toggle implies export exclusion. This describes the PR-6 fallback; the separate task-2 implementation/validation checkpoint is recorded above, not silently counted as PR-6 work.

Layers More options retains ratio, pre-Looks source-movie mask controls/gestures, background, watermark, Brand Kit save/name/cancel and per-block Smart Fit. Captions retains burn toggle, manual colours/custom hex/apply/clear, S/M/L and custom factor, outline width/colour, dark box, Library preset naming/save/cancel and always-.srt explanation. Current factors are S=0.85, M=1.0, L=1.2; existing custom factor is 0.5–3.0. CaptionStyle has no font-family or arbitrary position field; Settings/Library font resolution and existing caption placement/painter stay unchanged. Clean/Bold/Boxed/Outline and any XL convenience are not claimed complete in this presentation PR: renderer-supported presets are a separate task. Look retains all existing Looks; Audio retains movie/BGM volume, BGM add/change/remove/duck, polish, normalization and trim-new-takes. Classic calls the same reused controls with no grouping filter.

| New key | EN | MY |
| --- | --- | --- |
| V3_STYLE_LAYERS | Layers | Layer များ |
| V3_STYLE_CAPTIONS | Captions | စာတန်း |
| V3_STYLE_LOOK | Look | မြင်ကွင်း ပုံစံ |
| V3_STYLE_AUDIO | Audio | အသံ |

Fresh final-code local validation: targeted 68/68, full check --rerun-tasks --no-build-cache 1373/1373 (UI 781), expanded installed-VLC/ffmpeg plus real unavailable-player fallback regression 396/396; failures/errors/skips 0/0/0. The 32 selected normal Style screenshots were opened individually against concepts 05/06 and reproduced byte-identically; one additional cold-fontconfig Exact Frame image was reviewed separately because native font metrics differ. The 14 MY selector failures were reproduced with raw semantics before a test-only correction: exact rendered catalog text, real scroll/display and actual TextLayoutResult syllable-cut assertions, with no weakened visibility, 48dp, tolerances or forced clicks. Initial Style CI check had 1372 cases, 1366 passed, 1 failed and 5 skipped (other five jobs succeeded): the new fallback test omitted legitimate native fontconfig sidecars. The test-only repair validates exact native paths, cache-directory signature, native magic/version/actual-size header; negative tests reject arbitrary files. A cold real-ffmpeg gate passes 2/2 and requires native cache creation, all original hashes unchanged and a real cached PNG. Earlier failures/skips remain retained. The rectangle-helper test repeats ratio/Looks labels without passing them to the helper and is not ratio/Looks mapping proof. Final exact-remote reruns and six-job CI results must be recorded in Draft #190/handoff; local passes do not substitute. Global wide/header/nav work stays PR8_PENDING; PHONE_PENDING / OWNER_PC_PENDING and owner wording review remain pending. This is scoped presentation, not complete concept parity.

## Music Video presentation (PR-7 / Q-30; scoped implementation)

Same ShotSheetScreen and the existing Storyboard/Shots/References/FlowHandoff models: five sections, gates, saves and confirmations are unchanged. The project bar keeps Input · Shot sheet · Inbox · Export · Classic steps; Storyboard/Shots/References/Flow Handoff stay under Classic steps, and approve-all, credit counter and batch sheet stay under All shots. No schema, engine, WorkflowRules, import or handoff change; Flow stays manual.

- Shot list: existing ShotStatus badges, a real "{n} shots · {accepted} accepted" summary and transient All / Not accepted / Accepted filters with actual counts. Filters are never saved, reset per project and only change which rows are listed; the open shot stays open when filtered out.
- Below 840 dp the list comes first, then one shot's sheet. "← Shot list" and platform Back close the sheet before the shell Back leaves (Music Video detail → shot list); the selection is kept and a project switch returns to that project's list. At/above 840 dp PR-8 reorders the same content to list · media preview · sheet inspector; all five sections and tags remain.
- Navigator: Shot N · time, badge, position i / n, ← / → only where a neighbour exists (no inert arrows).
- Prompt text is monospace. Copy prompt is the one sticky primary, calling the existing FlowHandoffModel.copyPrompt with its gate (disabled while step 4 shows the gate reason); Open Flow is the sticky secondary and only opens the URL. Import video, pass, credits and batch remain manual in step 4. Classic Flow Handoff keeps its own card buttons.
- "Use this version" (one listed version) stays separate from "Accept video" (newest import on the Flow card).
- Concept 09 differences: no Saved chip (no reliable save state), no five-step ProjectTopBar (Music Video has no five-step flow), no hero player above the sheet on narrow (per-version preview stays in step 5, the media pane on wide), no collapsed Prompt & references card, no sample title/counts/durations. Real screenshots use the fake-provider synthetic test project because the QA fixture has no Music Video project. Owner phone/PC checks remain pending.

| New key | EN | MY |
| --- | --- | --- |
| V3_MV_SUMMARY | {n} shots · {accepted} accepted | Shot {n} ခု · လက်ခံပြီး {accepted} ခု |
| V3_MV_OPEN_COUNT | Not accepted {n} | မလက်ခံရသေး {n} |
| V3_MV_ACCEPTED_COUNT | Accepted {n} | လက်ခံပြီး {n} |
| V3_MV_FILTER_EMPTY | No shots match this filter. | ဒီ filter နှင့် ကိုက်ညီသော shot မရှိပါ။ |
| V3_MV_SHOT_LIST | ← Shot list | ← Shot စာရင်း |
| V3_MV_MANUAL_HINT | Manual Flow handoff · import the video in step 4 | Flow သို့ ကိုယ်တိုင် ပို့ရန် · ဗွီဒီယိုကို အဆင့် 4 မှာ ထည့်ပါ |


## Desktop / wide presentation (PR-8 / brief §6.11)

- Shared `ProjectPanes`: window width ≥840 dp is list · preview · inspector, with the existing `project-list`, `project-preview`, `project-editor` tags. 840–1199 dp keeps the inspector's 44% share (24/32/44); ≥1200 dp uses 23/45/32. Content height excludes the measured sticky bar. Below 840 dp the existing single-pane list/detail flows remain.
- Source, Story, Voice, Style, Recap Export and ShotSheet use that order. Voice now keeps its real filtered block list on the left, one native output preview/Exact Frame in the centre and the existing voice lock/selected block inspector on the right. No duplicate wide preview. Controls, confirmations, filters, version/take history and explicit Save/Discard keep their tags and model callbacks.
- With project-surface focus, Space calls the visible native Recap preview's existing Play/Pause action, including unavailable-player recovery; arrows navigate Story/Voice blocks or open ShotSheet shots. Arrows stop at the ends (never wrap); from an unselected block list, Right opens the first block. Recording disables block navigation just as the visible navigator does. Empty/unsupported previews register no playback action; Music Video's existing per-version player is unchanged (no invented Space controller).
- Focus belongs to the actual control: **all** focused descendants, including non-Af text fields, buttons, scrubbers and dialogs, keep their keys. Text typing/caret/selection never reaches the project handler. Project modifiers are ignored. Tab and native button activation remain available. An unconsumed passive-content/background tap restores surface focus without consuming child clicks, drag or wheel gestures. No per-input focus counter or mandatory input wrapper.
- DESIGN §5's historical Ctrl+C/A project intent has no production handler at exact PR-7 (source search retained in evidence). Q-31 awaits owner decision; PR-8 adds neither automatic copy nor approval. Existing text copy/select and scrubber arrow/Home/End behavior is not replaced.
- Owner-requested PR-7 recheck follow-up: the pre-existing Regenerate `↻` was a missing glyph in the bundled Pyidaungsu/Noto Sans Myanmar fonts. `AfText` now draws just that decoration inline, retaining the exact text/accessibility label, selected prose font, existing buttons, confirmations and gates. No font dependency or workflow change. Focused follow-up validation: 34/34 (4 EN/MY × bundled/fallback large-font symbol cases, 4 existing Storyboard cases, 26 ShotSheet/Flow/language/navigation cases), 0 failures/errors/skips. Real enabled/disabled pointer actions, ≥48 dp targets, unchanged label/font and syllable checks are asserted; captures were visually reviewed. Final-remote full check and CI remain separate gates in the Draft PR/handoff.
- Concept 11 is direction, not sample data: no fake Saved, voice/model, movie thumbnail, waveform, compare, ETA or multi-track timeline. At the PR-8 checkpoint, thumbnails, on-video handles, caption presets, preflight Fix and waveform were separate tasks/fallbacks; subsequent thumbnail and task-2 records above supersede only their own units, while tasks 3–5 remain pending. Empty previews can still look sparse; 840/font2 Burmese can still be dense. Device/visual acceptance remains PHONE_PENDING / OWNER_PC_PENDING.
- Targeted validation at implementation handoff: 228/228 screen/input/PR-7 regression cases and 3/3 unavailable-VLC + real-ffmpeg Exact Frame cases (412 EN, 1440 EN/MY); both runs have 0 failures/errors/skips. All earlier failed attempts remain: initial checkpoint compile failure (missing ShotSheet shortcut imports, 0 tests), 2 new-test sticky-dialog scroll-selector failures, then 3 legitimate 840/font2/MY/fallback syllable-layout failures fixed by production label wrapping. No existing assertion or window was weakened. Existing matrices retain 412/840 × font 1/1.3/2 × EN/MY × bundled/fallback, 360 stress and 1440 EN/MY; AdaptiveRecapTest now asserts list · preview · inspector. Real pointer/wheel/drag, ≥48 dp sticky targets, focus/typing/modifier/navigation/disposal and opening/browsing project-file byte identity supplement those matrices. Fresh final-remote full check and all-six CI results belong in the stacked Draft PR/handoff; targeted passes do not prove them. The dedicated installed-VLC gate is separate from local fallback and must exercise actual Space playback.

| New key | EN | MY |
| --- | --- | --- |
| V3_SHORTCUTS_HINT | Click background for keys: Space play / pause · ← / → previous / next block | နောက်ခံကို နှိပ်ပြီး ကီးသုံးပါ: Space ဖွင့် / ခဏရပ် · ← / → ယခင် / နောက် အပိုင်း |
| V3_MV_SHORTCUTS_HINT | Click background for keys: ← / → previous / next shot | နောက်ခံကို နှိပ်ပြီး ကီးသုံးပါ: ← / → ယခင် / နောက် shot |


## Caption presets — separate task 3 (brief §6.06/§7.3; commit-time checkpoint)

The verified task-2 head is `1fa2db68f306143591c4e7f2fc7b4e32d4a7d436`: clean fresh-clone full1476/1476/0failures/errors/skips; all six CI44306185607 jobs SUCCESS,including actualVLC8/8/0skips and12cold starts. Previous task-2 paragraphs are historical checkpoints,not current pending claims. Owner/device/MY wording acceptance is still pending.

Use the existing `RecapModel.setCaptionStyle` atomic project save. Presets replace only the seven existing CaptionStyle values in one call; burn,timing,story,takes,layers,Looks,ratio,placement and Library entries are unchanged. Existing manual controls/S/M/L/testTags stay; Classic and native Style reuse the same component. New callbacks observe the open-project flow and recheck captured folder/full Project snapshot (including updatedAt) and busy state. No media/core/schema/model/dependency/config/workflow changes.

| Choice | Existing field mapping |
| --- | --- |
| Clean | size=M(1.0),colour=#FFFFFF,box=false,customColour=null,customSizeFactor=null,strokeWidth=0,strokeColour=#000000 |
| Boxed | Clean values with box=true; existing renderer dark-box style |
| Outline | Clean values with strokeWidth=3; existing black outline |
| S / M / L | unchanged factors0.85/1.0/1.2;clear the custom-size override |
| XL | size=L plus customSizeFactor=1.4;all colour/box/stroke fields retained |
| Manual colour/custom hex | unchanged colour/customColour precedence;no palette conversion |
| Placement | unchanged captionsOnMask/eligible lowest lower-half mask and existing bottom fallback; no new free-position field or fake selector |

`strokeWidth=0` is the EXISTING automatic outline,max(2,size/24),not a promise of outline-off. Keep the old manual control and renderer semantics; do not change the engine to manufacture Clean. True Bold is omitted: neither CaptionStyle nor Overlay exposes font weight,and AssWriter writes Bold0. Do not rename a size/outline change to imply a new bold font. Font-family selection stays Settings/Library. XL changes the existing custom pixel-size factor;the renderer's line chunking still uses L's1.2 base factor. No rewrap/schema/renderer change is hidden.

Preset status normally derives from the complete existing persisted CaptionStyle; non-matching settings are Custom. After a genuine manual edit,show transient Custom even if its values equal another mapping. Choosing a preset clears this transient marker and unapplied custom text. Project switches reset it;after reconstructing the UI,an exact persisted mapping may resolve to its name again because no preset ID/manual flag is persisted. Failed saves do not mark an unapplied edit as Custom. XL is a manual size convenience,not a fourth style preset.

The existing output-frame/export caption painter is unchanged. Size remains output pixels,independent of UI language/accessibility font scale. Required tests: mapping+existing-field JSON,actual Style chips and persisted reload,all non-caption/artifact bytes preserved,manual-to-Custom,busy and A/B isolation,EN/MY1×/2× at360/412/840/1440 with real scroll/pointer and48dp targets,all preset/XL production caption pixels unchanged across locale/scale,and real ffmpeg Exact Frames for every supported mapping. Existing assertions/windows/timeouts and installed-VLC regression gates stay intact. Fresh actual-final-head full and all-six CI are measured in the separate Draft PR/handoff,not assumed from this checkpoint.

| Key | EN | MY (owner wording review pending) |
| --- | --- | --- |
| V3_CAPTION_PRESETS | Caption presets | စာတန်း ပုံစံများ |
| V3_CAPTION_PRESET_STATUS | Preset: {name} | ပုံစံ: {name} |
| V3_CAPTION_CLEAN | Clean | ရိုးရှင်း |
| V3_CAPTION_BOXED | Boxed | နောက်ခံဘောင် |
| V3_CAPTION_OUTLINE | Outline | အနားသတ် |
| V3_CAPTION_CUSTOM | Custom | စိတ်ကြိုက် |
| V3_CAPTION_XL | XL | XL |

Q31 / PHONE_PENDING / OWNER_PC_PENDING / owner MY wording remain pending. Preflight Fix and waveform are still separate unimplemented units.

Initial task-3 compilation-only attempt failed before any tests: new test scaffolding used an incomplete Output constructor and an inferred heterogeneous parameter-array type. Corrected with existing RenderPresets.preview and explicit Array<Any>,without changing assertions,windows or production engine. Failed log/source retained; not a test pass.

Focused attempt2 ran50 tests:41passed/9failed/0errors/skips;all pre-existing cases passed,including actualVLC and12cold starts. Seven new matrix cases exposed a real UI guard bug: a same-value preset save advances Project.updatedAt without changing RecapUiState,so a child subscribed only to that state retained a stale full-project callback. It now also observes the existing open-project flow,retains the folder/full-snapshot guard,and avoids redundant same-value preset saves. A no-op selection has a project-byte-identity assertion. The other two new-test failures were an off-by-one ASS-column parser (now uses the existing named Format header) and a new fixture assumption that1500ms had captions (QA FIRST is[200,1200),now tested at its700ms interior). Real active-cue/ffmpeg-burn assertions remain,and cross-look comparison is strengthened to decoded ARGB pixels. All failed source/logs remain in handoff;no original assertion,test window or engine changed.

Focused attempt3 passed50/50,0failures/errors/skips (9XML suites),2m23s after separate compilation prep. It covers the14new cases plus unchanged Style/caption-language and actualVLC regressions;all12cold trials pass. This is a focused pre-commit run,not the final-head full/CI gate. Five representative full-window UI captures (412EN1,412MY2,360MY2,840MY2fallback,1440EN1),all four bundled native caption looks and four real ffmpeg Exact Frames were individually reviewed. Native captions contain actual Burmese ink;the Exact Frame fixture's caption is FIRST at700ms,distinct from its existing QA Burmese TEXT layer. No new universal native/export pixel equivalence,MY export-font acceptance or owner approval is claimed.840/font2 fallback remains dense and scrollable.
