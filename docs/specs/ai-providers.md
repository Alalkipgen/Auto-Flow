# Spec: AI Providers

```kotlin
interface TextProvider  { suspend fun json(prompt: String, schema: JsonSchema, system: String?): AiResult<JsonElement> }
interface ImageProvider { suspend fun image(prompt: String, ratio: Ratio, refImages: List<ByteArray> = emptyList()): AiResult<ByteArray> }
interface TtsProvider   { suspend fun speak(text: String, voice: VoiceRef, lang: String, style: String?): AiResult<ByteArray>
                          val capabilities: TtsCapabilities /* replication, burmese, designedVoices */ }
interface TranscribeProvider { suspend fun transcribe(audio: ByteArray, lang: String): AiResult<Transcript> }

sealed interface AiResult<T> { data class Ok<T>(val value:T, val usage:Usage?):AiResult<T>
  data class RateLimited(val retryAfterMs:Long?):AiResult<Nothing>; data class Failed(val code:String,val msg:String):AiResult<Nothing> }
```
- `Fake*`: deterministic from input hash; configurable delay; produce valid Burmese/English fixture content and solid-colour PNGs with the shot number drawn.
- `Gemini*`: REST via Ktor, API key from SecureStorage, structured output, exponential backoff (1s,2s,4s… max 5 tries) on 429/503, honour `retryAfter`.
- `UsageCounter`: per-model requests/day persisted in `~/.autoflow/usage.json`; UI shows "today: n requests".
- `config/models.json`:
```json
{ "text":  { "default": "gemini-flash-latest", "candidates": ["..."] },
  "image": { "default": "...", "ratios": ["16:9","9:16","1:1","4:5"] },
  "tts":   { "default": "...", "burmese": true, "replication": "unknown" },
  "flow":  { "creditsHint": { "fast": 20, "quality": 100 }, "url": "https://labs.google/fx/tools/flow" } }
```
Values are hints; the app must behave correctly if they are wrong.
