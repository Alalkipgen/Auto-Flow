# 5A-8 continuation — implementation and combined QA handoff

Native output-frame implementation and its automated evidence are now present. This is **not** “Studio v2 complete” or physical-owner acceptance. No merge, main push, force-push, release or release tag was requested or performed.
The owner still plans **one** final phone-QA session, not an install per Draft PR.

## Verified starting point and stack

Historical starting point: #149, `feat/recap-preview-parity`, `c638fec95e47a446f89085e400cc1053ce330c69`.
The original #141–#149 stack above repair #140 remains untouched. New Draft PRs are individually stacked:

| PR | Head | Base |
| --- | --- | --- |
| #150 | `work/phase-5-exact-frame-reachability` | `feat/recap-preview-parity` (#149) |
| #151 | `work/phase-5-native-layers` | `work/phase-5-exact-frame-reachability` (#150) |
| #152 | `work/phase-5-layer-controls` | `work/phase-5-native-layers` (#151) |
| #153 | `work/phase-5-output-timeline` | `work/phase-5-layer-controls` (#152) |
| #154 | `work/phase-5-desktop-playback` | `work/phase-5-output-timeline` (#153) |
| #155 | `work/phase-5-playback-clock` | `work/phase-5-desktop-playback` (#154) |
| #156 | `work/phase-5-timed-playback-ui` | `work/phase-5-playback-clock` (#155) |
| #157 checkpoint (`c03c0f7ea02b68b27beb6212143217575fcbc9e2`) | `work/phase-5-combined-qa` | `work/phase-5-timed-playback-ui` (#156) |
| #158 | `work/phase-5-native-output-frame` | `work/phase-5-combined-qa` (#157) |
| #159 | `work/phase-5-native-live-preview` | `work/phase-5-native-output-frame` (#158) |
| #160 | `work/phase-5-native-s10-runtime` | `work/phase-5-native-live-preview` (#159) |
| Final QA handoff | `work/phase-5-final-native-qa` | `work/phase-5-native-s10-runtime` (#160) |

Exact final source identity belongs in the CI artifact's `BUILD.txt` / installer `MANIFEST.txt`, not a self-referential SHA in this source file.
Check the final head's CI, not earlier green runs. Historical 855 tests are not this continuation's fresh count.

## What is implemented and what evidence means

- Native `recap.layers` transactions/editor: all five schema types, stable-ID project/revision-bound drafts, finite geometry/time validation,
  rect controls only where applicable, drag/pinch and mouse drag/wheel, type-specific parameters, order, image import via ArtifactStore,
  save/cancel/remove. Removing a reference never removes the immutable image. Real disk save/reopen/conflict/project-switch tests and
  serialized-project Compose gesture tests supplement each other; they are not just mocked setters.
- Native-v2 output timeline shares export layout, take-file presence, stable block IDs, clip ordinals, Fit/slowdown, EOF hold and SPEED
  narration timing. Common clock tests and a real ffmpeg/libass/audio SPEED test exercise mapping. No v1 adapter is used.
- Desktop VLCJ 4.8.3 (GPLv3, compatible with the combined desktop distribution under GPLv3; source files remain MIT): separately installed
  64-bit VLC **3.0.x**, existing JNA/JNA-platform 5.17.0 reused, no JPMS duplicate. See D-69 and the shipped notices/source companion.
  Settings path + discovery, lazy unavailable fallback, decoded callback pixels below Compose, real play/pause/seek/rate and resource release.
  No native VLC is copied into the APK, app image or EXE. An already-loaded VLC path change requires restart. No owner software is downloaded.
- The live mode is now **muted native output-frame playback across all compiled clips**, not a renamed first-clip/source-picture loop.
  `NativeFramePainter` applies legacy movie masks before Mirror/Zoom, fit/Fill and native/legacy background, Warm, ordered native
  IMAGE/TEXT/BLUR/BORDER, watermark and actual captions last. BACKGROUND is behind the fitted picture and BORDER ignores rect.
  Missing IMAGE/BACKGROUND-image (even inactive) refuses the output proxy; read-only project/revision asset results cannot attach to
  another project. Source buffers/artifacts stay immutable. Exact Frame remains the native-v2 export compiler at the captured output cursor.
  The clearly labelled legacy movie-mask placement still remains separate and intentionally pre-Looks.
- Real Linux libVLC/Compose tests decode changing source-time colour patches at mapped output seeks, verify native IMAGE/BORDER pixels
  and actual FIRST/SECOND/blank caption ink in landscape/portrait/square, including transitions while playing, pause/resume, stop/reopen,
  project change and screen disposal. They found/fixed copied RV32 alpha and paused-seek mutable-clock capture bugs. Polling is nominally
  40ms, not proof of frame-accurate boundary sync, Windows native playback or Android runtime behaviour.
- Android Media3 is the same engine, extracted behind the platform player boundary with TextureView underneath Compose, Activity lifecycle,
  source seek/rate and release handling. A compile pass is **not** device playback verification. No emulator/KVM/physical phone was available
  in the implementation sandbox; keep device lifecycle rows PHONE_PENDING.
- Exact Frame user reachability: 24 parameterized actual screen-container combinations (412/1000 x 760 viewport, font 1/1.3/2, bundled
  Pyidaungsu/default fallback, Studio Story/Classic). Real ancestor scroll -> displayed assertion -> pointer click -> result. The old direct
  semantics-action error test remains isolated, not counted as reachability proof.
- Original S10 five still legacy placement cases remain intact. Linux requires real ffmpeg. Platforms without ffmpeg now use explicit
  framework skips rather than silent-success returns. New `NativeOutputParityTest` separately measures 8 configurations × 7 output positions
  (56 actual Compose/ffmpeg/libass frame pairs), 72 changed-pixel bounds comparisons and overlap occlusion. Geometry tolerance ±2% of
  output dimension, RGB MAE <=0.08; observed targeted maximum edge difference 0.3704%, MAE 0.07686. It includes all types/order/timing,
  caption changes/blank, 3 ratios, Mirror/Zoom/Fill/Warm/background combinations and 3 clips/2 stable blocks. Static parity is not live proof.

## Fresh final-source gates

From the actual pushed remote head with a clean tree (do not reset someone else's newer work):

```sh
AUTOFLOW_EXPECT_FFMPEG=1 ./gradlew check \
  --rerun-tasks --no-build-cache --continue --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx1600m \
  -Pkotlin.compiler.execution.strategy=in-process
./gradlew :app-android:assembleDebug :app-desktop:createDistributable
bash tools/desktop-smoke.sh
```

Use a full JDK and Android SDK, not a JRE. Linux CI's full `check` requires ffmpeg; separate `desktop-playback` CI separately installs VLC3
on the disposable runner and requires both actual decoder and Compose runtime tests. Ordinary no-VLC test jobs explicitly skip those native
runtime tests and are not playback proof. `tools/ci-test-summary.py` publishes executed/failure/skip counts and uploads XML, not inflated totals.
The Windows installer job retains the real unsigned EXE only after install/installed-launch/uninstall succeeds. The no-VLC packaged startup
smoke is separate from installed-VLC runtime tests. No physical-owner acceptance status is changed by either.

## QA package and deterministic fixtures

- APK artifact: `autoflow-debug-<source7>.apk`, own `.sha256`, exact `BUILD.txt`, defaults `FEATURES.json`, preserved `PHONE-QA.md`, and
  `qa-fixture/AutoFlow-5A8-synthetic-backup.zip` + `EXPECTED.txt` + actual-file `SHA256SUMS`.
- EXE artifact: `AutoFlow-0.4.2-<source7>-unsigned-setup.exe`, own `.sha256`, `MANIFEST.txt`, flags/checklist, license notices and matching
  application + pinned VLCJ/JNA source companion. SmartScreen warning expected: unsigned test installer, not a release.
- Both binary artifacts: 30-day Actions retention; download page gives exact expiry. GitHub sign-in may be required. An Actions ZIP digest
  is not an APK/EXE checksum. Report actual binary notices/sidecars, do not substitute the artifact ZIP hash.
- Generate fixtures offline with `./gradlew :ui:qaRecapFixture -PqaFixtureDir=<fresh-empty-path>`. They share the real player test's valid WAV,
  native story, source-time colour patches, three clips/two stable blocks and five layer types. Android uses the existing safe backup restore.
  They contain no keys/accounts or live provider outputs. Read EXPECTED.txt; content/timing are deterministic, byte-identical encoders/IDs/revisions are not promised.

## Remaining acceptance / limitations (not missing renderer code)

- **PHONE_PENDING:** Android emulator/instrumentation was not run: no emulator/adb or `/dev/kvm` in the implementation sandbox.
  Android compilation/JVM seams are not device playback/lifecycle verification. Physical phone must check real TextureView capture,
  caption/layer seek transitions, background/foreground, project switches, cleanup, touch gestures, export consistency and performance.
- **PHONE_PENDING / OWNER_PC_PENDING:** real owner Windows PC, compatible installed VLC 3.0.x, project/media switches and playback;
  installer CI does not prove Windows native movie playback. Physical phone/PC acceptance and every older pending PHONE-QA row remain open.
- **One combined QA session:** install only the final-source APK whose run gates are green, restore disposable fixtures, keep real projects
  backed up, execute PHONE-QA including large-font Exact Frame reachability and edit/save/reopen/conflict/cancel. Do not request an interim
  phone install per PR. EXE is unsigned: record SmartScreen/signing/update behaviour without deleting user data.
- Interactive output is a muted, bounded 640-edge proxy, not an audio workstation. Gaussian blur, YUV/Warm conversion, outlines/text and
  unavailable named TEXT-font fallback are not bit-identical across Compose/ffmpeg/libass/platforms. Geometry/order/timing are measured,
  but 24fps S10 samples (41.7ms) are away from activation edges and nominal 40ms playback polling is not a frame-perfect sync guarantee.
  Use same-time native Exact Frame/export as the final raster truth; never claim the legacy placement still is a full output.
- No known agreed renderer/editor/player implementation item remains. Final remote-source gates and binary identity are **per-run facts**:
  if any final run is red or an artifact missing, treat that gate/package as pending, not completed by the targeted checkpoint tests above.
  The final owner-facing handoff must supply the actual pushed SHA, executed/failure/skip counts, run links and actual-file checksums.
