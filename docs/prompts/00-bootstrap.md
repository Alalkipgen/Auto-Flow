# Task: Bootstrap repository

Read AGENTS.md, docs/ARCHITECTURE.md, docs/PLAN.md.

Create a Kotlin Multiplatform project with modules `:core`, `:ai`, `:media`, `:platform`, `:ui`, `:app-desktop`, `:app-android`
using Gradle version catalogs. Targets: `jvm` (desktop) and `android` for all shared modules.
- Kotlin + Compose Multiplatform stable versions; kotlinx.serialization, coroutines, Ktor client (CIO on JVM, OkHttp on Android), Koin, kotlin-test.
- `:app-desktop` opens a window with the Auto Flow title bar and empty sidebar matching docs/DESIGN.md §1–2.
- `:app-android` launches to an empty Projects screen.
- Add `config/models.json` from docs/specs/ai-providers.md.
- Add `.gitignore` (Gradle, IDE, `*.autoflow/`, `~/.autoflow`, spikes outputs).
- Add GitHub Actions: `./gradlew :core:check :ai:check :ui:desktopTest` on push.
Do not implement features. Branch: `work/phase-0-bootstrap`. Update docs/PLAN.md.
