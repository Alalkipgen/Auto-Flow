# Dubbing D-0 spikes — 2026-11-21

Spec: [specs/dubbing.md](../specs/dubbing.md) §7. This report covers the **CI part (c)** and the fake-provider
request counts. The phone parts — (a) S8 listening at 1.00–1.25× and (b) three 30–60 s clips with real TTS — are
**PHONE_PENDING** and do not block (D-85). Until the owner reports, Dubbing keeps the defaults: `maxVoiceSpeed` 1.0
(Q-42) and `maxSourceMs` 600 000 = 10 min (Q-41).

## (c) Mixing 50 / 150 / 300 cues — real ffmpeg

Test: `media/src/desktopTest/.../audio/DubMixSpikeTest.kt` (runs in the CI `check` job with
`AUTOFLOW_EXPECT_FFMPEG=1`; the `D0|…` rows are printed to the test log). One 1.2 s take every 2 s (300 cues = a
10 min video), realistic take paths (`takes/au-<16 hex>.wav`), each cue `aformat` → `adelay` on a silent bed, one
`amix … normalize=0`. Two ways:

- **direct** — one ffmpeg run with every cue as an input;
- **grouped** — stems of `dubbing.stemGroup` = 50 cues each (full length), then one run mixing the stems.

Both outputs are checked: the length equals the video (±40 ms) and **every cue starts within ±40 ms of its line**
(silencedetect onsets).

Numbers below were measured in the agent sandbox (2 vCPU, 4 GB, ffmpeg 7.0.2 static, Linux). The CI run uses the
same test; its timing rows are in the CI log and were not copied here (the agent cannot read CI logs).

| Cues | Video | Direct: wall · args · command chars | Grouped (50): wall (all runs) · longest command | TTS requests · time at 3 rpm |
| ---: | ---: | --- | --- | --- |
| 50 | 100 s | 0.48 s · 110 · 6 058 | 0.55 s · 6 058 | 50 · 16 min |
| 150 | 300 s | 3.7 s · 310 · 18 210 | 3.2 s · 6 173 | 150 · 50 min |
| 300 | 600 s | 20.5 s · 610 · **36 660** | **11.5 s** · 6 173 | 300 · 100 min |

## Findings

1. **No input limit hit**: ffmpeg mixed 300 inputs in one run, every cue on time. But the direct mix grows faster
   than linearly (×5.5 time for ×2 cues from 150 → 300), while grouped stays near linear.
2. **Windows command line**: the direct 300-cue command is 36 660 characters — above the Windows limit (32 767), so it
   would fail to start on Windows. Grouped commands stay about 6 200 characters at any length.
   → **DubMixer (D-3) pre-mixes groups of `stemGroup` = 50 cues into stems** (spec §6 option), always — not only
   above a threshold — so one code path is tested.
3. **The mix is not the bottleneck; TTS is.** One request per line (resume skips current takes): at the Gemini TTS pacing
   of 3 requests per minute (`RecapModel.TTS_RPM`, as Text → voice), 150 lines ≈ 50 min and 300 lines ≈ 100 min,
   before any 429 waits; the free-tier daily request limit is not known here (not measured live).
4. **v1 maximum length (Q-41)**: the CI part supports keeping **10 min** (`dubbing.maxSourceMs` 600 000) — the mix of
   a 10 min video takes about 12 s. Whether 10 min is practical depends on the TTS time above and the phone part (b);
   the owner may lower it later in `config/tools.json` without a code change.

## Not measured

- Phone render times for 50 / 150 / 300 cues (part c on the phone) and peak memory — PHONE_PENDING.
- Real TTS per-line time, 429 / retries, naturalness (part b) and the speed limit (part a, S8) — PHONE_PENDING; no
  live calls were made (no key in CI, live only with `-PliveAi`).
