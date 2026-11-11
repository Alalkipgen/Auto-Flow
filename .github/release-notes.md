Installs over v0.1.10 (tap **Update**), no data lost.

## What's new in v0.1.11 — export your music video

### Input → song file
- Song card → **သီချင်းဖိုင် ထည့်မယ်** → pick the mp3 / m4a / wav
- The song is copied into the project (`source/song.mp3`) and the **duration is filled in from the file**

### More → Export (new screen)
- Shows what goes into the video: shots, accepted videos (missing ones become "#NN — pending" cards), song, lyrics font
- **Preview 540p** (fast) or **Export 1080p** (full quality) — renders in the background, also with the screen off
- Progress % + **Cancel**; when done: **Open** (plays the video) and **Share…** (Telegram, Drive, Gallery apps…)
- Lyrics (each shot's Burmese line) are drawn at the bottom with the Settings font (Pyidaungsu or Noto Sans Myanmar)
- Low-battery warning below 20 % when not charging

### Also since v0.1.10
- Flow Handoff: one "✓ Accepted" badge (was shown twice)
- UI text uses the Burmese Settings font
- Settings → Tools: ffmpeg version line (libass / libx264 / aac)

## Test (Phase 1 exit)
1. Settings → Fonts: add Noto Sans Myanmar or Pyidaungsu (if not added yet)
2. Open a project with at least 1 accepted video → Input → **သီချင်းဖိုင် ထည့်မယ်** → pick the song
3. More → Export → **Preview 540p** → wait for "done ✓" → **Open**: music + Burmese lyrics?
4. **Export 1080p** → **Share…**

The Android APK bundles a GPL ffmpeg build (see the README).
