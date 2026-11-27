# Follow-up verification and copy-only recovery

Scope: follow-up A–D to D-62, not a repeat audit or a release. Keep the existing F01–F16 commits.
No live AI calls are required by any command below. Test fixtures are synthetic.

## A — one effective spoken-text boundary

`LibraryStore.lexicon` publishes a snapshot after a successful save/load. Production `uiModule` supplies that same
flow to `RecapModel` and `RecapFrames`. The latter is shared by Exact frames and ExportModel (both preview/export).
`SpokenText.of` remains the single rule: nonblank `speakMy`, else `textMy`, Library replacements, trim. A request
captures that string and `RecapRules.textHash(string)` before suspension, with its original project/block/lock/style.
A later Library/text/project change cannot rehash or redirect the answer. Existing takes remain history and become
stale when the effective string changes; job reconciliation and render selection use the same snapshot rules.
Caption chunking/SRT/burn-in continue to use written `textMy`, not pronunciation text.

`LibrarySpeechIntegrationTest` uses the real Koin app module, a test filesystem/Library store, the real persisted
desktop runner and RecapJobProcessor, and a fake provider only at the TTS API boundary (plus fake OS/render ports).
It covers single speech, Speak All, exact hashes, live stale/Export refresh, re-speaking stale items, chosen history,
all clips/layers and native v2 preview/export/Exact-frame graphs; another case edits text/lexicon and switches project
while a response is in flight. Existing RecapVoiceLockTest and async/job tests remain required.
No new lexicon-editing UI or filesystem watcher is introduced: saved Library edits publish immediately; external
edits to library.json publish on Library reload without restarting the application.

## B — TEXT in drawing order, captions last

The original multi-layer compiler also failed to relay `prev = next`, producing an unconnected filter output.
After isolating that one-line prerequisite, real ffmpeg reproduced identical pixels for TEXT→IMAGE and IMAGE→TEXT
at 16:9, 9:16 and 1:1. This was not just a source-inspection claim.

The repair creates one indexed ASS sidecar/filter per TEXT at its list position. RenderJobRunner writes all sidecars
for movie renders and Exact frames. Real captions/placeholder labels remain in the final ASS pass. The existing
rect/font-size/timing rules are unchanged. BACKGROUND is still composed per clip *behind* the fitted picture,
regardless of list position; it does not participate as a foreground covering layer.

`TextOrderFfmpegTest` renders an opaque overlapping IMAGE in both orders at all three ratios, checks changed decoded
pixels and the absence/presence of white glyphs, rect placement, inactive times, actual captions last, Exact-frame
output and 1500 ms rendered duration. D-63 records the cost: one libass/font context per TEXT layer, no new dependency.
`LayerMatrixFfmpegTest`, `LayerFfmpegRenderTest`, audio and native-v2 real-render tests remain required.

## C — historical external-harness exceptions

| Old test | Current contract | Replacement/equivalent coverage |
| --- | --- | --- |
| `changingVoiceMustNotCreateAnEmptyLockedModel` | Initial **null** lets the provider resolve its configured default. Never send blank as an explicit model. Persist the answered model, then keep it; busy pauses, no model fallback. | Adapted `changingVoiceAllowsDefaultResolutionThenLocksResolvedModel`: first null, answered lock/provenance/selected voice, second exact lock. Existing `RecapVoiceLockTest` and Gemini provider tests retain busy/PAUSED and one-model coverage. |
| `exportMustKeepAllV2LayersAndClips` directly called `toV1` | The intentionally lossy v1 compatibility/display adapter cannot express all v2 clips/layers. Production renders must call the **native v2 overload**. | Adapted test calls native `RecapRender.graph`. `LibrarySpeechIntegrationTest` traces both actual production ExportModel callers through RecapFrames; `RecapV2FfmpegRenderTest` renders every clip, BORDER and chosen older take at all three ratios, checks 3000 ms duration, movie pixels and 1 kHz chosen audio rather than the newer 440 Hz take. |
| `concurrentSaveMustNotReturnSuccessForDifferentBytes` waited for B to write before releasing A | Saves serialize under a per-folder JVM + OS lock; a stale writer receives Conflict. B cannot write while A owns the folder. | Adapted fixture and existing `ProjectSaveConcurrencyTest` hold A after its temp write, wait for B's **actual blocked lock attempt**, then release A; both finish, A's full serialized result equals committed bytes and B is Conflict. Existing many-thread and child-JVM OS-lock tests remain, with committed-byte checking on the successful cross-process save. |

The external harness lives outside source. The downloadable evidence contains its full adaptation diff, fresh XML
and logs. Additional compatibility-only edits: suspend restore calls explicitly expect unsafe/incomplete archives
to be refused (security assertions retained); removed `AudioFilters.trimSilence()` plan emission is labeled as a
changed implementation, not falsely counted as a silence-trim regression; actual trim is exercised by repository
AudioFfmpegTest. New compiler TEXT sidecars are written when emitting external render plans. No test is deleted or
disabled and no assertion requires the obsolete initial-model contract. A passing adapted harness is not a claim
that the historical unadapted source now prints 26/26.

## D — recover an already-lossy migration WITHOUT replacing the current project

Prerequisites: a full JDK for this repository, original `project.v1.bak.json`, enough space for a complete separate
copy, and all required source/artifact files. **Close Auto Flow and all other writers first.** This offline tool does
not acquire a source lock (which could itself create/change source files). It hashes the non-cache source before
and after copying and refuses a detected change; it is not a transactional snapshot of actively changing writers.

1. Make an independent normal backup before recovery. Keep the current v2 project and its original v1 backup.
2. Pick an existing destination parent and a **new, non-existing** `Recovered.autoflow` path outside the source.
   Do not create the destination folder yourself. Do not use the original folder or an existing empty folder.
3. From the repository, run (replace only the two example project paths):

```bash
./gradlew :platform:recoverV1Copy \
  -PrecoverySource=/projects/Current.autoflow \
  -PrecoveryDestination=/projects/Recovered.autoflow
```

4. Success is `RECOVERED COPY`, with verified block/take/approval counts. The destination contains:
   - migrated v2 `project.json` derived only from the original v1 backup;
   - byte-identical `project.v1.bak.json`;
   - the source's current manifest as `project.before-v1-recovery.json` (when present);
   - every non-cache source file, including **all** immutable artifacts/sidecars, even newer/unreferenced ones;
   - only an additive migrated story artifact. The original source is never a migration target.
5. Open the **recovered copy**, compare script/clip ranges, saved takes, voice provenance, approvals, fit choices,
   sources, style, picture/audio settings and references. Compare valuable later edits against the untouched current
   project; the tool intentionally does **not** merge or guess those edits. The historical project id is retained,
   like a normal backup restore; the new folder is the separate copy. Do not treat it as an automatic replacement.

Validation before success: original content hashes and copied bytes; transitive artifact sidecar identities, content
hashes, JSON and required project-relative files; v1 script → v2 story; all take indices/counts, provenance and hashes;
approval and other mapped fields. Typed artifact pointers must also have a valid id and the expected kind: the
ArtifactId serializer alone does not enforce this. No configured model is guessed when absent. Missing/corrupt backup/artifact/movie,
unsafe references/links, unsupported fields, orphan take/fit indices or any destination collision produce
`RECOVERY REFUSED`/nonzero exit, with no published destination. Only the tool's unpublished staging directory is
cleaned. Missing data must be recovered from another genuine backup, never invented. `.cache` is rebuildable and
not copied. No recovery UI, automatic overwrite or owner-edits merge workflow is added.

`V1ProjectCopyTest` exercises a genuinely lossy synthetic current v2 manifest with later valuable edits; compares
all source/backup/artifact hashes, migrated fields, takes/approvals/references; rejects missing/corrupt/non-v1 backup,
unknown fields, missing/corrupt artifacts/movie, orphan indices, collisions, nested destination and escaping refs.
Malformed/wrong-kind pointers in both the recap and the preserved root fields are explicitly refused.
Linux also exercises a symlink destination; Windows may require privileges for symlink creation.

## Fresh final gates

Inspect JAVA_HOME, `java -version`, `javac -version`, `./gradlew -q javaToolchains`, required Android SDK and actual
ffmpeg/ffprobe before building. Do not change SDK/Kotlin/Java versions to work around a missing compiler.
Run the gates against the **actual final remote commit**, not an equivalent earlier local commit:

```bash
AUTOFLOW_EXPECT_FFMPEG=1 ./gradlew check \
  --rerun-tasks --no-build-cache --continue --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx1600m \
  -Pkotlin.compiler.execution.strategy=in-process
./gradlew :app-android:assembleDebug --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx1600m -Pkotlin.compiler.execution.strategy=in-process
./gradlew :app-desktop:createDistributable --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx1600m -Pkotlin.compiler.execution.strategy=in-process
bash tools/desktop-smoke.sh
```

Run builds sequentially in a shared checkout; parallel Gradle invocations may read a jar while another rewrites it.
Inspect fresh JUnit XML, not only BUILD SUCCESSFUL. AUTOFLOW_EXPECT_FFMPEG makes unavailable real media tools fatal.
Archive exact SHA/tree, environment, XML/logs, real render commands/measurements and packaged-smoke evidence.

Android assembly or a reconstructed executor test is **not** an emulator/device lifecycle pass. Without a suitable
device, process death/reopen/resume, project switching in flight, cancellation/checkpoint and actual WorkManager chain/
pacing execution stay **UNVERIFIED** (Android lifecycle; S10 is Compose/ffmpeg preview parity). User force-stop differs from OS process death. Local saved-output
reconciliation does not promise exactly-once remote TTS requests across crashes; no provider idempotency is available.
