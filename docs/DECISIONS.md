# Decisions (ADR-lite). Do not reopen without a new entry.

D-01 KMP + Compose Multiplatform over Flutter — owner's Kotlin expertise, YFT code reuse.
D-02 Project-as-folder with immutable content-hash artifacts; SQLite only as index.
D-03 Stale detection via recorded inputs, not status cascades.
D-04 Video generation is manual via Google Flow. No automation of Google UIs — ToS/account risk and brittleness.
D-05 Gemini API free tier (AI Studio key) for text/image/TTS. AI Pro subscription is NOT an API credential; it only applies to Flow/AI Studio UI.
D-06 No backend, accounts, billing, or custom sync. Syncthing/folder copy is the sync method.
D-07 Desktop video playback: VLCJ first. Fallback if spike S3 fails: ffmpeg-extracted thumbnails + "open externally", playback deferred.
D-08 Android does not render. ffmpeg-kit is retired; Media3 Transformer only for preview concat if ever needed.
D-09 Voice replication is an optional, capability-gated feature. Default narration path is user recording; standard TTS second.
D-10 Model names/quotas/credit costs live in `config/models.json`, never in code.
D-11 Koin for DI (KMP). kotlinx.serialization for all persisted models.
D-12 Burmese text for humans, English for models; separate fields, never auto-translated silently.
D-13 Toolchain pinned as one set in `gradle/libs.versions.toml` only: Kotlin 2.4.21, Compose Multiplatform 1.12.2, AGP 9.3.3, Gradle 9.7.1, kotlinx.serialization 1.12.0, coroutines 1.11.0, Ktor 3.6.1, Koin 4.2.2 (newest set inside Kotlin 2.4.21's tested Gradle/AGP range); shared modules use `com.android.kotlin.multiplatform.library` + `jvm("desktop")`; JVM target 17, minSdk 26, compileSdk 37; no Material3 (custom theme per DESIGN.md); upgrade versions together as a set.
