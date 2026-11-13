# Spec: Movie Recap mode (Phase 4, D-33)

A recap is a short video that retells a movie: the owner picks scenes from a movie file, a Burmese narration script
is written for them (by Gemini or by hand), the narration is recorded or spoken by TTS, and the scenes are cut to
the narration with captions. Everything runs offline except the two optional Gemini calls (script, TTS).

## 1. Rights (shown in the UI)
- Dashboard → **New Movie Recap** shows: "Use only footage you own or have permission to use. Recaps of other
  people's movies can be blocked, muted or demonetized by YouTube / Facebook / TikTok." and a required tick
  **☐ I own this footage or have permission to use it**. Without the tick the project is not created.
- The tick is kept as `recap.rightsConfirmed`; the Movie Recap screen and Export repeat the one-line notice.
- The app never uploads the movie (only text and, for TTS, the script text go to Gemini).

## 2. Project data (`project.recap`, only in `MOVIE_RECAP` projects; no schema bump — the field defaults to null)
```json
"recap": {
  "rightsConfirmed": true,
  "movie": { "file": "source/movie.mp4", "sizeBytes": 734003200, "durationMs": 6912000,
             "width": 1920, "height": 1080, "hasAudio": true },
  "targetMs": 300000,
  "scenes": [ { "startMs": 0, "endMs": 4200, "picked": false } ],
  "sceneThreshold": 10,
  "subtitles": "source/subtitles.srt",
  "style": "PLAIN",
  "script": "rs_…",
  "scriptApproved": false,
  "voiceSource": "RECORDED",
  "ttsVoice": "Charon",
  "takes": [ { "segment": 0, "audio": "au_…", "source": "RECORDED", "durationMs": 3900, "textHash": "…" } ],
  "looks": ["ZOOM"],
  "movieVolume": 0.2,
  "burnCaptions": true
}
```
- The movie is copied (streamed, never loaded into memory) to `source/movie.<ext>` (mp4, mkv, mov, webm, m4v). It is
  not an artifact: it is too large to hash. A new movie replaces the old one (moved to `.cache/replaced/`), clears
  the scenes, the script pointer and the takes. During the import the phone needs about twice the movie's size free.
- The typed summary lives in `source/summary.txt`, the subtitles (optional) in `source/subtitles.srt`.
- `script` is an `rs` artifact (new kind "recap script", §4). Every change (generate, edit, write by hand) writes a
  new one and clears `scriptApproved`.
- `takes`: the newest narration per segment (`au` artifacts, WAV or M4A). A take is **stale** when `textHash` ≠
  SHA-256 (12 hex) of the segment's current text; stale takes are not used by the render and show ⚠ Stale.

## 3. Scenes
- **Find scenes** runs `ffmpeg -i movie -an -sn -vf scale=320:-2,scdet=threshold=<t> -f null -` and reads the
  `lavfi.scd.time` values from the log (threshold 10 by default; "More scenes" = 6, "Fewer scenes" = 16).
  Progress = the `time=` of ffmpeg's status line ÷ the movie length.
- Cut times → scenes `[cut_i, cut_i+1)`. Scenes shorter than 1.0 s are merged into the previous one; scenes longer
  than 20 s are split into equal parts of ≤ 20 s (a recap needs short pieces).
- Scene picker: thumbnail (the frame at the scene's middle, `.cache/thumbs/<startMs>.jpg`, made on demand), time,
  length, ☐ pick. Header: "Picked N scenes · 3:12 of target 5:00" with target chips **1 · 3 · 5 · 10 min**.
  Picking is free; the target is a guide (amber when the picked total is more than 20 % over or under).
- Re-finding scenes keeps picks whose start time survives exactly; it clears the script approval.

## 4. Script (`rs` artifact)
```json
{ "schemaVersion": 1, "source": "gemini" | "manual", "style": "PLAIN" | "COMMENTARY",
  "segments": [ { "startMs": 12000, "endMs": 16200, "textMy": "…" } ] }
```
- One segment per picked scene, in movie order; `startMs`/`endMs` are the scene's (movie time). A script whose
  segments do not equal the current picked scenes is **outdated** (Script card warns; approval is not possible).
- Input: the typed summary (Burmese or English) and/or the `.srt`. **✨ Write script (Gemini)** sends the summary,
  and for each picked scene its time, length and the subtitle lines inside it (≤ 600 characters per scene), and
  asks for Burmese narration that takes about the scene's length to read (≈ 4 Burmese syllables per second).
  Style chips: **Plain** (neutral storytelling) · **Commentary** (adds short reactions/opinions, recap-channel
  style). Output schema `{segments:[{textMy}]}` with exactly one item per scene; a wrong count is retried once,
  then fails ("invalid_output"), like the storyboard.
- **✎ Write it myself** creates a script with empty segments. Each segment is editable (Save writes a new `rs`).
- **✓ Approve script** is allowed only for a current (not outdated) script with no blank segment (RecapRules).

## 5. Voice (`VoiceSource`)
- **Recorded** (default, D-09): per segment **● Record** / **■ Stop** (Android `MediaRecorder` AAC/M4A, asks for
  the microphone permission the first time; desktop `javax.sound` 16-bit mono WAV), or **Import file** (any audio).
- **TTS**: **🔊 Speak all (Gemini TTS)** / per segment, voice chips from the prebuilt list (default Charon,
  "Informative"), model from `config/models.json` `tts.candidates` (first that answers). Uses the owner's key.
- **Replicated**: needs a paid-tier Gemini project (Gemini docs, 2026-11) → hidden while
  `tts.replication` is not `"available"` in models.json (D-34).
- Voice needs an approved script (RecapRules). Takes are kept in `artifacts/` (never deleted); the newest wins.
- ▶ plays a take (Android). Settings → **Test Gemini TTS (S5)** speaks one Burmese sentence and shows the model.

## 6. Fit check and render
- Output timeline: one clip per segment, in order. Clip length = max(scene length, take length + 0.4 s); when the
  take is longer, the clip keeps playing the movie past the scene end (never past the movie end; then the last
  frame holds). The take starts 0.2 s into its clip.
- Fit row per segment: ✓ fits · "+1.8 s: the scene runs on" (amber) · "No voice" (grey; the clip is the scene with
  movie sound only) · "⚠ Stale". Footer: "Recap 4:51 (target 5:00) · 12 of 14 voiced".
- Audio: the movie's sound at `movieVolume` (chips Off 0 · Low 0.2 · Full 1.0) mixed with the takes.
- Captions: each voiced segment's text over its take's time, bottom, Burmese Settings font (same rules as lyrics);
  burned in when `burnCaptions`, and always written as `exports/<name>.srt` next to the mp4.
  A long text is cut into chunks of at most two lines (line width as for lyrics), shown one after the other over
  the take, each for its share of the text; breaks go at a sentence end (`။ . ! ?`), then a clause mark (`၊ , ; :`),
  then a space, then a Burmese syllable boundary — never inside a syllable. The `.srt` has the same chunks.
- Looks (any combination, applied to the movie picture): **Mirror** (`hflip`), **Zoom 10 %** (center crop 90 %),
  **Fill frame** (crop to the output ratio instead of black bars), **Warm** (`eq` saturation 1.15, contrast 1.05).
- Rendering uses the Export screen (Preview 540p / Export 1080p, Save to Gallery, Share), ratio from the project.
- Render is allowed with an approved, current script and at least one current take (RecapRules).

## 7. Screens
- Dashboard: **New Movie Recap** (name, rights tick) → opens **Movie Recap**.
- **Movie Recap** (one scrolling screen, sidebar "Mode → Movie Recap", phone More → Movie Recap) with numbered
  cards: 1 Movie · 2 Scenes · 3 Script · 4 Voice · 5 Fit check → "Go to Export". Locked cards say what to do first.
- In a recap project the Music Video screens (Input … Timeline) are locked: "This is a Movie Recap project — use
  Movie Recap"; Export opens when RecapRules allows a render.
