# Gemini live-validation checkpoint — 2026-11-19

**Not measured.** No securely provisioned `GEMINI_API_KEY` was available to the local opt-in process. The models-list preflight was not sent, so there is no HTTP status or eligibility finding for the configured models. No live calls or uploads were made. CI used fakes; no credentials or owner media were sent.

| Flow (synthetic input only) | Planned repeated runs | Queue · prep · upload · first/full response · retry · validate · save · UI | Requests / retries / 429 / bytes | Output quality |
| --- | --- | --- | --- | --- |
| Transcribe, 5 picked scenes (10 if available) | ≥2 per size | Not measured | No live calls made | Real per-scene lines not measured |
| Script, 1-minute target | ≥2 | Not measured | No live calls made | Not measured |
| TTS, 10 blocks | ≥2 | Not measured | No live calls made | Not measured |

## Confirmed from source and fake tests

- A foreground shadow row and the runner's persisted row could represent the same request as Running and Queued at once. The runner now has one displayed row; equivalent active inputs are claimed once. That is a row/queue correctness change, **not** a measured reduction in Gemini latency.
- “1 lines from 5 scenes” counts distinct stored scene entries and parsed lines. A scene entry may contain zero lines. The screenshot cannot distinguish little speech, transcription quality or provider truncation; no claim about the owner's audio is made.
- `Diagnostics → Last run timings` now lists observed monotonic portions, not estimated stage times. Queue time is absent for a run recovered in a new process; upload/first byte require the HTTP hooks to observe them. Stage timings can overlap and should not be summed as elapsed time.
- No changes to model selection, batching, concurrency, transport, output, or pacing. The provider bottleneck, before/after latency, retry incidence and impact of free-tier limits are unknown.

## Next live run

Provision a fresh key locally without logging it; opt in and check the models list first against `config/models.json`. On auth failure record only the status/reason and stop. If enabled, run the real Source/Story/Voice paths on `qa-fixture/AutoFlow-5A8-synthetic-backup.zip` or generated synthetic clips/text, twice each. Capture only numeric diagnostics and validate per-scene line counts and saved clips. Respect the returned `retryDelay` and stop on rate-limit failure rather than looping. Do not use owner media, echo keys, or submit prompts/results to CI.
