# UI/UX Design

Reference mockup: `docs/mockups/autoflow-ui.html` (open in a browser).

## 1. Visual language
- Dark studio theme. Background `#0B1020`, panel `#131A2E`, card `#1A2340`, line `#263153`
- Text `#E6EAF5`, muted `#8A94B3`
- Accent purple `#8B5CF6` (primary actions), cyan `#22D3EE` (Flow / external), green `#22C55E` (accepted), amber `#F59E0B` (stale/warn)
- Fonts: UI Burmese = Pyidaungsu → Noto Sans Myanmar fallback; Latin = Inter/Segoe UI; prompt text = monospace
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
Form: lyrics (multiline, section markers `[Verse]`, `[Chorus]` optional), duration mm:ss (auto-read from mp3 if attached), song style, video style (chips + custom), mood/colour, ratio chips **16:9 / 9:16 / 1:1 / 4:5 / 21:9**, clip length **8s / 10s / 5s**, storyboard mode **Narrative / Performance / Abstract / Mixed**, character quick-add. Primary button: "Storyboard ထုတ်မယ်".

### Storyboard
Vertical list of cards: timecode (cyan, tabular), lyric line (muted), Burmese scene text (editable inline), camera · mood chips, version tag. Card actions: ✎ ပြင် · ↻ ပြန်ထုတ် · ✓ Approve. Header: "Approve all", storyboard version dropdown, regenerate-all (confirm dialog shows what becomes stale).

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
Bottom nav: Projects · Storyboard · Shots · Inbox · More (References, Flow Handoff, Timeline, Export, Settings). Same cards, single column. Every desktop screen exists on the phone, including Timeline and Export (render runs in the background with a notification). Primary actions: Approve, Copy Prompt, Edit, Regenerate, Attach video (from share sheet). Settings → long-press the version opens the Self-test (S6).

## 5. Interaction rules
- Every destructive or quota-consuming action has a confirm dialog that states what will be generated and what becomes stale.
- Regenerate never replaces; it adds a version. Version chooser shows side-by-side.
- Keyboard: `Ctrl+C` on a selected shot copies its prompt; `A` approves; `←/→` moves between shots.
- Empty states explain the next step in Burmese.

## 6. Copy (Burmese UI strings — keep these exact)
Approve = "အတည်ပြုမယ်" · Edit = "ပြင်မယ်" · Regenerate = "ပြန်ထုတ်မယ်" · Copy Prompt = "Prompt ကူးမယ်" · Needs Flow = "Flow မှာ လုပ်ရန်" · Stale = "ပြန်ထုတ်ရန် လိုသည်" · Accept video = "ဗွီဒီယို လက်ခံမယ်"
