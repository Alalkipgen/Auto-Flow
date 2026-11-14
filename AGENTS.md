# AGENTS.md — rules for AI coding agents working on Auto Flow

You are working on a personal-use desktop+mobile app. The sole user is the repo owner.
Optimize for: correctness, small diffs, testability without API keys, and never losing user data.

## 0. Read order (every session)
1. this file
2. docs/PLAN.md — find the current phase and open task
3. docs/ARCHITECTURE.md
4. the spec(s) in docs/specs/ that the task references
5. docs/DECISIONS.md — do not re-litigate settled decisions

## 1. Hard rules
- **Never delete or mutate an artifact file** under `artifacts/`. Artifacts are immutable and content-hash named. Only `project.json` pointers change.
- **Never automate Google Flow** (no browser automation, no cookie reuse, no reverse-engineered endpoints). Flow is a manual handoff. See docs/specs/flow-handoff.md.
- **Never hardcode** model names, quotas, credit costs, or prices in logic. They live in `config/models.json` and are treated as data that will change.
- **API keys** are read from platform secure storage only (Windows DPAPI via `:platform` SecureStorage, D-19 / Android Keystore). Never in code, logs, test fixtures, or `project.json`.
- **All AI calls go through `:ai` interfaces.** UI and `:core` must compile and run with `Fake*Provider` implementations. CI runs with fakes only.
- **Burmese vs English:** human-facing text lives in fields with the `My` suffix (camelCase, e.g. `sceneMy`, `lyricsMy`). Anything sent to an image/video model lives in fields with the `En` suffix (e.g. `subjectEn`, `descriptionEn`). Never mix, never auto-translate silently. JSON keys in `project.json` and all artifacts use the same camelCase names.
- **Approval gates are enforced in `:core` (WorkflowRules), not in UI.** UI only reflects state.
- Writes to `project.json` are atomic (write temp → fsync → rename).
- No new third-party dependency without a line in docs/DECISIONS.md explaining why.

## 2. Workflow
- Branch per task: `work/phase-<n>-<slug>`. Never commit directly to `main`.
- One task = one PR. Keep PRs under ~400 changed lines where possible; split otherwise.
- Before coding: restate the task, list files you will touch, list the spec sections you rely on. If the spec is ambiguous, **stop and write the question into docs/PLAN.md under "Open Questions"** instead of guessing.
- After coding: update docs/PLAN.md task status, add/adjust tests, run `./gradlew check`.
- Commit messages: `phase1(core): add ArtifactStore with content-hash naming`.

## 3. Code conventions
- Kotlin, KMP. Shared code in `commonMain`; platform code via `expect/actual` only in `:media` and `:platform`.
- Compose Multiplatform for UI. No Android-only UI libs in shared modules.
- Serialization: kotlinx.serialization. All persisted models have `@SerialName` and a `schemaVersion`.
- Errors: sealed `Result`-style types at module boundaries; no exceptions across module boundaries.
- Coroutines + Flow for async. No RxJava.
- DI: Koin. No Hilt (Android-only).
- Tests: `commonTest` with kotlin-test. Golden tests for the prompt compiler live in `core/src/commonTest/resources/golden/`.

## 4. Definition of Done (per task)
- [ ] Spec section referenced in PR description
- [ ] Unit tests for `:core` / `:ai` logic; UI has at least a smoke test with fakes
- [ ] `./gradlew check` passes on desktop target; the CI `android` job builds the debug APK (D-24)
- [ ] docs/PLAN.md updated
- [ ] No TODOs without an issue reference

## 5. When you are unsure
Prefer the simpler design. Prefer the one that keeps user data recoverable. Write the question down. Do not invent features not listed in docs/PLAN.md.

## 6. Phase 5 patterns (Studio v2)

### JDK
Java 25 at `/usr/lib/jvm/java-25-amazon-corretto.x86_64`. Always set:
```
export JAVA_HOME=/usr/lib/jvm/java-25-amazon-corretto.x86_64
```

### Feature flags
`FeatureFlags` lives in `:core`. UI reads flags via `koinInject<FeatureFlags>()`. Never read flags in `:core` logic; pass them in at the call site.

### StudioV2 routing (`AppShell.kt`)
```kotlin
var dest by remember { mutableStateOf(StudioV2Dest.HOME) }
var projectScreen by remember { mutableStateOf<String?>(null) }
// PROJECTS routes: null=Dashboard "Recap"/"Input"/"Storyboard"/"Export"=project screen
```

### AI job pipeline
- `AiJob` / `AiJobKind` / `AiJobState` in `:core/recap/`
- `AiJobStore` persists to `AppDirs.aiJobs` (filesDir/ai-jobs/)
- `AiJobRunner` interface in `:core`; desktop impl = `DesktopAiJobRunner` in `ui/desktopMain/`
- `RpmPacer` token-bucket RPM limiter used by the runner
- Android runner uses WorkManager (device spike S10; not in CI)

### Library
- `Library` / `BrandKit` / `SavedVoice` / `MusicItem` / `FontItem` / `Preset` in `:core/library/`
- `LibraryStore` loads/saves `AppDirs.library/library.json`
- `LibraryModel` applies brand kit / voice / music / font / preset to the open project
- Wired by `UiModule` (desktop: `DesktopModule`)

### Smart Fit
- `FitChoice` enum + `SmartFitConfig` in `:core/recap/SmartFit.kt`
- `Recap.fit: Map<Int, FitChoice>` — keyed by scene index
- `RecapFitCard` renders fit choices for RUNS_ON rows
- Actual audio processing (silenceremove + atempo) is device spike S8

### Quick Recap
- `QuickRecap` + `QuickRecapStage` in `:core/recap/QuickRecap.kt`
- `recap.quick` stores wizard progress in `project.json`
- `DashboardModel.createQuickRecap(targetMs, style)` creates the project using `lastParent`
- `HomeScreen` wizard (Length → Style → Confirm) calls this via `AppShell` wiring

### Skiko / UI tests
Skiko rendering tests fail on headless Linux (`Unsupported`). This is pre-existing; CI (Windows) runs them. Never mark these as broken.
