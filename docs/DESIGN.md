# UI/UX Design

Reference mockup: `docs/mockups/autoflow-ui.html` (open in a browser).

## 1. Visual language
- Dark studio theme. Background `#0B1020`, panel `#131A2E`, card `#1A2340`, line `#263153`
- Text `#E6EAF5`, muted `#8A94B3`
- Accent purple `#8B5CF6` (primary actions), cyan `#22D3EE` (Flow / external), green `#22C55E` (accepted), amber `#F59E0B` (stale/warn), red `#F87171` (failed)
- Primary button fill `#7C3AED` keeps white action text above WCAG AA 4.5:1; selection accent stays `#8B5CF6`.
- Fonts: UI Burmese = Pyidaungsu → Noto Sans Myanmar fallback; Latin = Inter/Segoe UI; prompt text = monospace
- Regenerate decoration: the bundled Burmese fonts lack `↻` (U+21BB). `AfText` draws only that character inline, at 1 em and in the text's enabled/disabled colour; its exact accessible label and the surrounding selected prose font remain unchanged. No platform symbol font, extra dependency or different button/confirmation is required (PR-7 recheck follow-up in the PR-8 stack).
- Radius 12, subtle 1px borders, no heavy shadows inside panels
- Logo: gradient purple→cyan rounded square with white play triangle

## 2. Information architecture
Sidebar is the pipeline, in order. Selected step is highlighted; locked steps are dimmed with a lock glyph and a tooltip saying what must be approved first.
```
Project: Dashboard · Input · Storyboard · Shots · References · Flow Handoff · Timeline · Export
Mode:    Music Video · Movie Recap
System:  Settings
```
Title bar right side always shows provider status: `Gemini API ● Free tier` · `Flow ● Manual`.

## 3. Screens (desktop)
### Dashboard
Project list (name, mode, ratio, progress x/y, last opened), New Music Video, New Movie Recap, Open folder.

### Input
Form: lyrics (multiline, section markers `[Verse]`, `[Chorus]` optional), duration mm:ss (auto-read from mp3 if attached), song style, video style (chips + custom), mood/colour, ratio chips **16:9 / 9:16 / 1:1 / 4:5 / 21:9**, clip length **8s / 10s / 5s**, storyboard mode **Narrative / Performance / Abstract / Mixed**, character quick-add. Primary button: "Storyboard ထုတ်မယ်". After a song is imported, a "Lyrics timing" card: "Sync lyrics (tap)" (song plays; current line big, next line muted; "⤵ Line starts" / "⏹ Last line ends", Undo, Restart, Save) and "✨ Suggest timings (Gemini)" (confirm first); Export then shows each line when it is sung (D-30, D-31).

### Storyboard
Vertical list of cards: timecode (cyan, tabular), lyric line (muted), Burmese scene text (editable inline), camera · mood chips, version tag. Card actions: ✎ ပြင် · ↻ ပြန်ထုတ် · ✓ Approve. Header: "Approve all", storyboard version dropdown, regenerate-all (confirm dialog shows what becomes stale), "♩ Snap cuts to beat" switch (D-32).

### Shots
Grid of shot cards: thumbnail (ref or video frame), `#NN`, timecode, badge. Badges:
`Draft` grey · `Prompt ready` purple-outline · `Needs Flow` purple · `Imported` cyan · `Accepted` green · `⚠ Stale` amber. Filter chips by badge.

### Shot detail (right panel, persistent on Shots/References/Handoff)
Header `Shot #NN` + badge · meta chips (timecode, ratio, duration, model, draft/final) · Ref A / Ref B 2-up with select + "Upload own" + "Regenerate" · PromptIR (collapsed structured view; expanded English string, copyable) · Buttons grid:
`📋 Copy Prompt` (primary) · `↗ Open Flow` (cyan) · `📦 Export Package` · `📥 Import Video` · `✓ Accept Video` (full width, green).
Copying the prompt sets handoff state to SENT_TO_FLOW automatically.

### Flow Handoff
Table of pending shots with checkboxes → "Export batch sheet (HTML)". Inbox panel showing unmatched files from `inbox/` with "Attach to shot…" per file. Manual Flow credit counter (today / this month, editable).

### Timeline
Not an NLE. Ordered clip strip (drag to reorder, trim handles), audio track with offset/fades, overlay list (lyrics auto from shots, title card), preset selector, Preview (proxy) / Export buttons with output path.

### Settings
Gemini API key (masked, "Test" button), model config editor (read-only view of `config/models.json` + override path), ffmpeg path + detect, font paths, default ratio/clip length, language.

## 4. Android (full app, D-22)
Bottom nav: Projects · Storyboard · Shots · Inbox · More (Input, References, Flow Handoff, Timeline, Export, Settings). Same cards, single column; button rows wrap. The phone layout follows the window width (< 600 dp), not the platform. Android Back: a More screen → More, another tab → Projects, Projects → leave the app. Every desktop screen exists on the phone, including Timeline and Export (render runs in the background with a notification). Primary actions: Approve, Copy Prompt, Edit, Regenerate, Attach video (from share sheet). Settings → long-press the version opens the Self-test (S6).

## 5. Interaction rules
- Every destructive or quota-consuming action has a confirm dialog that states what will be generated and what becomes stale.
- Regenerate never replaces; it adds a version. Version chooser shows side-by-side.
- Keyboard (UI v3 / PR-8): `Space` toggles the visible native Recap preview; `←/→` selects the previous/next Story/Voice block or an open Music Video shot. No wrap at either end; no block selection means `→` opens the first block. Same callbacks/gates as the visible controls; no multi-track timeline.
- Shortcuts own the project surface focus: click passive content/background to return to it. Text fields of any kind, focused buttons, scrubbers and dialogs retain their own keys; modifiers are not project shortcuts. Existing scrubber arrows/Home/End and native text selection/copy are preserved.
- Historical design intent: `Ctrl+C` on a selected shot copies its prompt and `A` approves. A production-source search at PR-7 `dfd6199` found no such handlers (only scrubber keys). These are **not implemented** by PR-8; Q-31 in PLAN awaits the owner's scope/gating decision. Do not infer or add approval/copy behavior from this older sentence.
- Empty states explain the next step in the selected EN/MY UI language.
- UI v3: see `specs/ui-v3.md` for the complete control map. ProjectTopBar uses five segments; step navigation moves to StickyActionBar. Secondary tools remain in overflow/sections/Diagnostics.
- Back: modal/draft → block list → previous Recap step → project list → Home → leave confirmation. Top-left and Android Back share this rule.
- Sticky actions observe keyboard insets; content reserves their space. No fake Saved, ETA, quota or concept sample data.

## 6. Copy (Burmese UI strings — keep these exact)
Approve = "အတည်ပြုမယ်" · Edit = "ပြင်မယ်" · Regenerate = "ပြန်ထုတ်မယ်" · Copy Prompt = "Prompt ကူးမယ်" · Needs Flow = "Flow မှာ လုပ်ရန်" · Stale = "ပြန်ထုတ်ရန် လိုသည်" · Accept video = "ဗွီဒီယို လက်ခံမယ်"

Studio v2 words (D-67, code: `ui/i18n/My.kt`): nav ပင်မ · ပရောဂျက်များ · စာကြည့်တိုက် · အလုပ်များ; Recap steps ရင်းမြစ် · ဇာတ်လမ်း · အသံ · ပုံစံ · ထုတ်ယူ ("← ယခင်အဆင့်", "နောက်အဆင့်: ပုံစံ →"); chips Export = ထုတ်ယူ, Input = ထည့်သွင်း, Shot sheet = Shot စာရွက်, Inbox = ဝင်စာ, Classic steps = အဆင့်ဟောင်းများ, Classic Recap = "Recap ပုံစံဟောင်း"; "Use this version" = "✓ ဒီဗားရှင်း လက်ခံမယ်" (distinct from Accept video). Product names stay English (Recap, Music Video, Flow, Prompt, Storyboard, Shot, Gemini). Narrow labels and user lyrics carry U+200B between Burmese syllables so a line never breaks inside a syllable.

## 7. Studio v2 (Phase 5, D-40)
Mockups: `docs/mockups/phase5.html` — eight AI-drawn screens with English labels (image models garble Burmese); the app uses Burmese strings (§6). Known mockup slips: Quick Recap card numbers repeat (should be 1–5); the desktop script box shows a quote instead of narration.

### Navigation
Home · Projects · Library · Jobs. Phone (< 600 dp): bottom nav. ≥ 600 dp: nav rail. ≥ 840 dp: project screens in three panes (list · preview · inspector). One set of composables for every width — no desktop-only layout (D-22). Jobs badge = actual Paused + Failed Needs-you count in amber first, else Running + Queued count in cyan, else none; no invented approval-wait rows. Compact rail also shows the count. Tested by JobRowsTest and StudioJobsLayoutTest. No avatar or account (D-06).

### Home
Continue (last project + its step) · Quick Recap card (movie → length → style → Start; "stops only for your approval") · Create: Recap · Music Video · Dubbing (disabled Planned) · Quick tools: existing standalone routes only, otherwise disabled Planned · Running jobs (actual progress, no unmeasured ETA).

### Recap — five steps
Step header "Voice · 3/5" (shown "အသံ · 3/5") with back / next (no tab bar: Burmese labels at a large font do not fit). Steps: Source · Story · Voice · Style · Export. Small preview on Story and Voice, big preview on Style.
- **Source:** movie card (length, size, sound), subtitle tracks (Extract), `.srt` import, summary, "Transcribe picked scenes" (confirm card: requests, today's usage), scenes with picks and target chips (as Phase 4).
- **Story:** block list — #, time, thumbnail, first line, chips ✓ Fits · +1.8 s · No voice · ⚠ Stale · Different voice. Tap → block editor: text (written / spoken toggle, speak-as line), clips (trim, add range, split, merge), take (▶, Speak, Record), Smart Fit choice. "Approve story" at the bottom.
- **Voice:** voice lock card ("Gemini 3.8 Flash TTS · Charon · locked", "Change voice…" says what becomes stale), Speak remaining (real requests → Jobs), takes per block. Voice & Audio for one block ("← Block 12"): tabs Speak · Record (teleprompter: current line big, next line muted) · Clean (polish, trim silences) · Clone (Labs only).
- **Style:** big live preview with draggable layers (blur mask, logo, watermark, title, border, background, captions) and "Exact frame"; Brand Kit chips; Looks; caption style; BGM, ducking, movie volume.
- **Export:** pre-flight list (✓ / ⚠ rows with "Fix →"), quality Auto / 720 / 1080 / 1440, measured progress only, Preview 540p / Export, Save to Gallery, Share, Publish Kit (title, description, hashtags, chapters, thumbnail; Copy each).

### Quick Recap
Wizard: movie → length chips (1 · 3 · 5 · 10 min) → style (Brand Kit / preset) → Start. Progress list with one row per stage (each a Jobs entry). Stops at the Story review (Approve / Edit); then voice and render continue as jobs.

### Library · Jobs
Library: Brand Kits · Voices · Lexicon · Music · Fonts · Presets; "Apply" copies into the project (D-39). Jobs: renders and AI jobs with project, actual progress, supported Pause / Resume / Cancel, failure reason + Retry; no remaining-quota promise.

### Music Video
Shot sheet per shot: storyboard text (edit / approve), prompt (copy), Ref A / B, Flow handoff (open Flow, import video), video versions, accept. The shots list keeps the §3 cards and badges. *As implemented (5B-12)*: five numbered sections on one sheet (1 Storyboard · 2 Prompt · 3 Ref A / B · 4 Flow handoff · 5 Videos · accept) with ← / → shot buttons; ≥ 840 dp: shots list (+ "All shots") · media preview · sheet inspector. At 840–1199 dp the shares are 24/32/44%; at ≥1200 dp they are 23/45/32% (excluding gaps/rail). The breakpoint uses window width. Voice also retains its block list alongside the selected inspector. Empty media remains an honest empty state; no sample frame is added. The classic tabs stay one release behind "Classic steps".

### Accessibility
Check every screen at font scale 1.0, 1.3 and 2.0 (the owner uses a large font); touch targets ≥ 48 dp; Burmese text wraps, never cut inside a syllable.

### UI v3 status vocabulary
Fits / Done / Accepted = green; duration mismatch / Script changed / Paused = amber; No voice = neutral; Running = cyan; Failed = red; Planned = muted. `StatusChip` owns this mapping. Model and voice labels are resolved data, never the illustrative names above. See UI v3 spec for components, transient visibility and separate thumbnail/overlay/preset/preflight/waveform tasks.
