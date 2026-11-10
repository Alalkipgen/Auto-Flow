# S2 — Gemini free-tier image: one 9:16 reference image

**Result: FAIL / BLOCKED on free tier** (2026-11-10, runs #1–#4)

Request: `generateContent` with `responseModalities: [TEXT, IMAGE]` and `imageConfig.aspectRatio: "9:16"`,
English prompt in `src/main/kotlin/Main.kt`. Raw responses: `results/*.response.json`, `results/*.meta.txt`.

## Run
```bash
# From repository root, with GEMINI_API_KEY in env. Optional: S2_MODELS=a,b,c
bash ./gradlew -p spikes/s2-gemini-image run
```

## Findings
Every image-capable model listed for this key returned **HTTP 429 RESOURCE_EXHAUSTED with `limit: 0`** for
free-tier requests per minute, requests per day, and input tokens per minute (the quota is zero, not used up):

`gemini-2.5-flash-image`, `gemini-3-pro-image-preview`, `gemini-3-pro-image`, `nano-banana-pro-preview`,
`gemini-3.1-flash-image-preview`, `gemini-3.1-flash-image`, `gemini-3.1-flash-lite-image`, `gemini-nano-banana-2.1`.
`gemini-2.0-flash-preview-image-generation` returned 404 (retired).

The `RetryInfo.retryDelay` (~14 h) points to the daily reset, but with `limit: 0` a retry won't help.
No image was produced, so output size is unknown.

## Options (see docs/PLAN.md Q-03)
1. Enable billing (paid tier) for image models only and keep text on the free tier.
2. Make reference images part of the manual Google Flow handoff (AI Pro), and import them like video clips.
3. Make `ImageProvider` optional: shots can proceed with no reference image, or with a user-supplied one.
