# Auto Flow — UI Polish mockups (Phase 9)

Owner-supplied HTML mockups for Phase 9 (PLAN Phase 9, DESIGN §9): phone panels at 412 dp and desktop screens in a 1440 × 900 window (D-103), in Burmese. They set the structure, not the pixels (D-102). Every project name, time, size, count and label in them is an example; the app shows only what it really has.

Source: the Phase 9 plan bundle `AutoFlow-Phase9-UI-Polish-Plan.zip` (2026-11-21, SHA-256 `2e94076095daa5c51c15c54e33817d3b3ac8e61cb8e6fd441a23d8c432fad2c4`), `mockups/polish-1.png`, `polish-2.png` (3700 × 2330), `polish-3.png` (2050 × 5349), `current-c298c06.jpg` and `icons/`.

| File | Screen | What to take from it |
|---|---|---|
| `polish-1.webp` | Phone sheet 1/2: Home · Projects · Jobs · Settings | overview |
| `01_Home.webp` | 1 · ပင်မ | icon + label nav, Quick Recap hero, Continue with thumbnail + progress, Create grid |
| `02_Projects.webp` | 2 · ပရောဂျက်များ | filter chips with counts, thumbnails, mode + status chips, removed-projects row |
| `03_Jobs.webp` | 3 · အလုပ်များ | groups Running / Needs you / Finished, attention cards, one main button per card |
| `04_Settings.webp` | 4 · ဆက်တင်များ | grouped rows (icon, title, current value, ›) instead of "+" expanders |
| `polish-2.webp` | Phone sheet 2/2: Subtitles · Dubbing voice · Dubbing export · Voice & Audio | overview |
| `05_Subtitles_Editor.webp` | 5 · စာတန်းထိုး ပြင်မယ် | player with the live cue, timeline strip, view segmented control, cue cards, sticky Save |
| `06_Dubbing_Voice.webp` | 6 · Dubbing · အသံ | step bar + labels instead of the chip row, per-line fit chips, sticky main action |
| `07_Dubbing_Export.webp` | 7 · Dubbing · ထုတ်မယ် | output preview, checklist, Render package row |
| `08_Voice_Audio.webp` | 8 · အသံနှင့် Audio | task segmented control, voice card, parts list, format + main action bar |
| `polish-3.webp` | Desktop sheet (Windows · Linux), 1440 × 900 | overview |
| `D1_Home.webp` | D1 · ပင်မ | 244 dp sidebar, hero, jobs column, Continue cards, Create row |
| `D2_Projects.webp` | D2 · ပရောဂျက်များ | filter chips, thumbnail grid, 360 dp detail pane |
| `D3_Dubbing.webp` | D3 · Dubbing editor | 72 dp rail, numbered step bar, line table + video / waveform / voice column, hints left + buttons right |
| `D4_Subtitles.webp` | D4 · စာတန်းထိုး editor | rail, video + timeline, cue list pane, shortcut hints |
| `current-c298c06.webp` | Before: the app at `c298c06` (CI `windows-screenshots`) | the problems listed in DESIGN §9 / PLAN Phase 9 |
| [`icons/`](icons/MAPPING.md) | Phosphor Icons 2.1 (MIT): `phosphor/{regular,fill,duotone}/*.svg`, `phosphor/LICENSE`, `MAPPING.md`, `phosphor_to_kotlin.py` | source of the in-repo `ImageVector`s (D-98); the SVGs are copied byte for byte from the bundle |

The phone panels and D1–D4 are crops of the sheets with their captions. Like `../ui-v3/`, the images are compressed WebP (quality 80; phone longest side 1280 px, desktop 1440 px wide); the original PNGs and the mockup HTML/CSS are not committed — they stay in the bundle above. The text-only repository connection cannot upload binary files, so `.github/workflows/ui-polish-mockups.yml` imports them once from a base64 manifest and checks each SHA-256:

```
32595eb608cd502cb840d56fe0f598713a21f146de33fe261f6e40cf50504752 01_Home.webp
ece2a2f2f67ec5473728e492a039f925c8348bdaabe759a46855975e9e02d9b5 02_Projects.webp
fd595d7ba4110cf68a216293519b5238e950382d88594e911b61603138d2e5df 03_Jobs.webp
08c69a5df4175916c044388db1d31ba8beb2382c6013511699eb4af9e39225ca 04_Settings.webp
1f47094a2e3a304bc0954d4e1d5e18c289d96882075a79c6f0fc6ca56ca4f393 05_Subtitles_Editor.webp
2c84cf223076a47dcb3a1b8ebc6fe16d3b8844ca971ce656276bd1ae06cf20ca 06_Dubbing_Voice.webp
8b8322e4679a2cd253b6b32ee0ed0eb0ca487c2ec1f5ce96466184706856a10c 07_Dubbing_Export.webp
47f3b834c3b1e2c9dfe67ac2a1d3fce24819d065cff96e2b44e3270d1400424d 08_Voice_Audio.webp
107f957cd5fbf114af26dec63f80956f2b906324616942aa45bd49bfe6ca0d4f D1_Home.webp
8841e1f13a2325def236a1a92c99220ca06e0b5cd374d552ca9d6c4c54c1d6ca D2_Projects.webp
bf7d98ad5fd5f34ec95fa90cd4d3f7ce54a5acf815e6a85d8da0c151816901c2 D3_Dubbing.webp
73f1b343e8d95a83df1ce4956a1c9206b7766464876d8e085094d6b5d295bd49 D4_Subtitles.webp
9d443180fbc24f55ec359441612f5d3f7cff7bf5ad03a8ac75d4cfa67f2c708c current-c298c06.webp
3a91ec6d68503099aa5769eed8813e72db086f07889ce6c7e7957fb6df35463b polish-1.webp
6cf5005222d57dcaf77f6b54de744198a9e13752f7f5160c3196d66ac8127f15 polish-2.webp
a75e1e548a38365a69ca825da48cfc685b8e5fdca38abae1d341f84d6fe269ca polish-3.webp
```
