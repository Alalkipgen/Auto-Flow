Installs over v0.4.1 — tap **Update**, no data lost.

## Auto Flow v0.4.2 — fixes (Phase 5 Step 0)

- **Blur boxes with soft edges work again** — a feathered BLUR layer made ffmpeg refuse the whole export (`geq` has no `luma` option) and its fade was fully transparent. Now the box fades in from its edges; checked by rendering with a real ffmpeg in CI.
- **Desktop app starts** — the packaged desktop app stopped at "Could not create instance for RecapModel" (missing Main dispatcher). Fixed, and CI now launches the packaged app on every push.
- **Voice lock (Burmese TTS)** — Speak uses exactly one model: the locked one, or before the first take the first Burmese-capable model, which the first take then locks. A busy model (429 / 503) **pauses** the job (Resume) instead of failing; a missing model fails; it never switches models. `gemini-2.5-flash-preview-tts` is no longer offered for Burmese (Google lists Burmese only for Gemini 3.8 Flash TTS). Fixed a voice chosen before the first take being sent with an empty model name.
