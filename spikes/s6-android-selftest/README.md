# S6 — Android on-device feasibility (Self-test)

Status: **PASS — the bundled ffmpeg runs all three renders on the owner's phone (D-23 primary path)**, release v0.1.1, 2026-11-11. The Media3 Transformer fallback was not needed (it stays in the app as fallback code only, D-23).

Unlike S1–S4 this is not a throwaway module: the Self-test ships inside the app (`app-android/.../selftest/`, `:media` `selftest/`) and can be re-run from Settings → long-press the version. v0.1.0 crashed on **Run all** before any render (Android 16 refused a foreground service of type "none", see PLAN changelog v0.1.1).

## Device
Xiaomi 2312DRA50C (Redmi Note 13 Pro 5G, "garnet"), Snapdragon 7s Gen 2 (SM7435), 8 cores, Android 16 (SDK 36, patch 2026-07-01); RAM 7.3 GB (2.36 GB available at start); app memory class 256 MB (large 512 MB); 3.3 GB free app storage; battery 96 %, 36.2 °C, thermal status none before and after.

## Binary
- `nativeLibraryDir` …/lib/arm64, `libffmpeg.so` 24.0 MB and `libffprobe.so` 23.8 MB, both `canExecute=true`; SELinux context `u:r:untrusted_app:s0:…` — exec from the native library dir is allowed (no EACCES / seccomp kill).
- `ffmpeg -version` 9.0.2 (our CI build, `tools/ffmpeg-android/`): `ass` (libass) ✓, `xfade` ✓, `libx264` ✓, `aac` ✓ → overlay backend LIBASS; 477 filters, 176 encoders.
- Foreground service type granted: `mediaProcessing`.

## Renders (16 s output each; fixtures: two 8 s 1080×1920 clips, 20 s mp3, S4 Burmese `.ass`, Noto Sans Myanmar)

| Render | Engine | Result | Wall time | Peak RSS (ffmpeg maxrss) | Output |
|---|---|---|---|---|---|
| Concat 2 clips → 540p (preview: ultrafast, crf 23) | ffmpeg | ok | 2.4 s | 221 MB | 4.8 MB, h264 540×960 + aac |
| + Burmese `.ass` overlay (libass) → 540p | ffmpeg | ok | 2.4 s | 221 MB | 5.0 MB, h264 540×960 + aac |
| + mp3 mix → 1080p (export: slow, crf 18, AAC 192k) | ffmpeg | ok | 14.3 s | 810 MB | 12.0 MB, h264 1080×1920 + aac |

Whole run 21.6 s. Frames were shown on the Self-test screen.

## Conclusions for Stage 2
- Android renders with the bundled ffmpeg through the shared RenderGraph compiler (D-23); Media3 is not on the render path.
- Speed: preview ≈ 6.7× real time at 540p, export ≈ 1.1× real time at 1080p `slow` — a 3-minute song exports in roughly 3 minutes.
- Memory: the 1080p `slow` export peaks at ≈ 810 MB in the ffmpeg process (outside the app heap, but visible to Android's low-memory killer). Renders run one at a time in a foreground worker; if a long export is ever killed, the first lever is `-threads` / a faster x264 preset for that phone, not a different engine.
