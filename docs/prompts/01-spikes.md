# Task: Phase 0 spikes S1–S4

For each spike create `spikes/s<N>-<slug>/` as a standalone Gradle module excluded from the main build, with a README.md recording results per docs/PLAN.md Phase 0.
- S1: `GEMINI_API_KEY` from env. Send sample Burmese lyrics (use `spikes/fixtures/lyrics-sample.txt`, create a 12-line placeholder song) through the two-stage calls in docs/specs/prompt-compiler.md §1 with structured output. Save raw responses. Note rate-limit headers.
- S2: generate one 9:16 image from a sample English prompt; save PNG; note limits.
- S3: Compose Desktop window playing `spikes/fixtures/sample.mp4` with VLCJ; play/pause/seek; measure bundled size.
- S4: render the string "မိုးရွာနေတဲ့ ညမှာ တစ်ယောက်တည်း" in Compose Desktop (Pyidaungsu, Noto Sans Myanmar) and via `ffmpeg drawtext` onto a 5s black clip. Save screenshots to the spike folder.
Report pass/fail per exit criteria. Do not touch main modules. Branch: `work/phase-0-spikes`.
