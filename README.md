# Auto Flow

Personal AI music-video & movie-recap studio for Android (primary, D-22) and Windows.

Auto Flow does **not** generate video itself. It turns lyrics + duration + style into an
approval-gated pipeline — Burmese storyboard → numbered English shot prompts → reference
images — then hands each shot off to **Google Flow** (manual, in the browser), imports the
resulting clips, and assembles the final video with ffmpeg.

- Text / image / TTS: Gemini API **free tier** (user's own API key, stored locally)
- Video: Google Flow (AI Pro subscription) via **manual handoff** — no automation, no scraping
- Render: ffmpeg — bundled in the Android app (D-23), located on Windows
- No backend, no accounts, no cloud sync engine. A project is a folder.

## Status
Pre-alpha. See `docs/PLAN.md`. Agents: read `AGENTS.md` first.

## Stack
Kotlin Multiplatform · Compose Multiplatform · kotlinx.serialization · Ktor · SQLDelight (index only) · ffmpeg

## License
MIT

The app icons are Phosphor Icons 2.1 (MIT, © 2023 Phosphor Icons), copied into the source as Compose vectors (`ui/.../icons/AfIcons.kt`); the licence text ships in both apps as `licenses/PHOSPHOR-ICONS.txt`.

The Android APK bundles a GPL-2.0-or-later ffmpeg build (with libx264), run as a separate program; its pinned sources and build script are in `tools/ffmpeg-android/`, and the APK carries its license texts and build info.

Desktop builds include VLCJ 4.8.3 (GPLv3). The combined desktop binary is distributed under GPLv3, not MIT-only; see `app-desktop/src/main/resources/licenses/`. The application source remains available under MIT. CI supplies the matching application source and pinned VLCJ/JNA sources alongside the unsigned test installer. Native VLC is **not** bundled: playback requires a separate 64-bit VLC 3.0.x installation; still / Exact frame do not require VLC.
