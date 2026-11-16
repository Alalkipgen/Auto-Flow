# 5A-8 focused continuation checkpoint

This is **not** “Studio v2 complete” or “5A-8 complete”. No merge, main push, force-push, release or release tag was requested or performed.
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
| QA checkpoint | `work/phase-5-combined-qa` | `work/phase-5-timed-playback-ui` (#156) |

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
- Retained live mode is honestly **muted pre-Looks source-picture/legacy-placement playback across all compiled clips**. Timed captions and
  active layer-ID readout use one output position. Native layer pixels/Looks/background are NOT drawn in this mode. Exact Frame uses the
  captured output position and the real native-v2 export compiler, with project-keyed async state.
- Real Linux libVLC/Compose test decodes source-time colour patches at mapped output seeks and verifies actual rendered pixels plus
  FIRST/SECOND/no-cue, pause/resume, stop/reopen, project change and screen disposal. It does not prove frame-accurate boundary sync,
  Windows native playback, text/blur raster equivalence or Android runtime behaviour.
- Android Media3 is the same engine, extracted behind the platform player boundary with TextureView underneath Compose, Activity lifecycle,
  source seek/rate and release handling. A compile pass is **not** device playback verification. No emulator/KVM/physical phone was available
  in the implementation sandbox; keep device lifecycle rows PHONE_PENDING.
- Exact Frame user reachability: 24 parameterized actual screen-container combinations (412/1000 x 760 viewport, font 1/1.3/2, bundled
  Pyidaungsu/default fallback, Studio Story/Classic). Real ancestor scroll -> displayed assertion -> pointer click -> result. The old direct
  semantics-action error test remains isolated, not counted as reachability proof.
- Original S10 five still legacy placement cases remain intact. Linux requires real ffmpeg. Platforms without ffmpeg now use explicit
  framework skips rather than silent-success returns. They do not prove live playback or native output-layer parity.

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

## Exact remaining implementation / acceptance handoff

1. **Implement native output-frame Compose renderer** as a cohesive preview component, NOT more responsibilities in RecapModel. Keep the
   legacy movie-mask placement view separate. Draw fitted/fill/zoom/mirror movie and supported Looks/background, native list-order layers
   with schema-specific rect behaviour and activation, captions last. TEXT stays at its list position. Preserve IMAGE/BACKGROUND resolution
   failure behaviour; never show “success” for missing required images. Do not approximate this by repeated Exact Frame calls.
2. **Extend S10 with actual rendered output** from that component and real native-v2 ffmpeg/libass: each native type, overlap order,
   before/inside/after activation, timed chunks, 16:9/9:16/1:1, supported Mirror/Zoom/Fill/background combinations, multi-clip mapping.
   Keep the original five cases. Measure pixels, not shared geometry. Specify geometry/timing tolerances; no bit-identical text/blur claim.
3. Add/extend **runtime** evidence for the native renderer over live frames. Current real source-picture playback evidence is intentionally
   narrower and must not be renamed full rendered playback parity. Windows installed-VLC runtime is not verified yet.
4. Run Android emulator/instrumentation where supported, especially Activity stop/start, paused seek, source/project change and release.
   Then preserve the single physical-phone/owner-PC acceptance session and all older pending rows in PHONE-QA.
5. Push new scoped Draft PRs above the current top (or updated main only if the stack has actually merged); no rewrite of this stack.
   Rerun all requested final-source gates, create a fresh **queue-complete** combined APK/EXE/checksums/downloads, and only then request
   the owner's one final consolidated QA run. This checkpoint APK does not discharge that last package requirement.
