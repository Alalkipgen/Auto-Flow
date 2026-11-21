# Auto Flow — UI Polish mockups (Phase 9)

Owner-supplied HTML mockups for Phase 9 (PLAN Phase 9, DESIGN §9), drawn at 412 dp in Burmese. They set the structure, not the pixels (D-102). Every project name, time, size, count and label in them is an example; the app shows only what it really has.

Source: the Phase 9 plan bundle `AutoFlow-Phase9-UI-Polish-Plan.zip` (2026-11-21, SHA-256 `f103aaa255441ae8b67753a902b408907b4cfa57c7fc3fbe4ec00c90250ecc2d`), `mockups/polish-1.png`, `polish-2.png` (3700 × 2330) and `current-c298c06.jpg`.

| File | Screen | What to take from it |
|---|---|---|
| `polish-1.webp` | Sheet 1/2: Home · Projects · Jobs · Settings | overview |
| `01_Home.webp` | 1 · ပင်မ | icon + label nav, Quick Recap hero, Continue with thumbnail + progress, Create grid |
| `02_Projects.webp` | 2 · ပရောဂျက်များ | filter chips with counts, thumbnails, mode + status chips, removed-projects row |
| `03_Jobs.webp` | 3 · အလုပ်များ | groups Running / Needs you / Finished, attention cards, one main button per card |
| `04_Settings.webp` | 4 · ဆက်တင်များ | grouped rows (icon, title, current value, ›) instead of "+" expanders |
| `polish-2.webp` | Sheet 2/2: Subtitles · Dubbing voice · Dubbing export · Voice & Audio | overview |
| `05_Subtitles_Editor.webp` | 5 · စာတန်းထိုး ပြင်မယ် | player with the live cue, timeline strip, view segmented control, cue cards, sticky Save |
| `06_Dubbing_Voice.webp` | 6 · Dubbing · အသံ | step bar + labels instead of the chip row, per-line fit chips, sticky main action |
| `07_Dubbing_Export.webp` | 7 · Dubbing · ထုတ်မယ် | output preview, checklist, Render package row |
| `08_Voice_Audio.webp` | 8 · အသံနှင့် Audio | task segmented control, voice card, parts list, format + main action bar |
| `current-c298c06.webp` | Before: the app at `c298c06` (CI `windows-screenshots`) | the problems listed in DESIGN §9 / PLAN Phase 9 |
| `icons.js` | 36 SVG icon paths (24 × 24) | source of the in-repo `ImageVector`s (D-98) |

The panels are crops of the two sheets with their captions. Like `../ui-v3/`, the images are compressed WebP (quality 80, longest side 1280 px); the original PNGs and the mockup HTML/CSS are not committed — they stay in the bundle above. The text-only repository connection cannot upload binary files, so `.github/workflows/ui-polish-mockups.yml` imports them once from a base64 manifest and checks each SHA-256:

```
af7ffac409bff314fdcc4562978bcba89c65e55353659eb0a1c0835e4dbcca99 01_Home.webp
7f9f6e28330baa7befe9377070eb6ceeb8caf94b07ff2ca0251edda31537b6e3 02_Projects.webp
447c44c94e798bd119bcfa7bafe317af15d1df0fe354ebfce5c1becdad37c580 03_Jobs.webp
3cd3be6665f2b2b8372cee03795340f1fd4c60a931d715b5229f05ea7bedc00e 04_Settings.webp
bb93412caf5b6550147ac370e082e09f5d86f4034458b8cd4e8403584a339393 05_Subtitles_Editor.webp
9b206f02c1b7c69ce4e0190355657e4deb7449c053445c1c9e6aa616d165ef23 06_Dubbing_Voice.webp
1338241214d3dc29bd1c0cf147d7f120b188f5c17fc34d1fbac52b3f48bdc999 07_Dubbing_Export.webp
ab387aacab757a378a2f33e9ab4e464319dac0bf79b465adcd098276d88160d3 08_Voice_Audio.webp
9d443180fbc24f55ec359441612f5d3f7cff7bf5ad03a8ac75d4cfa67f2c708c current-c298c06.webp
ced0b5831c4baa4aeb1181380181a1ba4f65bb36ea248dd31c1c68fe05b83836 polish-1.webp
392f408a48d0b89156157389c0f60f92a8e96a7af498d3f86827e426172f54fa polish-2.webp
```
