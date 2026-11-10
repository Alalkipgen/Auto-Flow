# S4 — Burmese rendering spike

Status: **PASS via libass (B, primary) + Compose/Skia PNG overlay (C, fallback)** — Linux and Windows CI, 2026-11-10. Plain `drawtext` (A) is **not** used. No main modules are modified. Native-reader sign-off on the frames below is still welcome.

## Result — three overlay methods (Q-02)

Test: 5 s black 1080x1920 (9:16) clip, frame grabbed at t = 2.5 s. Line 1 is the required phrase `ကျွန်တော် မိုးရွာနေတဲ့ ညမှာ တစ်ယောက်တည်း`; line 2 `မြန်မာ စာလုံးဆင့် ကမ္ဘာ ကြွက် ညှိ ဉာဏ်` stresses stacked consonants (ကမ္ဘာ), medials (ကျွ, ကြွ, ရွ), pre-base ေ reordering, and ညှိ / ဉာဏ်. Fonts: Noto Sans Myanmar Regular (notofonts) and Pyidaungsu 2.5.3 Regular (MCF); URLs in `.github/workflows/spike-s4.yml`.

Run: `.github/workflows/spike-s4.yml` → `methods/run.sh` (A/B/C) + `./gradlew -p spikes/s4-burmese-rendering run` with `S4_PNG_OUT`/`S4_FONTS_DIR` (C's PNGs). Raw frames, `.ass` files, `ffmpeg-info.txt` and `status.txt` per OS: `evidence/methods/{linux,windows}/`. Contact sheets (rows: Windows Noto, Windows Pyidaungsu, Linux Noto, Linux Pyidaungsu): `evidence/s4-A-drawtext.png`, `evidence/s4-B-libass.png`, `evidence/s4-C-skia.png`.

| | FFmpeg build | `ffmpeg -buildconf` |
|---|---|---|
| Linux | 8.0.1-3ubuntu2 (ubuntu-latest apt) | libass, libfreetype, libfribidi, **libharfbuzz**, libfontconfig |
| Windows | 9.0.2 full_build (gyan.dev) | libass, libfreetype, libfribidi, **libharfbuzz**, fontconfig |

| Method | Command (per font; relative paths, run in the output dir) | Linux 8.0.1 | Windows 9.0.2 |
|---|---|---|---|
| **A** drawtext + fontfile | `-vf "drawtext=fontfile=font.ttf:textfile=line1.txt:fontcolor=white:fontsize=64:x=(w-text_w)/2:y=820,drawtext=…line2.txt…:y=940"` | ✗ broken: ေ not reordered (နတေ့), ကမ္ဘာ shows a visible virama instead of a stack, medials misplaced | ✓ correct shaping (both fonts); no wrapping — line 1 at 64 px overflows 1080 px |
| **B** libass `.ass` + `ass=` | `-vf "ass=lyrics-<font>.ass:fontsdir=fonts"` (`methods/lyrics.template.ass`: 1080x1920 PlayRes, `\pos`, karaoke `\k`) | ✓ correct, both fonts; karaoke highlight works | ✓ pixel-identical to Linux |
| **C** Compose/Skia PNG + overlay | `-f lavfi -i color… -loop 1 -i text-<font>.png -filter_complex "[0][1]overlay=0:0:shortest=1,format=yuv420p"` (PNG from `ImageComposeScene(1080,1920)` + `BasicText`) | ✓ correct, both fonts | ✓ same as Linux (≤0.03 % pixels differ, antialiasing) |

Why A differs: `vf_drawtext.c` in FFmpeg ≤ 8.0 hard-codes `hb_buffer_set_script(HB_SCRIPT_LATIN)` before shaping; release/9.0 removed it and lets `hb_buffer_guess_segment_properties` detect Myanmar (language is still forced to `en`, one script per drawtext call). So A depends on the user's FFmpeg version and still lacks wrapping, karaoke and per-word styling — rejected.

### Recommendation
- **Primary: B (libass).** Shaping is via HarfBuzz inside libass (independent of drawtext's script bug and identical on both OSes), and ASS gives wrapping, `\pos`/alignment, outline/shadow, fades and karaoke `\k` — what the lyric overlay needs. Requirements for the app's ffmpeg check: `ass` filter present and `--enable-libass` in `-buildconf`; ship the chosen `.ttf` in the project/app fonts dir and pass `fontsdir=` (never rely on system fonts).
- **Fallback: C (Skia PNG + overlay).** Same shaping engine as the UI preview (WYSIWYG), works with any ffmpeg that has `overlay`; use when `ass` is missing or for styled title cards. Cost: one PNG per text state (karaoke = many PNGs), so keep it for static/simple text.
- Size calibration: libass scales a font so that its ascender+descender (`usWinAscent+usWinDescent`) equals the ASS `Fontsize`, so the glyph em is `Fontsize × unitsPerEm / (winAscent+winDescent)`: Noto Sans Myanmar 1000/2184 (ASS 64 → ≈29 px em), Pyidaungsu 2048/3530 (64 → ≈37 px). This matches the measured text widths in B vs C (C uses 44 px). The render compiler must convert `sizePx` per font with this factor so export matches the Compose preview.
- Not yet tested: mixed Burmese/English in one line (libass itemises by script, so expected OK), and line wrapping of long Burmese lines (no spaces between words in many lyrics → needs explicit breaks or `\N`).

## Earlier observations (drawtext only)


### Reproduce (original Compose window + drawtext.sh)

Requires JDK 21, two local TTF font files, Compose desktop dependencies from the existing version catalog, and FFmpeg with the `drawtext` filter. Fonts are not bundled here. Pyidaungsu 2.5.3 Regular was downloaded from the [MCF repository](https://github.com/mcfnlp/Pyidaungsu/tree/ff1cd8dfe8c451244329d7a6181c83e769226cac/Pyidaungsu2.5.3/Release); Noto Sans Myanmar Regular came from Amazon Linux's `google-noto-sans-myanmar-fonts` package. Check licenses before distributing fonts.

```bash
export JAVA_HOME=/path/to/jdk21
export NOTO_MYANMAR_FONT=/path/to/NotoSansMyanmar-Regular.ttf
export PYIDAUNGSU_FONT=/path/to/Pyidaungsu-2.5.3_Regular.ttf
# From repository root; use gradlew.bat instead on Windows.
bash ./gradlew -p spikes/s4-burmese-rendering run
# Bash, on a host with drawtext:
FFMPEG=/path/to/ffmpeg bash spikes/s4-burmese-rendering/drawtext.sh
```

The script writes five-second MPEG-4 clips and extracts a frame for each font under `out/` (ignored). It copies each font to a temporary fixed path to avoid FFmpeg filter path escaping. The first line is the required phrase; the second additionally exercises medials and stacked characters.

### Observed — 2026-11-10, Linux only

- Compose Multiplatform 1.12.2, JDK 21, Xvfb with software rendering: both fonts render the sample; `evidence/compose-linux.png`. The extra `ကမ္ဘာ` sample appears stacked. Native Myanmar-reader and Windows verification are still needed.
- FFmpeg 5.1.9 (Amazon Linux `ffmpeg-free`, `drawtext` with FreeType and FriBidi, no HarfBuzz listed in its build configuration): files were produced, but the stacked `ကမ္ဘာ` renders visibly differently from Compose, with separate/mispositioned glyphs. See `evidence/noto-drawtext-ffmpeg-5.1.png` and `evidence/pyidaungsu-drawtext-ffmpeg-5.1.png`. Thus this build does **not** pass the Burmese shaping criterion. Do not generalize this finding to all FFmpeg builds.
- Next: test a HarfBuzz-enabled FFmpeg build and verify output with a Burmese reader; repeat on Windows. Do not mark S4 passed until both rendering paths meet the criterion.

#### Follow-up: FFmpeg 7.1.1 and HarfBuzz

A local, minimal FFmpeg 7.1.1 build with FreeType, FriBidi and HarfBuzz 7.0.0 was tested using the same script and fonts. The unmodified build still shows the same stacked-character problem (`*-7.1-stock.png`). In this source version `vf_drawtext.c:shape_text_hb` explicitly sets `HB_SCRIPT_LATIN` and language `en` before shaping. As a diagnostic only, changing those two settings to `HB_SCRIPT_MYANMAR` and `my` produces visually different, apparently stacked glyphs in this sample (`*-7.1-experimental.png`). This is a local source modification, not a supported solution: it forces one script for all text and was not tested with mixed scripts or on Windows. Native-reader verification is still required. It does not justify marking S4 passed or choosing a production FFmpeg fork.
