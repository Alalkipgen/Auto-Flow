# S1 — Gemini free-tier text: Burmese storyboard JSON

**Result: PASS** (2026-11-10, run #4 of `.github/workflows/spike-gemini.yml`)

Two-stage flow from `docs/specs/prompt-compiler.md` §1 with structured output
(`responseMimeType: application/json` + `responseJsonSchema`). Input: `spikes/fixtures/lyrics-sample.txt` (12 lines).
Outputs: `results/analysis.json` (SongAnalysis), `results/storyboard.json` (6 ShotSpecs). Raw responses and
response headers: `results/*.response.json`, `results/*.meta.txt`. Available models at run time: `results/models.txt`.

## Run
```bash
# From repository root, with GEMINI_API_KEY in env. Optional: S1_MODELS=a,b,c (fallback order)
bash ./gradlew -p spikes/s1-gemini-text run
```

## Findings
| Item | Observed |
|---|---|
| Model that succeeded | `gemini-3.5-flash` (free tier, `x-gemini-service-tier: standard`) |
| `gemini-2.5-flash` | HTTP 404: "no longer available to new users" (run #1) |
| `gemini-3.8-flash` | HTTP 503 "high demand" on every try; once a 180 s client timeout (runs #2–#4) |
| Latency | Stage A 12.6 s, Stage B 18.4 s (successful attempt only) |
| Transient errors | 503 on 5 of 9 calls to 3.5-flash; backoff 10/20/40 s recovered each time |
| Rate-limit headers | **None.** Success responses carry no `x-ratelimit-*` headers. Limits only show up in 429 bodies (`QuotaFailure` + `RetryInfo.retryDelay`) |
| JSON validity | Both stages parsed on the first try; schema followed; no retry needed |

## Burmese quality notes
- Lyrics were split correctly into VERSE / CHORUS / OUTRO, and the theme and arc are coherent and natural.
- `sceneMy` uses a formal, literary register (…သော, …၏, …ခြင်း), while the lyrics are colloquial. A prompt
  instruction for a colloquial register may be needed.
- Minor errors: "ထိန်းဟပ်" (should be ထင်ဟပ်), and the awkward phrase "မျက်မှောက်ပြုနေခြင်း". The storyboard screen needs human review/edit (as planned).
- `*En` fields are good English. The `HERO` token leaked into prose ("A young man HERO…"). The compiler should
  expect `{{HERO}}` tokens and instruct the model explicitly.

## Implications for Phase 1
- Model ids drift fast. Keep them in `config/models.json` with a fallback list and never hard-code them.
- GeminiTextProvider must retry 500/503 with backoff and fall back to the next model.
  The client timeout must be ≥ 300 s.
- The daily counter must be app-side: the API exposes no remaining-quota headers.
