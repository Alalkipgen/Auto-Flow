# Task: Phase 1B — :ai
Implement interfaces and Fake providers per docs/specs/ai-providers.md; then GeminiTextProvider and GeminiImageProvider with backoff and UsageCounter. Add `ProviderRegistry` (Koin) switching fake/real via settings flag. Integration tests against the real API are opt-in (`-PliveAi=true`) and skipped by default. Branch: `work/phase-1-ai`.
