# S3 — Compose Desktop video playback (VLCJ)

**Result: PASS — 7/7 on Linux, Windows (full VLC), and Windows (trimmed VLC)** (2026-11-10, `.github/workflows/spike-s3.yml`)

Approach: VLCJ 4.8.3 `CallbackVideoSurface` (RV32), with each frame copied into a Skia image and drawn by Compose `Image`
inside a 9:16 box. No heavyweight AWT canvas (`SwingPanel`), so the video has no z-order or overlay problems with Compose UI.
Test clip: 10 s, 540x960 H.264/AAC, 30 fps (generated in CI with ffmpeg `testsrc2`).

## Run
```bash
# Manual (window with Play/Pause, -2s, +2s); put an mp4 at spikes/fixtures/sample.mp4 or set S3_VIDEO
bash ./gradlew -p spikes/s3-vlcj-playback run
# Windows with a portable VLC folder instead of an installed VLC:
S3_VLC_DIR='C:\path\to\vlc-3.0.x' ./gradlew -p spikes/s3-vlcj-playback run
# Automated checks: S3_AUTO=1 (writes results/<tag>/report.txt + screenshots)
```

## Results (`results/<os>/report.txt`)
| Check | Linux (VLC 3.0.23, Xvfb) | Windows Server 2025 (VLC 3.0.24 zip) | Windows, trimmed VLC |
|---|---|---|---|
| First frame after `play()` | 794 ms | 465 ms | 395 ms |
| Playback rate | 30.0 fps | 29.9 fps | 30.0 fps |
| 9:16 frame | 540x960 ✔ | 540x960 ✔ | 540x960 ✔ |
| Pause (time frozen, pixels unchanged) | ✔ | ✔ | ✔ |
| Seek while paused → 5000 ms | exact, new frame in 320 ms | exact, 322 ms | exact, 321 ms |
| Resume / backward seek while playing | ✔ / ✔ | ✔ / ✔ | ✔ / ✔ |

Screenshots: `results/*/0N-*.png` (window) and `*-frame.png` (decoded frame). The GitHub Windows runner has a
1024x768 screen, so the taskbar covers the control row in window shots. This is a runner limitation, not an app issue.

## Bundle size impact (Windows)
| Part | Size |
|---|---|
| JARs: vlcj 4.8.3 + vlcj-natives + jna-jpms + jna-platform-jpms | ~3.7 MB |
| VLC 3.0.24 win64 zip (download) | 79 MB |
| Full VLC folder | 190 MB (plugins 140 MB; codec 43 MB, access 23 MB, gui 19 MB) |
| Trimmed VLC (libvlc + libvlccore + access, audio_*, codec, demux, packetizer, video_chroma, video_output, logger, misc) | **95 MB**, passes all checks |

The trimmed set could shrink further (e.g., drop unused codecs inside `codec/`). Packaging is tracked as PLAN Q-05.

## Findings for Phase 1
- **Buffer padding:** VLC pads the RV32 buffer (540x960 → 544x962). Read the real size from `video().videoDimension()` on
  the `videoOutput` event and crop with `rowBytes = bufferWidth * 4`, or the 9:16 frame gets 4 px/2 px of edge padding.
- **Redisplays while paused:** VLC re-sends the same picture about 14x per second while paused. Skip the Skia conversion
  when the buffer CRC is unchanged, to save CPU.
- **Time label after a paused seek:** `timeChanged` does not fire on a seek while paused. The UI must read `status().time()` after
  `setTime()` (the Linux screenshot shows a stale 0:04.6 label next to the correct 5.000 frame).
- Each frame currently allocates a new Skia image (about 2 MB at 30 fps). That is fine for preview. Reuse a bitmap if profiling shows GC pressure.
- No audio device on CI runners, so VLC logs ALSA errors on Linux. Playback still works. Audio was not checked by this spike.
