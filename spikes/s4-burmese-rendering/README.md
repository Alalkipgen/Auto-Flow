# S4 — Burmese rendering spike

Status: **in progress / exit criterion not met**. No main modules are modified.

## Reproduce

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

## Observed — 2026-11-10, Linux only

- Compose Multiplatform 1.12.2, JDK 21, Xvfb with software rendering: both fonts render the sample; `evidence/compose-linux.png`. The extra `ကမ္ဘာ` sample appears stacked. Native Myanmar-reader and Windows verification are still needed.
- FFmpeg 5.1.9 (Amazon Linux `ffmpeg-free`, `drawtext` with FreeType and FriBidi, no HarfBuzz listed in its build configuration): files were produced, but the stacked `ကမ္ဘာ` renders visibly differently from Compose, with separate/mispositioned glyphs. See `evidence/noto-drawtext-ffmpeg-5.1.png` and `evidence/pyidaungsu-drawtext-ffmpeg-5.1.png`. Thus this build does **not** pass the Burmese shaping criterion. Do not generalize this finding to all FFmpeg builds.
- Next: test a HarfBuzz-enabled FFmpeg build and verify output with a Burmese reader; repeat on Windows. Do not mark S4 passed until both rendering paths meet the criterion.
