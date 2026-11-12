Installs over v0.1.12 (tap **Update**), no data lost.

## What's new in v0.1.13 — Phase 2 finished

### Input → Lyrics timing (tap to sync)
- **Sync lyrics (tap)**: the song plays, the current line is big → tap **⤵ Line starts** at each line, **⏹ Last line ends** at the end
- **Undo** rewinds 3 s, **Restart**, **Save** / **Cancel**, **Clear timings**. Choosing a new song clears old timings

### ✨ Suggest timings (Gemini)
- On the Lyrics timing card: Gemini listens to the song and suggests a start/end for every line (asks first; uses your key and quota)
- Check the result with tap-to-sync if a line is off

### Export → lyrics line by line
- With saved timings each lyric line shows at its own time (not for the whole shot). Export says "Lyrics: N synced lines", or warns when the timings are outdated

### Storyboard → ♩ Snap cuts to beat
- Switch on: Auto Flow finds the beat of the song once, then moves each cut to the nearest beat (within ±250 ms, a shot never gets shorter than 2 s). The message shows how many cuts moved and the BPM

### Characters → ⚠ Stale
- Changing a character description marks the shots that use it **⚠ Stale** → **↻ Recompile**

## Not done yet
- Gemini TTS for Burmese (S5) and Gemini lyrics timing quality (S7) need a test on the phone
- Phase 4 Movie Recap waits for a decision (Q-13 in PLAN.md)

The Android APK bundles a GPL ffmpeg build (see the README).
