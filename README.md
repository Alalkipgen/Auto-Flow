# Auto Flow

Personal AI music-video & movie-recap studio for Android (primary, D-22) and Windows.

Auto Flow does **not** generate video itself. It turns lyrics + duration + style into an
approval-gated pipeline — Burmese storyboard → numbered English shot prompts → reference
images — then hands each shot off to **Google Flow** (manual, in the browser), imports the
resulting clips, and assembles the final video with ffmpeg.

- Text / image / TTS: Gemini API **free tier** (user's own API key, stored locally)
- Video: Google Flow (AI Pro subscription) via **manual handoff** — no automation, no scraping
- Render: ffmpeg on Windows
- No backend, no accounts, no cloud sync engine. A project is a folder.

## Status
Pre-alpha. See `docs/PLAN.md`. Agents: read `AGENTS.md` first.

## Stack
Kotlin Multiplatform · Compose Multiplatform · kotlinx.serialization · Ktor · SQLDelight (index only) · ffmpeg

## License
MIT
