# Spec: AI Providers

```kotlin
interface TextProvider  { suspend fun json(prompt: String, schema: JsonSchema, system: String?): AiResult<JsonElement> }
interface ImageProvider { suspend fun image(prompt: String, ratio: Ratio, refImages: List<ByteArray> = emptyList()): AiResult<ByteArray> }
interface TtsProvider   { suspend fun speak(text: String, voice: VoiceRef, lang: String, style: String?): AiResult<ByteArray>
                          val capabilities: TtsCapabilities /* replication, burmese, designedVoices */ }
interface TranscribeProvider { suspend fun transcribe(audio: ByteArray, lang: String): AiResult<Transcript> }

sealed interface AiResult<T> { data class Ok<T>(val value:T, val usage:Usage?):AiResult<T>
  data class RateLimited(val retryAfterMs:Long?):AiResult<Nothing>; data class Failed(val code:String,val msg:String):AiResult<Nothing>
  /** Manual handoff started; the result arrives later as an imported artifact (D-14). */
  data class Deferred(val instruction:String):AiResult<Nothing> }
```
- `Fake*`: deterministic from input hash; configurable delay; produce valid Burmese/English fixture content and solid-colour PNGs with the shot number drawn.
- `Gemini*`: REST via Ktor, API key from SecureStorage, structured output. Per D-15: model chain = the stage's `candidates` list in order (first = default), tried at every stage call; exponential backoff (1s,2s,4s… max 5 tries) on 500/503, and on 429 only when `retryDelay` ≤ 60 s (else next candidate / `RateLimited`); timeout 120 s per attempt; every running call shows a Cancel button (coroutine cancellation → no partial artifact).
- `ManualImageProvider` (default `ImageProvider`, D-14): puts the ImageDialect prompt on the clipboard, optionally opens AI Studio, and returns `AiResult.Deferred("Generate the image, then Import image (file/inbox)")`. The image enters via Flow Handoff → Import Image / Inbox as kind `im`.
- `GeminiImageProvider`: deferred (D-14). The `ImageProvider` interface stays so it can be added later, capability-gated by a quota probe (429 with `limit: 0` → unavailable).
- `UsageCounter`: per-model requests/day persisted in `~/.autoflow/usage.json`; UI shows "today: n requests".
- `config/models.json`:
```json
{ "text":  { "candidates": ["gemini-3.5-flash", "..."] },
  "image": { "provider": "manual", "ratios": ["16:9","9:16","1:1","4:5"] },
  "tts":   { "default": "...", "burmese": true, "replication": "unknown" },
  "flow":  { "creditsHint": { "fast": 20, "quality": 100 }, "url": "https://labs.google/fx/tools/flow" } }
```
Values are hints; the app must behave correctly if they are wrong.
