Installs over v0.1.11 (tap **Update**), no data lost.

## What's new in v0.1.12 — Phase 2 first part

### Export → Save to Gallery
- After a render is done: **Save to Gallery** copies the mp4 to `Movies/AutoFlow` (Gallery / Photos see it; it stays even if Auto Flow is uninstalled)

### Share to Auto Flow → Inbox
- In Gallery, Files or Telegram: **Share** a video or image → **Auto Flow** → it lands in the open project's **Inbox**, ready to attach to a shot

### Input → Characters (Character Bible)
- **+ Add character**: token (CHAR1…), Burmese name, English description
- Write `{{CHAR1}}` in a scene → the prompt gets the English description. After changing a description, **↻ Recompile** the shots that use it

### Flow Handoff
- **Flow credits — today / this month** panel, with the log (✕ removes a wrong line)
- After each video import: "Credits used?" → **20 (Fast)** / **100 (Quality)** / custom / Skip
- **Batch sheet**: tick shots → **Export batch sheet (HTML)** → one page with every prompt + reference image + **Copy prompt** buttons → **Open sheet ↗** / **Share sheet**

### Shots → video versions
- Shot detail lists every imported video (v1, v2… with Draft / Final) → **▶ Preview** / **Use this version**

### Also
- References / Shots buttons wrap on the phone (Approve is always visible)
- The phone stepper names the screen you are on
- Desktop: Export renders on Windows too

## Not done yet (questions in PLAN.md)
- Lyrics timing (Q-11), beat detection (Q-12), prompt ⚠ Stale after a character change (Q-10)

The Android APK bundles a GPL ffmpeg build (see the README).
