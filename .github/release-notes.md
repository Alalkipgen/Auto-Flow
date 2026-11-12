Installs over v0.1.13 (tap **Update**), no data lost.

## What's new in v0.1.14 — Phase 4 Movie Recap

### Dashboard → New Movie Recap
- Name + tick "I own this footage or have permission to use it" (rights notice on Dashboard, Movie Recap and Export)
- Opens the **Movie Recap** screen (phone: More → Movie Recap) with five cards; a locked card says what to do first

### 1 · Movie and 2 · Scenes
- **🎬 Import movie** (mp4, mkv, mov, webm) copies it into the project (needs about twice its size free while copying)
- **🔍 Find scenes** (More / Normal / Fewer), target length 1 / 3 / 5 / 10 min, tap scenes to pick them

### 3 · Script
- Gemini writes one Burmese sentence per picked scene from your summary and/or an .srt subtitle file (Plain or Commentary style), or **Write it myself**
- Edit each scene's text, then **Approve script**

### 4 · Voice
- **🎙 Recorded**: ● Record / ■ Stop per scene (asks for the microphone once), or import an audio file
- **🔊 Gemini TTS**: per scene or **Speak all**, voice chips (Charon, Kore, Puck, …)
- Editing a text marks its voice **⚠ Stale**; ▶ plays a take

### 5 · Fit check → Export
- One row per scene: ✓ fits / "+1.8 s: the scene runs on" / No voice / ⚠ Stale, and "Recap m:ss (target m:ss) · n of N voiced"
- Movie sound under the voice: Off / Low / Full · Looks: Mirror, Zoom 10 %, Fill frame, Warm · Burn captions on/off
- **Go to Export** → Preview 540p / Export 1080p as usual; captions are also saved as `exports/<name>.srt`

### Settings → 🔊 Test Gemini TTS (S5)
- Speaks one Burmese sentence with your key and shows which model answered; ▶ plays it

## Not done yet
- Phone tests: Movie Recap flow, S5 (Gemini TTS Burmese) and S7 (Gemini lyrics timing)
- Voice replication: needs a paid Gemini project, so it stays hidden
- A recap with many scenes opens the movie once per scene while rendering: memory on the phone still to check

The Android APK bundles a GPL ffmpeg build (see the README).
