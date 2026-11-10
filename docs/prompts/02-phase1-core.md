# Task: Phase 1A — :core

Implement per docs/specs/project-format.md, workflow-and-approval.md, prompt-compiler.md §2–4:
1. `ProjectStore`: create(dir,name,mode), open(dir), save(project) atomic with .bak.
2. `ArtifactStore`: put(bytes, kind, ext, shot?, inputs, provider?, model?, note?) → Artifact; get(id); listByShot(n); exists(id). Content-hash naming, sidecar meta.
3. Models from ARCHITECTURE.md §4 with kotlinx.serialization and schemaVersion.
4. `ShotSplitter.split(durationMs, clipMs, sections?)`: equal clips, last clip absorbs remainder ≥ 2s else merges; section boundaries snap when within 1.5s.
5. `WorkflowRules` gates + `Stale` functions — pure, fully unit-tested.
6. `PromptCompiler` + `VeoFlowDialect` + `ImageDialect` + golden tests (3 cases: narrative, performance, abstract).
No UI, no network. Branch: `work/phase-1-core`.
