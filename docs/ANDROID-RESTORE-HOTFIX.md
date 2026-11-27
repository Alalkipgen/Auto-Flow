# Android restore hotfix — scoped evidence

## Cause and compatible I/O

The owner's existing physical trace is from stable `0.4.2`/code `559`/source `4e95a7b`, Xiaomi
`2312DRA50C` Android 16/SDK36 arm64. `ProjectZip.checkProject` used `Files.readString(Path)`
(added in Android 36.1), so SDK36 threw a linkage `NoSuchMethodError`, outside `catch(Exception)`.
This did not show backup creation or ffmpeg failure. No agent device reproduction is claimed.

The replacement is `Files.newBufferedReader(path, Charsets.UTF_8).use { it.readText() }`, available
since API26, the actual minSdk. Like the old JDK read, malformed/unmappable UTF-8 throws instead
of inserting replacement characters. `File.readText()`/plain `decodeToString()` would be too permissive.
The existing 64 MiB project-file guard, required JSON fields, ZIP limits, traversal/duplicate-name
checks, completeness manifest, fresh staging, collision-safe promotion and cleanup remain unchanged.
No production catch was broadened; cancellation still propagates through `AndroidProjectBackup`.
No dependency, minSdk or toolchain change; core-library desugaring is not enabled in this app.

Targeted Android-reachable shared-I/O scan found one adjacent minSdk issue:
`ByteArrayOutputStream.toString(Charset)` in manifest reading does not exist in the real API26 SDK.
Use its `toString(UTF_8.name())` overload instead, preserving the manifest's existing decoding behavior.
Other restore `java.nio.file`/stream calls resolve in API26. `JvmFs.read` already uses compatible
`Files.readAllBytes`. The other `Files.readString`/`writeString` calls are in `V1ProjectCopy`, reached
only by the desktop-only explicit `RecoverV1Copy` CLI; not rewritten as if they were Android UI calls.
This is a targeted restore/shared-I/O check, not another full audit.

Android availability reference: https://developer.android.com/reference/java/nio/file/Files

## Executable gates (not historical evidence)

- `:platform:desktopTest --tests '*ProjectZipTest*'`: all existing safety/collision/rejection/cancellation
  cases plus Burmese names/text byte-for-byte and invalid/truncated/surrogate UTF-8 rejection.
- `:ui:desktopTest --tests '*QaRecapFixtureTest*'`: real production write/restore + valid synthetic
  native-v2 project, preserved layers, audio, timeline and approvals; ffmpeg remains required in CI.
- `python3 tools/check-restore-android-api.py "$ANDROID_HOME/platforms/android-26/android.jar" platform/build/classes/kotlin/desktop/main`:
  reads actual compiled restore method descriptors and resolves them against the real minimum SDK,
  including inherited I/O methods. Before the adjacent fix it fails on `toString(Charset)`.
  This is API-linkage evidence, **not Android runtime verification**.
- `:app-android:lintDebug :app-android:assembleDebugAndroidTest`: builds the executable platform-only
  instrumentation runner with no new dependency. `tools/android-restore-regression.sh` generates the
  same three-project synthetic backup and runs the real `AndroidProjectBackup.restore` on Android,
  plus Burmese data, corrupt/missing JSON, malformed/incomplete manifest, collisions, preserved
  existing bytes and cancellation cleanup. It refuses installation unless
  `AUTOFLOW_DISPOSABLE_ANDROID=1`; use only a disposable emulator/device, never the owner's data.

Sandbox has no connected device or `/dev/kvm`; instrumentation execution and owner's fixed-build
retest remain pending. Building an instrumentation APK does not close either pending item.
Fresh executed outcomes/source/artifact checksums are recorded on the PR, not assumed here.

## Delivery

Separate Draft PR above #161. The existing CI stable signing secrets, application ID and versionCode
= run number are retained; only code >559 with the same certificate is an update-installable candidate.
Artifacts have actual-binary `.sha256`, exact source/run manifests and 30-day retention. Never replace
stable signing with a random key, uninstall or clear data. No merge/main push/tag/release.
